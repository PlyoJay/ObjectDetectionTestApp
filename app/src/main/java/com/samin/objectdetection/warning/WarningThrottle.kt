package com.samin.objectdetection.warning

class WarningThrottle(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    private var lastMessage: String? = null
    private var lastWarnTimeMs: Long = 0L

    fun canShow(
        message: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val canShow = lastMessage != message || nowMs - lastWarnTimeMs >= cooldownMs

        if (canShow) {
            lastMessage = message
            lastWarnTimeMs = nowMs
        }

        return canShow
    }

    companion object {
        private const val DEFAULT_COOLDOWN_MS = 2_500L
    }
}
