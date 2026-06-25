package com.samin.objectdetection.warning

import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.motion.MotionDirection
import com.samin.objectdetection.motion.UserObjectRelation

enum class ProximityLevel {
    FAR,
    MID,
    NEAR,
    VERY_NEAR
}

enum class RiskLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

enum class FeedbackLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH
}

enum class ObjectPriority(val level: Int) {
    HIGH(level = 1),
    LOW(level = 3)
}

enum class RiskObjectCategory {
    HUMAN_FLOW,
    VEHICLE_RISK,
    TRAFFIC_CONTROL,
    STATIC_OBSTACLE,
    TEMPORARY_OBSTACLE,
    UNKNOWN
}

typealias VisionObjectCategory = RiskObjectCategory

enum class HorizontalPosition {
    LEFT,
    CENTER,
    RIGHT
}

enum class CrowdLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH
}

enum class WarningScenario {
    IMMEDIATE_DANGER,
    APPROACHING_OBJECT,
    FRONT_VEHICLE,
    FRONT_OBSTACLE,
    CROWD,
    TRAFFIC_INFO,
    MONITORING
}

data class WarningFeedback(
    val riskLevel: RiskLevel,
    val beepLevel: FeedbackLevel,
    val vibrationLevel: FeedbackLevel,
    val voiceLevel: FeedbackLevel,
    val message: String?,
    val shouldNotify: Boolean
) {
    companion object {
        val NONE = WarningFeedback(
            riskLevel = RiskLevel.NONE,
            beepLevel = FeedbackLevel.NONE,
            vibrationLevel = FeedbackLevel.NONE,
            voiceLevel = FeedbackLevel.NONE,
            message = null,
            shouldNotify = false
        )
    }
}

data class WarningCandidate(
    val label: String,
    val confidence: Float,
    val priority: ObjectPriority,
    val category: RiskObjectCategory = RiskObjectCategory.UNKNOWN,
    val proximityLevel: ProximityLevel,
    val riskLevel: RiskLevel,
    val motionDirection: MotionDirection = MotionDirection.UNKNOWN,
    val userObjectRelation: UserObjectRelation = UserObjectRelation.UNKNOWN,
    val warningScenario: WarningScenario = WarningScenario.MONITORING,
    val horizontalPosition: HorizontalPosition,
    val feedback: WarningFeedback,
    val warningKey: String
) {
    companion object {
        fun fromDetection(
            detection: DetectionResult,
            warningKey: String
        ): WarningCandidate {
            return WarningCandidate(
                label = detection.label,
                confidence = detection.confidence,
                priority = detection.objectPriority,
                category = detection.riskObjectCategory,
                proximityLevel = detection.proximityLevel,
                riskLevel = detection.riskLevel,
                motionDirection = detection.motionDirection,
                userObjectRelation = detection.userObjectRelation,
                warningScenario = detection.warningScenario,
                horizontalPosition = detection.horizontalPosition,
                feedback = detection.warningFeedback,
                warningKey = warningKey
            )
        }
    }
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
            category = RiskObjectCategory.HUMAN_FLOW,
            proximityLevel = if (nearPersonCount > 0) ProximityLevel.NEAR else ProximityLevel.MID,
            riskLevel = riskLevel,
            motionDirection = MotionDirection.UNKNOWN,
            userObjectRelation = UserObjectRelation.UNKNOWN,
            warningScenario = WarningScenario.CROWD,
            horizontalPosition = if (centerPersonCount > 0) HorizontalPosition.CENTER else HorizontalPosition.LEFT,
            feedback = WarningPolicy.buildCrowdFeedback(crowdLevel, riskLevel, message),
            warningKey = key
        )
    }
}
