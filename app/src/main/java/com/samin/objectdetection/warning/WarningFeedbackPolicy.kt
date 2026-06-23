package com.samin.objectdetection.warning

import android.util.Log
import com.samin.objectdetection.detector.DetectionResult

object WarningFeedbackPolicy {

    fun evaluate(
        detection: DetectionResult
    ): WarningFeedback {
        val feedback = mapRiskToFeedback(
            label = detection.label,
            category = detection.riskObjectCategory,
            priority = detection.objectPriority,
            riskLevel = detection.riskLevel
        )

        Log.d(
            TAG,
            "label=${detection.label}, conf=${format(detection.confidence)}, " +
                "priority=${detection.objectPriority}, " +
                "proximityLevel=${detection.proximityLevel}, riskLevel=${detection.riskLevel}, " +
                "beepLevel=${feedback.beepLevel}, vibrationLevel=${feedback.vibrationLevel}, " +
                "voiceLevel=${feedback.voiceLevel}, message=${feedback.message}, " +
                "cooldownPassed=pending, shouldNotify=${feedback.shouldNotify}"
        )

        return feedback
    }

    fun evaluateWithCooldown(
        detection: DetectionResult,
        cooldownManager: WarningCooldownManager,
        nowMs: Long = System.currentTimeMillis()
    ): WarningFeedback {
        val baseFeedback = mapRiskToFeedback(
            label = detection.label,
            category = detection.riskObjectCategory,
            priority = detection.objectPriority,
            riskLevel = detection.riskLevel
        )
        val cooldownPassed = if (baseFeedback.shouldNotify) {
            cooldownManager.canNotify(
                label = detection.label,
                priority = detection.objectPriority,
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
                "priority=${detection.objectPriority}, " +
                "proximityLevel=${detection.proximityLevel}, riskLevel=${detection.riskLevel}, " +
                "beepLevel=${feedback.beepLevel}, vibrationLevel=${feedback.vibrationLevel}, " +
                "voiceLevel=${feedback.voiceLevel}, message=${feedback.message}, " +
                "cooldownPassed=$cooldownPassed, shouldNotify=${feedback.shouldNotify}"
        )

        return feedback
    }

    fun applyCooldown(
        candidate: WarningCandidate,
        cooldownManager: WarningCooldownManager,
        nowMs: Long = System.currentTimeMillis()
    ): WarningCandidate {
        val cooldownPassed = cooldownManager.canNotify(
            label = candidate.label,
            priority = candidate.priority,
            proximityLevel = candidate.proximityLevel,
            horizontalPosition = candidate.horizontalPosition,
            nowMs = nowMs
        )
        return candidate.copy(
            feedback = candidate.feedback.copy(
                shouldNotify = candidate.feedback.shouldNotify && cooldownPassed
            )
        )
    }

    fun buildWarningMessage(
        label: String,
        category: VisionObjectCategory,
        riskLevel: RiskLevel,
        priority: ObjectPriority = ObjectPriority.LOW
    ): String? {
        if (riskLevel == RiskLevel.LOW || riskLevel == RiskLevel.NONE) return null
        return when (priority) {
            ObjectPriority.HIGH -> buildHighPriorityMessage(label, riskLevel)
            ObjectPriority.LOW -> buildLowPriorityMessage(label, category, riskLevel)
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
            "stairs" -> "계단"
            "curb" -> "단차"
            "low_obstacle" -> "낮은 장애물"
            else -> "객체"
        }
    }

    private fun buildHighPriorityMessage(label: String, riskLevel: RiskLevel): String? {
        val normalized = label.trim().lowercase()
        return when (riskLevel) {
            RiskLevel.CRITICAL -> when (normalized) {
                "bicycle" -> "정지! 자전거"
                "motorcycle" -> "정지! 오토바이"
                "car",
                "bus",
                "truck" -> "정지! 차량"
                "stairs" -> "정지! 계단"
                "curb" -> "정지! 단차"
                "low_obstacle" -> "정지! 장애물"
                else -> "정지!"
            }
            RiskLevel.HIGH,
            RiskLevel.MEDIUM -> when (normalized) {
                "bicycle" -> "자전거 주의"
                "motorcycle" -> "오토바이 주의"
                "car",
                "bus",
                "truck" -> "차량 주의"
                "stairs" -> "계단 주의"
                "curb" -> "단차 주의"
                "low_obstacle" -> "낮은 장애물 주의"
                else -> "전방 주의"
            }
            RiskLevel.LOW,
            RiskLevel.NONE -> null
        }
    }

    private fun buildLowPriorityMessage(
        label: String,
        category: VisionObjectCategory,
        riskLevel: RiskLevel
    ): String? {
        val normalized = label.trim().lowercase()
        return when (riskLevel) {
            RiskLevel.CRITICAL -> when {
                normalized == "person" -> "정지! 사람"
                isLowPriorityObstacle(normalized, category) -> "정지! 장애물"
                else -> "정지!"
            }
            RiskLevel.HIGH,
            RiskLevel.MEDIUM -> when {
                normalized == "person" -> "전방 사람"
                isLowPriorityObstacle(normalized, category) -> "전방 장애물"
                else -> "전방 주의"
            }
            RiskLevel.LOW,
            RiskLevel.NONE -> null
        }
    }

    private fun isLowPriorityObstacle(
        label: String,
        category: VisionObjectCategory
    ): Boolean {
        return label == "bollard" ||
            label == "bench" ||
            label == "fire hydrant" ||
            label == "chair" ||
            category == RiskObjectCategory.STATIC_OBSTACLE ||
            category == RiskObjectCategory.TEMPORARY_OBSTACLE
    }

    private fun mapRiskToFeedback(
        label: String,
        category: VisionObjectCategory,
        priority: ObjectPriority,
        riskLevel: RiskLevel
    ): WarningFeedback {
        val message = buildWarningMessage(label, category, riskLevel, priority)
        if (priority == ObjectPriority.HIGH) {
            return mapHighPriorityFeedback(riskLevel, message)
        }

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

    private fun mapHighPriorityFeedback(
        riskLevel: RiskLevel,
        message: String?
    ): WarningFeedback {
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
                beepLevel = FeedbackLevel.HIGH,
                vibrationLevel = FeedbackLevel.HIGH,
                voiceLevel = FeedbackLevel.MEDIUM,
                message = message,
                shouldNotify = true
            )
            RiskLevel.MEDIUM -> WarningFeedback(
                riskLevel = riskLevel,
                beepLevel = FeedbackLevel.MEDIUM,
                vibrationLevel = FeedbackLevel.MEDIUM,
                voiceLevel = FeedbackLevel.MEDIUM,
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
