package com.samin.objectdetection.ui

import com.samin.objectdetection.metrics.FrameTrace

/** Main-thread state for one detector. This lifetime applies only to drawing, never warnings. */
internal class OverlayFrameState<T>(timeoutMs: Long) {
    data class Frame<T>(
        val value: T,
        val width: Int,
        val height: Int,
        val trace: FrameTrace,
        val updatedElapsedNs: Long,
        val originElapsedNs: Long,
        val timeBasis: String
    ) {
        fun ageMs(nowNs: Long): Long = (nowNs - originElapsedNs).coerceAtLeast(0) / 1_000_000L
    }

    enum class Update { ACCEPTED, EXPIRED_AT_UPDATE, OUT_OF_ORDER, INVALID_TIME, DISABLED, BEFORE_RESET }

    private var timeoutMs = timeoutMs.coerceIn(100L, 60_000L)
    private var active = true
    private var minimumOriginNs = 0L
    private var latestFrameId = Long.MIN_VALUE
    var frame: Frame<T>? = null
        private set

    fun configure(active: Boolean, timeoutMs: Long, nowNs: Long) {
        expire(nowNs) // Increasing the timeout must not revive a frame that has already expired.
        if (this.active != active) clear(nowNs)
        this.active = active
        this.timeoutMs = timeoutMs.coerceIn(100L, 60_000L)
        expire(nowNs)
    }

    fun update(value: T, width: Int, height: Int, trace: FrameTrace, nowNs: Long): Update {
        if (trace.frameId <= latestFrameId) return Update.OUT_OF_ORDER
        latestFrameId = trace.frameId // Includes rejected/empty results; never reuse an older frame.
        frame = null
        if (!active) return Update.DISABLED
        val cameraTimeValid = trace.captureAgeMsAt(nowNs) >= 0
        val originNs = if (cameraTimeValid) trace.cameraTimestampNs else trace.analyzerReceivedElapsedNs
        if (originNs <= 0 || originNs > nowNs) return Update.INVALID_TIME
        if (originNs < minimumOriginNs) return Update.BEFORE_RESET
        val candidate = Frame(value, width.coerceAtLeast(1), height.coerceAtLeast(1), trace, nowNs,
            originNs, if (cameraTimeValid) "CAMERA_CAPTURE" else "ANALYZER_RECEIVED")
        if (remainingNs(candidate, nowNs) <= 0) return Update.EXPIRED_AT_UPDATE
        frame = candidate
        return Update.ACCEPTED
    }

    fun expire(nowNs: Long): Frame<T>? {
        val current = frame ?: return null
        if (remainingNs(current, nowNs) > 0) return null
        frame = null
        return current
    }

    /** Rounded up so an early timer can be rearmed without a busy loop. */
    fun remainingDelayMs(nowNs: Long): Long? = frame?.let {
        val remaining = remainingNs(it, nowNs)
        if (remaining <= 0) 0L else (remaining + 999_999L) / 1_000_000L
    }

    fun clear(nowNs: Long) {
        frame = null
        minimumOriginNs = nowNs
    }

    private fun remainingNs(frame: Frame<T>, nowNs: Long): Long =
        timeoutMs * 1_000_000L - (nowNs - frame.originElapsedNs)
}
