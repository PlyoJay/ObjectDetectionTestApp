package com.samin.objectdetection.warning

import com.samin.objectdetection.policy.WarningPriority

class WarningStabilizer(
    private val cooldownMs: Long = 3000L
) {
    private var lastDecision: WarningDecision? = null
    private var lastAcceptedAtMs: Long = 0L

    fun stabilize(decision: WarningDecision): WarningDecision {
        val now = System.currentTimeMillis()
        val previous = lastDecision

        if (decision.obstacle == null) {
            lastDecision = decision
            lastAcceptedAtMs = now
            return decision
        }

        if (previous == null || previous.obstacle == null) {
            lastDecision = decision
            lastAcceptedAtMs = now
            return decision
        }

        if (isMoreDangerous(decision, previous)) {
            lastDecision = decision
            lastAcceptedAtMs = now
            return decision
        }

        if (isSameWarning(decision, previous) && now - lastAcceptedAtMs < cooldownMs) {
            return previous
        }

        lastDecision = decision
        lastAcceptedAtMs = now
        return decision
    }

    private fun isSameWarning(current: WarningDecision, previous: WarningDecision): Boolean {
        val currentObstacle = current.obstacle ?: return false
        val previousObstacle = previous.obstacle ?: return false
        return currentObstacle.detection.label == previousObstacle.detection.label &&
            currentObstacle.proximityLevel == previousObstacle.proximityLevel &&
            currentObstacle.priority == previousObstacle.priority
    }

    private fun isMoreDangerous(current: WarningDecision, previous: WarningDecision): Boolean {
        val currentObstacle = current.obstacle ?: return false
        val previousObstacle = previous.obstacle ?: return true

        val currentPriority = priorityRank(currentObstacle.priority)
        val previousPriority = priorityRank(previousObstacle.priority)
        if (currentPriority != previousPriority) return currentPriority < previousPriority

        val currentProximity = proximityRank(currentObstacle.proximityLevel)
        val previousProximity = proximityRank(previousObstacle.proximityLevel)
        if (currentProximity != previousProximity) return currentProximity < previousProximity

        return currentObstacle.score > previousObstacle.score
    }

    private fun priorityRank(priority: WarningPriority): Int {
        return when (priority) {
            WarningPriority.CRITICAL -> 0
            WarningPriority.HIGH -> 1
            WarningPriority.MEDIUM -> 2
            WarningPriority.LOW -> 3
            WarningPriority.NONE -> 4
        }
    }

    private fun proximityRank(proximityLevel: ProximityLevel): Int {
        return when (proximityLevel) {
            ProximityLevel.VERY_NEAR -> 0
            ProximityLevel.NEAR -> 1
            ProximityLevel.MID -> 2
            ProximityLevel.FAR -> 3
        }
    }
}
