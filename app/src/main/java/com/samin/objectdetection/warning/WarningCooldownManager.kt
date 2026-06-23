package com.samin.objectdetection.warning

class WarningCooldownManager(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    private val lastNotifiedAtByKey = mutableMapOf<String, Long>()

    fun canNotify(
        label: String,
        proximityLevel: ProximityLevel,
        horizontalPosition: HorizontalPosition,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val key = buildKey(label, proximityLevel, horizontalPosition)
        val lastNotifiedAt = lastNotifiedAtByKey[key]
        if (lastNotifiedAt != null && nowMs - lastNotifiedAt < cooldownMs) {
            return false
        }

        lastNotifiedAtByKey[key] = nowMs
        return true
    }

    fun buildKey(
        label: String,
        proximityLevel: ProximityLevel,
        horizontalPosition: HorizontalPosition
    ): String {
        return "${label.trim().lowercase()}_${proximityLevel.name}_${horizontalPosition.name}"
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 5_000L
    }
}
