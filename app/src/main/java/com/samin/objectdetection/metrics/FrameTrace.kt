package com.samin.objectdetection.metrics

/** Wall timestamps are Unix milliseconds; elapsed timestamps use Android elapsedRealtimeNanos.
 * Camera timestamps are comparable only when captureTimestampReliable is true. */
data class FrameTrace(
    val sessionId: String,
    val frameId: Long,
    val frameTimestampMs: Long,
    val cameraTimestampNs: Long,
    val captureTimestampReliable: Boolean,
    val analyzerReceivedMs: Long,
    val analyzerReceivedElapsedNs: Long,
    val inferenceStartedAtMs: Long? = null,
    val inferenceStartedElapsedNs: Long? = null,
    val completedAtMs: Long? = null,
    val completedElapsedNs: Long? = null,
    val status: String = "SUCCESS",
    val errorStage: String? = null,
    val error: String? = null
) {
    /** -1 means capture age is unknown; analyzer age must not be reported as capture latency. */
    fun captureAgeMsAt(nowElapsedNs: Long): Long =
        if (captureTimestampReliable && cameraTimestampNs > 0L &&
            cameraTimestampNs <= analyzerReceivedElapsedNs && analyzerReceivedElapsedNs <= nowElapsedNs) {
            (nowElapsedNs - cameraTimestampNs) / 1_000_000L
        } else -1L

    val frameAgeAtInferenceMs: Long get() = if (captureTimestampReliable) {
        inferenceStartedElapsedNs?.let { ((it - cameraTimestampNs) / 1_000_000L).coerceAtLeast(0) } ?: -1
    } else -1
    val analyzerToInferenceMs: Long get() = inferenceStartedElapsedNs?.let {
        ((it - analyzerReceivedElapsedNs) / 1_000_000L).coerceAtLeast(0)
    } ?: -1
}

/** Independent windows count events over actual monotonic elapsed time, including idle time. */
class FrameRateMeter(private val windowNs: Long = 1_000_000_000L) {
    private var startedNs: Long? = null
    private var received = 0L
    private var succeeded = 0L
    private var analyzerRate = 0.0
    private var inferenceRate = 0.0

    @Synchronized fun start(nowNs: Long) { startedNs = nowNs; received = 0; succeeded = 0; analyzerRate = 0.0; inferenceRate = 0.0 }
    @Synchronized fun received(nowNs: Long) { roll(nowNs); received++ }
    @Synchronized fun succeeded(nowNs: Long) { roll(nowNs); succeeded++ }
    @Synchronized fun snapshot(nowNs: Long): Rates { roll(nowNs); return Rates(analyzerRate, inferenceRate) }
    private fun roll(nowNs: Long) {
        val start = startedNs ?: nowNs.also { startedNs = it }
        val duration = nowNs - start
        if (duration >= windowNs) {
            analyzerRate = received * 1_000_000_000.0 / duration
            inferenceRate = succeeded * 1_000_000_000.0 / duration
            received = 0; succeeded = 0; startedNs = nowNs
        }
    }
    data class Rates(val analyzerFps: Double, val inferenceFps: Double)
}
