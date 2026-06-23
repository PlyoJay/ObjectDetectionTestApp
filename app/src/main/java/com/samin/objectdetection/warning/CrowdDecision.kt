package com.samin.objectdetection.warning

import com.samin.objectdetection.detector.DetectionResult

enum class CrowdLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH
}

data class CrowdDecision(
    val crowdLevel: CrowdLevel,
    val totalPersonCount: Int,
    val centerPersonCount: Int,
    val nearPersonCount: Int,
    val message: String?,
    val shouldNotify: Boolean
) {
    val warningKey: String?
        get() = when (crowdLevel) {
            CrowdLevel.HIGH,
            CrowdLevel.MEDIUM -> "crowd_${crowdLevel.name}"
            CrowdLevel.LOW,
            CrowdLevel.NONE -> null
        }

    fun toWarningCandidate(): WarningCandidate? {
        val key = warningKey ?: return null
        val riskLevel = when (crowdLevel) {
            CrowdLevel.HIGH -> RiskLevel.HIGH
            CrowdLevel.MEDIUM -> RiskLevel.MEDIUM
            CrowdLevel.LOW,
            CrowdLevel.NONE -> RiskLevel.NONE
        }
        if (riskLevel == RiskLevel.NONE || message == null || !shouldNotify) return null

        return WarningCandidate(
            label = "crowd",
            confidence = totalPersonCount.toFloat(),
            priority = ObjectPriority.LOW,
            proximityLevel = if (nearPersonCount > 0) ProximityLevel.NEAR else ProximityLevel.MID,
            riskLevel = riskLevel,
            horizontalPosition = if (centerPersonCount > 0) HorizontalPosition.CENTER else HorizontalPosition.LEFT,
            feedback = WarningFeedback(
                riskLevel = riskLevel,
                beepLevel = FeedbackLevel.NONE,
                vibrationLevel = FeedbackLevel.NONE,
                voiceLevel = FeedbackLevel.NONE,
                message = message,
                shouldNotify = true
            ),
            warningKey = key
        )
    }
}

object CrowdDecisionEvaluator {

    fun evaluate(detections: List<DetectionResult>): CrowdDecision {
        val personDetections = detections.filter { it.label.trim().lowercase() == "person" }
        val totalPersonCount = personDetections.size
        val centerPersonCount = personDetections.count { it.horizontalPosition == HorizontalPosition.CENTER }
        val nearPersonCount = personDetections.count {
            it.proximityLevel == ProximityLevel.NEAR || it.proximityLevel == ProximityLevel.VERY_NEAR
        }
        val crowdLevel = when {
            totalPersonCount == 0 -> CrowdLevel.NONE
            centerPersonCount >= 3 -> CrowdLevel.HIGH
            nearPersonCount >= 3 -> CrowdLevel.HIGH
            centerPersonCount >= 2 && nearPersonCount >= 2 -> CrowdLevel.HIGH
            totalPersonCount >= 3 -> CrowdLevel.MEDIUM
            centerPersonCount >= 1 -> CrowdLevel.MEDIUM
            else -> CrowdLevel.LOW
        }
        val message = when (crowdLevel) {
            CrowdLevel.HIGH -> "전방 혼잡"
            CrowdLevel.MEDIUM -> "전방 사람 많음"
            CrowdLevel.LOW,
            CrowdLevel.NONE -> null
        }

        return CrowdDecision(
            crowdLevel = crowdLevel,
            totalPersonCount = totalPersonCount,
            centerPersonCount = centerPersonCount,
            nearPersonCount = nearPersonCount,
            message = message,
            shouldNotify = crowdLevel == CrowdLevel.HIGH || crowdLevel == CrowdLevel.MEDIUM
        )
    }
}
