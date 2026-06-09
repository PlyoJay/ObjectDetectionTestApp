package com.samin.objectdetection.warning

import com.samin.objectdetection.motion.ApproachSpeedLevel
import com.samin.objectdetection.motion.MotionDirection
import com.samin.objectdetection.policy.ObjectCategory
import com.samin.objectdetection.policy.WarningPriority

class RiskEvaluator {

    fun evaluate(obstacle: ForwardObstacle?): RiskLevel {
        if (obstacle == null) return RiskLevel.NONE

        var riskLevel = baseRisk(obstacle)

        if (
            obstacle.priority == WarningPriority.CRITICAL &&
            obstacle.proximityLevel.isAtLeast(ProximityLevel.NEAR)
        ) {
            riskLevel = RiskLevel.CRITICAL
        }

        if (
            obstacle.priority == WarningPriority.HIGH &&
            obstacle.proximityLevel == ProximityLevel.VERY_NEAR
        ) {
            riskLevel = RiskLevel.CRITICAL
        }

        if (
            obstacle.priority == WarningPriority.HIGH &&
            obstacle.proximityLevel == ProximityLevel.NEAR
        ) {
            riskLevel = maxOf(riskLevel, RiskLevel.HIGH)
        }

        if (
            obstacle.category == ObjectCategory.HUMAN ||
            obstacle.category == ObjectCategory.VEHICLE
        ) {
            if (obstacle.proximityLevel.isAtLeast(ProximityLevel.NEAR)) {
                riskLevel = increase(riskLevel)
            }
        }

        if (obstacle.detection.motionDirection == MotionDirection.APPROACHING) {
            riskLevel = when (obstacle.detection.approachSpeedLevel) {
                ApproachSpeedLevel.FAST -> increase(riskLevel)
                ApproachSpeedLevel.MEDIUM -> {
                    if (
                        obstacle.proximityLevel.isAtLeast(ProximityLevel.NEAR) ||
                        obstacle.priority == WarningPriority.CRITICAL ||
                        obstacle.priority == WarningPriority.HIGH
                    ) {
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
            ProximityLevel.VERY_NEAR -> RiskLevel.HIGH
            ProximityLevel.NEAR -> RiskLevel.MEDIUM
            ProximityLevel.MID -> RiskLevel.LOW
            ProximityLevel.FAR -> {
                if (
                    obstacle.priority == WarningPriority.CRITICAL ||
                    obstacle.priority == WarningPriority.HIGH
                ) {
                    RiskLevel.LOW
                } else {
                    RiskLevel.NONE
                }
            }
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
