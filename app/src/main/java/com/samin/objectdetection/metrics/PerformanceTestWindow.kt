package com.samin.objectdetection.metrics

enum class PerformanceStopReason { AUTO_DURATION, MANUAL, ACTIVITY_DESTROYED, ERROR }

/** Monotonic time only; independent of frames, preview, diagnostics and interpreter threads. */
data class PerformanceTestWindow(val startedElapsedNs: Long, val durationMinutes: Int = 5) {
    init { require(durationMinutes in ALLOWED_MINUTES) }
    val durationMs: Long get() = durationMinutes * 60_000L
    val deadlineNs: Long get() = startedElapsedNs + durationMs * 1_000_000L
    fun remainingMs(nowNs: Long): Long = ((deadlineNs - nowNs).coerceAtLeast(0) + 999_999) / 1_000_000
    fun accepts(completedNs: Long): Boolean = completedNs >= startedElapsedNs && completedNs < deadlineNs
    fun stopReason(requested: PerformanceStopReason, nowNs: Long): PerformanceStopReason =
        if (nowNs >= deadlineNs && requested == PerformanceStopReason.MANUAL) PerformanceStopReason.AUTO_DURATION else requested

    companion object { val ALLOWED_MINUTES = listOf(3, 5, 10) }
}
