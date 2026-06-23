package com.samin.objectdetection.warning

class WarningCooldownManager(
    private val defaultCooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    private val lastNotifiedAtByKey = mutableMapOf<String, Long>()

    fun canNotify(
        label: String,
        priority: ObjectPriority,
        proximityLevel: ProximityLevel,
        horizontalPosition: HorizontalPosition,
        nowMs: Long = System.currentTimeMillis(),
        cooldownMs: Long = defaultCooldownMs
    ): Boolean {
        val key = buildKey(label, priority, proximityLevel, horizontalPosition)
        return canNotify(key, nowMs, cooldownMs)
    }

    fun canNotify(
        warningKey: String,
        nowMs: Long = System.currentTimeMillis(),
        cooldownMs: Long = defaultCooldownMs
    ): Boolean {
        val key = warningKey.trim()
        val lastNotifiedAt = lastNotifiedAtByKey[key]
        return lastNotifiedAt == null || nowMs - lastNotifiedAt >= cooldownMs
    }

    fun markNotified(
        warningKey: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val key = warningKey.trim()
        lastNotifiedAtByKey[key] = nowMs
    }

    fun buildKey(
        label: String,
        priority: ObjectPriority,
        proximityLevel: ProximityLevel,
        horizontalPosition: HorizontalPosition
    ): String {
        return "${label.trim().lowercase()}_${priority.name}_${proximityLevel.name}_${horizontalPosition.name}"
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 5_000L
    }
}
