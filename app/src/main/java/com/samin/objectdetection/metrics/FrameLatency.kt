package com.samin.objectdetection.metrics

/** Sparse observations, separate from FRAME completion statistics. All clocks are elapsedRealtimeNanos. */
sealed interface FrameLatencyEvent {
    val observedElapsedNs: Long

    data class Received(override val observedElapsedNs: Long) : FrameLatencyEvent

    data class Analyzer(
        val workEndedElapsedNs: Long,
        val closeStartedElapsedNs: Long,
        val closeEndedElapsedNs: Long,
        override val observedElapsedNs: Long,
        val closeSucceeded: Boolean,
        val outcome: String
    ) : FrameLatencyEvent

    data class MlKit(
        val dispatchedElapsedNs: Long,
        val requestedElapsedNs: Long,
        override val observedElapsedNs: Long,
        val outcome: String,
        val completionBasis: String
    ) : FrameLatencyEvent

    data class Warning(
        val candidateFinalizedElapsedNs: Long,
        val submittedElapsedNs: Long,
        override val observedElapsedNs: Long,
        val candidatePresent: Boolean,
        val accepted: Boolean,
        val skippedReason: String?
    ) : FrameLatencyEvent

    data class Output(
        val kind: OutputKind,
        val observation: String,
        override val observedElapsedNs: Long,
        val requestStartedElapsedNs: Long = -1L,
        val requestReturnedElapsedNs: Long = -1L,
        val startedElapsedNs: Long = -1L,
        val accepted: Boolean? = null,
        val pulseIndex: Int = 0,
        val reason: String? = null
    ) : FrameLatencyEvent
}

enum class OutputKind { TTS, BEEP, VIBRATION }

/** Captured once at analyzer receipt. The sink belongs to that file session, never the next recorder. */
class FrameLatencyRecorder(
    val trace: FrameTrace,
    private val elapsedNow: () -> Long,
    private val sink: (FrameLatencyEvent) -> Unit
) {
    fun nowNs(): Long = elapsedNow()

    fun record(event: FrameLatencyEvent) {
        // Diagnostics must not change camera/inference/output error handling.
        try { sink(event) } catch (_: Exception) { }
    }
}

/** No new clock reads/objects when logging is off. Close runs exactly once, even on analyzer errors. */
inline fun finishAnalyzerFrame(latency: FrameLatencyRecorder?, outcome: String, close: () -> Unit) {
    if (latency == null) { close(); return }
    val workEnded = latency.nowNs()
    val closeStarted = latency.nowNs()
    var succeeded = false
    try {
        close()
        succeeded = true
    } finally {
        val closeEnded = latency.nowNs()
        // This is the exit observation before telemetry enqueue, not the executor's return instruction.
        latency.record(FrameLatencyEvent.Analyzer(workEnded, closeStarted, closeEnded,
            latency.nowNs(), succeeded, outcome))
    }
}

/** Observes the actual API invocation, not a Handler/coroutine scheduling time or physical actuator onset. */
inline fun observeOutputRequest(
    latency: FrameLatencyRecorder?, kind: OutputKind, pulseIndex: Int = 0, request: () -> Boolean
): Boolean {
    if (latency == null) return request()
    val started = latency.nowNs()
    var accepted: Boolean? = null
    try {
        return request().also { accepted = it }
    } finally {
        val returned = latency.nowNs()
        latency.record(FrameLatencyEvent.Output(kind, "API_REQUEST", returned,
            requestStartedElapsedNs = started, requestReturnedElapsedNs = returned,
            accepted = accepted, pulseIndex = pulseIndex,
            reason = if (accepted == null) "API_EXCEPTION" else null))
    }
}

/** Bounded TTS callback association. Callback omission, duplicate callbacks and QUEUE_FLUSH are observable. */
class TtsStartTracker(private val elapsedNow: () -> Long, private val capacity: Int = 32) {
    class Request internal constructor(
        val id: String, val latency: FrameLatencyRecorder
    ) {
        @Volatile var requestedElapsedNs: Long = -1L
            internal set
    }

    private val pending = LinkedHashMap<String, Request>()

    init { require(capacity > 0) }

    fun register(id: String, latency: FrameLatencyRecorder): Request {
        val request = Request(id, latency)
        val evicted = synchronized(pending) {
            val replaced = pending.put(id, request)
            replaced ?: if (pending.size > capacity) pending.remove(pending.keys.first()) else null
        }
        evicted?.let { unavailable(it, "ASSOCIATION_EVICTED", elapsedNow()) }
        // Timestamp after bounded association bookkeeping, immediately before returning to speak().
        request.requestedElapsedNs = elapsedNow()
        return request
    }

    fun requestReturned(request: Request, accepted: Boolean?, reason: String? = null) {
        val now = elapsedNow()
        request.latency.record(FrameLatencyEvent.Output(OutputKind.TTS, "API_REQUEST", now,
            requestStartedElapsedNs = request.requestedElapsedNs, requestReturnedElapsedNs = now,
            accepted = accepted, reason = reason))
        if (accepted != true) synchronized(pending) {
            if (pending[request.id] === request) pending.remove(request.id)
        }
    }

    fun onStart(id: String) {
        val now = elapsedNow()
        val request = synchronized(pending) { pending.remove(id) } ?: return
        request.latency.record(FrameLatencyEvent.Output(OutputKind.TTS, "TTS_ON_START", now,
            requestStartedElapsedNs = request.requestedElapsedNs, startedElapsedNs = now))
    }

    fun terminal(id: String, reason: String) {
        val now = elapsedNow()
        val request = synchronized(pending) { pending.remove(id) } ?: return
        unavailable(request, reason, now)
    }

    fun clear(reason: String) {
        val now = elapsedNow()
        val removed = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        removed.forEach { unavailable(it, reason, now) }
    }

    private fun unavailable(request: Request, reason: String, now: Long) {
        request.latency.record(FrameLatencyEvent.Output(OutputKind.TTS, "TTS_START_UNAVAILABLE", now,
            requestStartedElapsedNs = request.requestedElapsedNs, reason = reason))
    }
}
