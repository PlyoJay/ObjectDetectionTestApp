package com.samin.objectdetection.warning

import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.policy.ObjectTuningPolicyRegistry

class WarningCandidateSelector {

    fun eligibleCandidates(detections: List<DetectionResult>, ignoreTopRatio: Float, limit: Int,
        warningKey: (DetectionResult) -> String): List<WarningCandidate> = ranked(
        detections.filter { ObjectTuningPolicyRegistry.shouldWarn(it) && it.centerYRatio >= ignoreTopRatio }
            .map { WarningCandidate.fromDetection(it, warningKey(it)) }, limit)

    fun select(candidates: List<WarningCandidate>): WarningCandidate? {
        return ranked(candidates).firstOrNull()
    }

    /** Limit only after risk/priority/proximity ranking, never detector confidence order. */
    fun ranked(candidates: List<WarningCandidate>, limit: Int = Int.MAX_VALUE): List<WarningCandidate> {
        return candidates
            .asSequence()
            .filter { it.feedback.shouldNotify }
            .sortedWith(
                compareBy<WarningCandidate> { riskRank(it.riskLevel) }
                    .thenBy { priorityRank(it.priority) }
                    .thenBy { proximityRank(it.proximityLevel) }
                    .thenBy { horizontalPositionRank(it.horizontalPosition) }
                    .thenBy { it.confidence }.reversed()
            )
            .take(limit.coerceAtLeast(1))
            .toList()
    }

    fun selectWithCrowd(
        candidates: List<WarningCandidate>,
        crowdCandidate: WarningCandidate?
    ): WarningCandidate? {
        val selectedObjectCandidate = select(candidates)
        if (crowdCandidate == null || !crowdCandidate.feedback.shouldNotify) {
            return selectedObjectCandidate
        }
        if (hasHighPriorityUrgentCandidate(candidates)) {
            return selectedObjectCandidate
        }

        return select(candidates + crowdCandidate)
    }

    private fun hasHighPriorityUrgentCandidate(candidates: List<WarningCandidate>): Boolean {
        return candidates.any { candidate ->
            candidate.feedback.shouldNotify &&
                candidate.priority == ObjectPriority.HIGH &&
                (candidate.riskLevel == RiskLevel.CRITICAL || candidate.riskLevel == RiskLevel.HIGH)
        }
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
