package com.samin.objectdetection.warning

import com.samin.objectdetection.motion.ApproachSpeedLevel
import com.samin.objectdetection.motion.MotionDirection
import com.samin.objectdetection.policy.ObjectCategory
import com.samin.objectdetection.policy.WarningPriority

class RiskEvaluator {

    fun evaluate(obstacle: ForwardObstacle?): RiskLevel {
        if (obstacle == null) return RiskLevel.NONE

        var riskLevel = baseRisk(obstacle)
        riskLevel = applyCategoryPolicy(obstacle, riskLevel)
        riskLevel = applyPriorityPolicy(obstacle, riskLevel)
        riskLevel = applyMotionPolicy(obstacle, riskLevel)
        riskLevel = enforceMinimumRisk(obstacle, riskLevel)

        return riskLevel
    }

    private fun applyCategoryPolicy(
        obstacle: ForwardObstacle,
        currentRisk: RiskLevel
    ): RiskLevel {
        var riskLevel = currentRisk

        when (obstacle.category) {
            ObjectCategory.VEHICLE -> {
                if (obstacle.detection.motionDirection == MotionDirection.APPROACHING) {
                    riskLevel = increase(riskLevel)
                }
                if (obstacle.detection.approachSpeedLevel == ApproachSpeedLevel.FAST) {
                    riskLevel = increase(riskLevel)
                }
            }
            ObjectCategory.SAFETY -> {
                if (obstacle.proximityLevel.isAtLeast(ProximityLevel.NEAR)) {
                    riskLevel = maxOf(riskLevel, RiskLevel.HIGH)
                }
            }
            ObjectCategory.HUMAN -> {
                if (obstacle.proximityLevel == ProximityLevel.FAR) {
                    riskLevel = minOf(riskLevel, RiskLevel.LOW)
                }
            }
            ObjectCategory.OBSTACLE -> {
                if (obstacle.proximityLevel == ProximityLevel.FAR) {
                    riskLevel = minOf(riskLevel, RiskLevel.LOW)
                }
            }
        }

        return riskLevel
    }

    private fun applyPriorityPolicy(
        obstacle: ForwardObstacle,
        currentRisk: RiskLevel
    ): RiskLevel {
        var riskLevel = currentRisk

        if (obstacle.priority == WarningPriority.CRITICAL) {
            riskLevel = increase(riskLevel)
        }
        if (
            obstacle.priority == WarningPriority.HIGH &&
            obstacle.proximityLevel.isAtLeast(ProximityLevel.NEAR)
        ) {
            riskLevel = increase(riskLevel)
        }

        return riskLevel
    }

    private fun applyMotionPolicy(
        obstacle: ForwardObstacle,
        currentRisk: RiskLevel
    ): RiskLevel {
        return when (obstacle.detection.motionDirection) {
            MotionDirection.APPROACHING -> {
                when (obstacle.detection.approachSpeedLevel) {
                    ApproachSpeedLevel.FAST -> increase(currentRisk)
                    ApproachSpeedLevel.MEDIUM -> {
                        if (obstacle.proximityLevel.isAtLeast(ProximityLevel.MID)) {
                            increase(currentRisk)
                        } else {
                            currentRisk
                        }
                    }
                    ApproachSpeedLevel.NONE,
                    ApproachSpeedLevel.SLOW,
                    ApproachSpeedLevel.UNKNOWN -> currentRisk
                }
            }
            MotionDirection.LEAVING -> decrease(currentRisk)
            MotionDirection.STABLE,
            MotionDirection.UNKNOWN -> currentRisk
        }
    }

    private fun enforceMinimumRisk(
        obstacle: ForwardObstacle,
        currentRisk: RiskLevel
    ): RiskLevel {
        if (
            obstacle.category == ObjectCategory.SAFETY &&
            obstacle.proximityLevel.isAtLeast(ProximityLevel.NEAR)
        ) {
            return maxOf(currentRisk, RiskLevel.HIGH)
        }

        return currentRisk
    }

    private fun baseRisk(obstacle: ForwardObstacle): RiskLevel {
        return when (obstacle.proximityLevel) {
            ProximityLevel.VERY_NEAR -> RiskLevel.CRITICAL
            ProximityLevel.NEAR -> RiskLevel.HIGH
            ProximityLevel.MID -> RiskLevel.MEDIUM
            ProximityLevel.FAR -> RiskLevel.LOW
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

    private fun decrease(riskLevel: RiskLevel): RiskLevel {
        return when (riskLevel) {
            RiskLevel.NONE,
            RiskLevel.LOW -> RiskLevel.NONE
            RiskLevel.MEDIUM -> RiskLevel.LOW
            RiskLevel.HIGH -> RiskLevel.MEDIUM
            RiskLevel.CRITICAL -> RiskLevel.HIGH
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
