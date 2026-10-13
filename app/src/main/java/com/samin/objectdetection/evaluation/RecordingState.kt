package com.samin.objectdetection.evaluation

/** Session IDs reject late broadcasts; STOPPING continues recording until the service confirms stop. */
class RecordingState {
    enum class Phase { IDLE, STARTING, RECORDING, STOPPING, FINALIZING }
    @Volatile var phase = Phase.IDLE
        private set
    @Volatile var sessionId: String? = null
        private set
    val busy get() = phase != Phase.IDLE
    val acceptingFrames get() = phase == Phase.RECORDING || phase == Phase.STOPPING
    @Synchronized fun isAwaiting(id: String, expected: Phase): Boolean =
        sessionId == id && phase == expected && (expected == Phase.STARTING || expected == Phase.STOPPING)
    @Synchronized fun begin(id: String): Boolean {
        if (busy) return false
        sessionId = id; phase = Phase.STARTING; return true
    }
    @Synchronized fun started(id: String): Boolean {
        if (sessionId != id || phase != Phase.STARTING) return false
        phase = Phase.RECORDING; return true
    }
    @Synchronized fun requestStop(): Boolean {
        if (phase != Phase.RECORDING && phase != Phase.STARTING) return false
        phase = Phase.STOPPING; return true
    }
    @Synchronized fun finalizing(id: String): Boolean {
        if (sessionId != id || phase == Phase.FINALIZING || phase == Phase.IDLE) return false
        phase = Phase.FINALIZING; return true
    }
    @Synchronized fun finished(id: String): Boolean {
        if (sessionId != id || phase != Phase.FINALIZING) return false
        sessionId = null; phase = Phase.IDLE; return true
    }
}
