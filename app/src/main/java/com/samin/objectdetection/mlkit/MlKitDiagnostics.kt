package com.samin.objectdetection.mlkit

/** Opt-in text diagnostics, independent of FRAME/LATENCY and detector/PNG debug logging. */
internal class MlKitDiagnostics(
    private val sessionId: String,
    private val nowNs: () -> Long,
    private val sink: (String) -> Unit
) {
    @Volatile var enabled: () -> Boolean = { true }
    private var lastSkipNs: Long? = null
    private var suppressedSkips = 0L
    @Volatile private var activeFrame = -1L
    @Volatile private var acquiredNs = -1L
    private var requests = 0L

    fun event(frameId: Long, stage: String, detail: () -> String) {
        if (!enabled()) return
        // Diagnostics must never change SDK completion, lease release or rendering.
        try { sink("[MLKIT_DIAG] session=$sessionId frame=$frameId stage=$stage ${detail()}") }
        catch (_: Exception) { }
    }

    @Synchronized
    fun skipped(frameId: Long, reason: String, inFlight: Boolean) {
        if (!enabled()) return
        val now = nowNs()
        val previous = lastSkipNs
        if (previous != null && now - previous < SKIP_INTERVAL_NS) {
            suppressedSkips++
            return
        }
        lastSkipNs = now
        val suppressed = suppressedSkips
        suppressedSkips = 0L
        event(frameId, "SKIP") {
            "reason=$reason inFlight=$inFlight activeFrame=$activeFrame " +
                "inFlightMs=${if (inFlight && acquiredNs >= 0) (now - acquiredNs) / 1_000_000L else -1} " +
                "suppressedSkips=$suppressed"
        }
    }

    @Synchronized
    fun acquired(frameId: Long) {
        activeFrame = frameId
        acquiredNs = nowNs()
        requests++
        event(frameId, "LEASE_ACQUIRED") { "requestIndex=$requests inFlight=true" }
    }

    @Synchronized
    fun released(frameId: Long) {
        val heldMs = if (activeFrame == frameId) (nowNs() - acquiredNs) / 1_000_000L else -1L
        if (activeFrame == frameId) {
            activeFrame = -1L
            acquiredNs = -1L
        }
        event(frameId, "LEASE_RELEASED") { "leaseReturned=true inFlight=${activeFrame >= 0} activeFrame=$activeFrame heldMs=$heldMs" }
    }

    companion object {
        private const val SKIP_INTERVAL_NS = 5_000_000_000L
    }
}
