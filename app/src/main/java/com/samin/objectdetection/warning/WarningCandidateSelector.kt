package com.samin.objectdetection.warning

class WarningCandidateSelector {

    fun select(candidates: List<WarningCandidate>): WarningCandidate? {
        return candidates
            .asSequence()
            .filter { it.feedback.shouldNotify }
            .maxWithOrNull(
                compareBy<WarningCandidate> { riskRank(it.riskLevel) }
                    .thenBy { priorityRank(it.priority) }
                    .thenBy { proximityRank(it.proximityLevel) }
                    .thenBy { horizontalPositionRank(it.horizontalPosition) }
                    .thenBy { it.confidence }
            )
    }

    private fun riskRank(riskLevel: RiskLevel): Int {
        return when (riskLevel) {
            RiskLevel.CRITICAL -> 4
            RiskLevel.HIGH -> 3
            RiskLevel.MEDIUM -> 2
            RiskLevel.LOW -> 1
            RiskLevel.NONE -> 0
        }
    }

    private fun priorityRank(priority: ObjectPriority): Int {
        return when (priority) {
            ObjectPriority.HIGH -> 2
            ObjectPriority.LOW -> 1
        }
    }

    private fun proximityRank(proximityLevel: ProximityLevel): Int {
        return when (proximityLevel) {
            ProximityLevel.VERY_NEAR -> 4
            ProximityLevel.NEAR -> 3
            ProximityLevel.MID -> 2
            ProximityLevel.FAR -> 1
        }
    }

    private fun horizontalPositionRank(horizontalPosition: HorizontalPosition): Int {
        return when (horizontalPosition) {
            HorizontalPosition.CENTER -> 2
            HorizontalPosition.LEFT,
            HorizontalPosition.RIGHT -> 1
        }
    }
}
