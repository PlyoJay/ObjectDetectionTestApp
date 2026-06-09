package com.samin.objectdetection.warning

import com.samin.objectdetection.model.DetectedObject
import com.samin.objectdetection.model.DetectionPriority
import com.samin.objectdetection.motion.ApproachSpeedLevel
import com.samin.objectdetection.policy.WarningPriority

class WarningDecisionMaker(
    private val riskEvaluator: RiskEvaluator = RiskEvaluator(),
    private val feedbackIntensityMapper: FeedbackIntensityMapper = FeedbackIntensityMapper()
) {

    fun shouldWarn(detectedObject: DetectedObject): Boolean {
        if (detectedObject.confidence < MIN_WARNING_CONFIDENCE) return false
        if (detectedObject.boundingBox.width() * detectedObject.boundingBox.height() < MIN_BBOX_AREA) return false
        if (detectedObject.priority == DetectionPriority.IGNORE) return false

        return detectedObject.priority == DetectionPriority.HIGH ||
            detectedObject.priority == DetectionPriority.MEDIUM
    }

    fun decide(obstacles: List<ForwardObstacle>): WarningDecision {
        val obstacle = obstacles
            .sortedWith(
                compareByDescending<ForwardObstacle> { riskEvaluator.evaluate(it) }
                    .thenBy { priorityRank(it.priority) }
                    .thenBy { proximityRank(it.proximityLevel) }
                    .thenByDescending { it.score }
            )
            .firstOrNull()

        if (obstacle == null) {
            val intensity = feedbackIntensityMapper.map(RiskLevel.NONE)
            return WarningDecision(
                obstacle = null,
                message = "감지된 위험 객체 없음",
                riskLevel = RiskLevel.NONE,
                beepLevel = intensity.beepLevel,
                voiceLevel = intensity.voiceLevel,
                vibrationLevel = intensity.vibrationLevel
            )
        }

        val riskLevel = riskEvaluator.evaluate(obstacle)
        val intensity = feedbackIntensityMapper.map(riskLevel)

        return WarningDecision(
            obstacle = obstacle,
            message = buildMessage(obstacle),
            riskLevel = riskLevel,
            beepLevel = intensity.beepLevel,
            voiceLevel = intensity.voiceLevel,
            vibrationLevel = intensity.vibrationLevel
        )
    }

    private fun buildMessage(obstacle: ForwardObstacle): String {
        val label = toKoreanLabel(obstacle.detection.label)
        val subject = "$label${subjectParticle(label)}"
        val approachingFast =
            obstacle.detection.approachSpeedLevel == ApproachSpeedLevel.FAST
        val suffix = if (approachingFast) " 빠르게 접근 중입니다" else " 있습니다"
        return when (obstacle.proximityLevel) {
            ProximityLevel.VERY_NEAR -> "전방 가까이에 $subject$suffix"
            ProximityLevel.NEAR -> "전방에 $subject$suffix"
            ProximityLevel.MID,
            ProximityLevel.FAR -> {
                if (approachingFast) {
                    "전방에 $label 빠르게 접근 중입니다"
                } else {
                    "전방에 $label 감지"
                }
            }
        }
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

    private fun toKoreanLabel(label: String): String {
        return when (label) {
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
            else -> label
        }
    }

    private fun subjectParticle(label: String): String {
        if (label.isEmpty()) return "가"
        val last = label.last()
        if (last !in '가'..'힣') return "가"
        return if ((last.code - 0xAC00) % 28 == 0) "가" else "이"
    }

    companion object {
        const val MIN_BBOX_AREA = 1_000f
        private const val MIN_WARNING_CONFIDENCE = 0.5f
    }
}
