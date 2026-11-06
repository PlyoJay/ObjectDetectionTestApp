package com.samin.objectdetection.tof

/** One TinyF sample. sensorId identifies hardware, never a mounting role.
 * measuredAt is monotonic nanoseconds (SystemClock.elapsedRealtimeNanos on Android),
 * captured by the future UART receiver. confidence preserves the raw device value;
 * its scale and threshold must be confirmed against the protocol.
 * Samples are not associated with images or detection boxes here.
 */
data class TofMeasurement(
    val sensorId: String,
    val measuredAt: Long,
    val distanceMm: Int?,
    val confidence: Int?,
    val valid: Boolean,
    val status: TofStatus
)

enum class TofStatus { OK, NOT_CONNECTED, INVALID_READING, OUT_OF_RANGE, STALE, ERROR }

/** One receiver per channel; two independent instances can be consumed by sensorId.
 * Returns null before the first sample. A future implementation must mark stale/disconnected
 * readings invalid instead of reusing an earlier valid value. No UART implementation yet.
 */
interface TofMeasurementSource {
    val sensorId: String
    fun latest(): TofMeasurement?
}
