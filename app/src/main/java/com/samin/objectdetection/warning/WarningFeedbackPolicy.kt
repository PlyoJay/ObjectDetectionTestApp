package com.samin.objectdetection.warning

import android.util.Log
import com.samin.objectdetection.detector.DetectionResult

object WarningFeedbackPolicy {

    fun evaluate(
        detection: DetectionResult,
        cooldownManager: WarningCooldownManager,
        nowMs: Long = System.currentTimeMillis()
    ): WarningFeedback {
        val baseFeedback = mapRiskToFeedback(
            label = detection.label,
            category = detection.riskObjectCategory,
            riskLevel = detection.riskLevel
        )
        val cooldownPassed = if (baseFeedback.shouldNotify) {
            cooldownManager.canNotify(
                label = detection.label,
                proximityLevel = detection.proximityLevel,
                horizontalPosition = detection.horizontalPosition,
                nowMs = nowMs
            )
        } else {
            false
        }
        val feedback = baseFeedback.copy(shouldNotify = baseFeedback.shouldNotify && cooldownPassed)

        Log.d(
            TAG,
            "label=${detection.label}, conf=${format(detection.confidence)}, " +
                "proximityLevel=${detection.proximityLevel}, riskLevel=${detection.riskLevel}, " +
                "beepLevel=${feedback.beepLevel}, vibrationLevel=${feedback.vibrationLevel}, " +
                "voiceLevel=${feedback.voiceLevel}, message=${feedback.message}, " +
                "cooldownPassed=$cooldownPassed, shouldNotify=${feedback.shouldNotify}"
        )

        return feedback
    }

    fun buildWarningMessage(
        label: String,
        category: VisionObjectCategory,
        riskLevel: RiskLevel
    ): String? {
        val objectName = labelToKorean(label)
        val objectSubject = objectSubjectPhrase(objectName)
        return when (riskLevel) {
            RiskLevel.CRITICAL -> when (category) {
                RiskObjectCategory.VEHICLE_RISK -> "정지! 전방에 ${objectSubject} 있습니다."
                RiskObjectCategory.HUMAN_FLOW -> "정지! 전방에 사람이 있습니다."
                RiskObjectCategory.STATIC_OBSTACLE,
                RiskObjectCategory.TEMPORARY_OBSTACLE -> "정지! 전방 가까이에 장애물이 있습니다."
                RiskObjectCategory.TRAFFIC_CONTROL -> "정지! 전방에 ${objectSubject} 있습니다."
                RiskObjectCategory.UNKNOWN -> "정지! 전방에 객체가 있습니다."
            }
            RiskLevel.HIGH -> when (category) {
                RiskObjectCategory.VEHICLE_RISK -> "전방에 ${objectSubject} 있습니다."
                RiskObjectCategory.HUMAN_FLOW -> "전방에 사람이 있습니다."
                RiskObjectCategory.STATIC_OBSTACLE,
                RiskObjectCategory.TEMPORARY_OBSTACLE -> "전방 장애물 주의"
                RiskObjectCategory.TRAFFIC_CONTROL -> "전방 ${objectName} 주의"
                RiskObjectCategory.UNKNOWN -> "전방 객체 주의"
            }
            RiskLevel.MEDIUM -> when (category) {
                RiskObjectCategory.VEHICLE_RISK -> "차량 주의"
                RiskObjectCategory.HUMAN_FLOW -> "사람 주의"
                RiskObjectCategory.STATIC_OBSTACLE,
                RiskObjectCategory.TEMPORARY_OBSTACLE -> "장애물 주의"
                RiskObjectCategory.TRAFFIC_CONTROL -> "${objectName} 주의"
                RiskObjectCategory.UNKNOWN -> "전방 주의"
            }
            RiskLevel.LOW,
            RiskLevel.NONE -> null
        }
    }

    fun labelToKorean(label: String): String {
        return when (label.trim().lowercase()) {
            "person" -> "사람"
            "bicycle" -> "자전거"
            "car" -> "자동차"
            "motorcycle" -> "오토바이"
            "bus" -> "버스"
            "truck" -> "트럭"
            "traffic light" -> "신호등"
            "stop sign" -> "정지 표지판"
            "bench" -> "벤치"
            "fire hydrant" -> "소화전"
            "chair" -> "의자"
            "bollard" -> "볼라드"
            else -> "객체"
        }
    }

    private fun objectSubjectPhrase(objectName: String): String {
        return when (objectName) {
            "자동차",
            "자전거",
            "오토바이" -> "${objectName}가"
            else -> "${objectName}이"
        }
    }

    private fun mapRiskToFeedback(
        label: String,
        category: VisionObjectCategory,
        riskLevel: RiskLevel
    ): WarningFeedback {
        val message = buildWarningMessage(label, category, riskLevel)
        return when (riskLevel) {
            RiskLevel.CRITICAL -> WarningFeedback(
                riskLevel = riskLevel,
                beepLevel = FeedbackLevel.HIGH,
                vibrationLevel = FeedbackLevel.HIGH,
                voiceLevel = FeedbackLevel.HIGH,
                message = message,
                shouldNotify = true
            )
            RiskLevel.HIGH -> WarningFeedback(
                riskLevel = riskLevel,
                beepLevel = FeedbackLevel.MEDIUM,
                vibrationLevel = FeedbackLevel.MEDIUM,
                voiceLevel = FeedbackLevel.MEDIUM,
                message = message,
                shouldNotify = true
            )
            RiskLevel.MEDIUM -> WarningFeedback(
                riskLevel = riskLevel,
                beepLevel = FeedbackLevel.LOW,
                vibrationLevel = FeedbackLevel.LOW,
                voiceLevel = FeedbackLevel.LOW,
                message = message,
                shouldNotify = true
            )
            RiskLevel.LOW,
            RiskLevel.NONE -> WarningFeedback(
                riskLevel = riskLevel,
                beepLevel = FeedbackLevel.NONE,
                vibrationLevel = FeedbackLevel.NONE,
                voiceLevel = FeedbackLevel.NONE,
                message = null,
                shouldNotify = false
            )
        }
    }

    private fun format(value: Float): String {
        return String.format(java.util.Locale.US, "%.3f", value)
    }

    private const val TAG = "WarningFeedback"
}
