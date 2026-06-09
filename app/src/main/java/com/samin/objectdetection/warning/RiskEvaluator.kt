package com.samin.objectdetection.warning

import com.samin.objectdetection.motion.ApproachSpeedLevel
import com.samin.objectdetection.motion.MotionDirection
import com.samin.objectdetection.policy.WarningPriority

class RiskEvaluator {

    fun evaluate(obstacle: ForwardObstacle?): RiskLevel {
        if (obstacle == null) return RiskLevel.NONE

        var riskLevel = baseRisk(obstacle)

        if (obstacle.priority == WarningPriority.CRITICAL) {
            riskLevel = increase(riskLevel)
        }

        if (
            obstacle.priority == WarningPriority.HIGH &&
            obstacle.proximityLevel.isAtLeast(ProximityLevel.NEAR)
        ) {
            riskLevel = increase(riskLevel)
        }

        if (obstacle.detection.motionDirection == MotionDirection.APPROACHING) {
            riskLevel = when (obstacle.detection.approachSpeedLevel) {
                ApproachSpeedLevel.FAST -> increase(riskLevel)
                ApproachSpeedLevel.MEDIUM -> {
                    if (obstacle.proximityLevel.isAtLeast(ProximityLevel.MID)) {
                        increase(riskLevel)
                    } else {
                        riskLevel
                    }
                }
                ApproachSpeedLevel.NONE,
                ApproachSpeedLevel.SLOW,
                ApproachSpeedLevel.UNKNOWN -> riskLevel
            }
        }

        return riskLevel
    }

    private fun baseRisk(obstacle: ForwardObstacle): RiskLevel {
        return when (obstacle.proximityLevel) {
            ProximityLevel.FAR -> RiskLevel.LOW
            ProximityLevel.MID -> RiskLevel.MEDIUM
            ProximityLevel.NEAR -> RiskLevel.HIGH
            ProximityLevel.VERY_NEAR -> RiskLevel.CRITICAL
        }
    }

    private fun increase(riskLevel: RiskLevel): RiskLevel {
        return when (riskLevel) {
            RiskLevel.NONE -> RiskLevel.LOW
            RiskLevel.LOW -> RiskLevel.MEDIUM
            RiskLevel.MEDIUM -> RiskLevel.HIGH
            RiskLevel.HIGH,
            RiskLevel.CRITICAL -> RiskLevel.CRITICAL
        }
    }

    private fun ProximityLevel.isAtLeast(threshold: ProximityLevel): Boolean {
        return proximityRank(this) <= proximityRank(threshold)
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
