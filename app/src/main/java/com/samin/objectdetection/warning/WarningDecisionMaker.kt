package com.samin.objectdetection.warning

import com.samin.objectdetection.policy.WarningPriority

class WarningDecisionMaker {

    fun decide(obstacles: List<ForwardObstacle>): WarningDecision {
        val obstacle = obstacles.sortedWith(
            compareBy<ForwardObstacle> { priorityRank(it.priority) }
                .thenBy { proximityRank(it.proximityLevel) }
                .thenByDescending { it.score }
        ).firstOrNull()

        if (obstacle == null) {
            return WarningDecision(
                obstacle = null,
                message = "감지된 위험 객체 없음",
                shouldVoiceGuide = false,
                shouldVibrate = false
            )
        }

        val shouldVoiceGuide =
            (obstacle.priority == WarningPriority.CRITICAL || obstacle.priority == WarningPriority.HIGH) &&
                proximityRank(obstacle.proximityLevel) <= proximityRank(ProximityLevel.NEAR)

        return WarningDecision(
            obstacle = obstacle,
            message = buildMessage(obstacle),
            shouldVoiceGuide = shouldVoiceGuide,
            shouldVibrate = obstacle.proximityLevel == ProximityLevel.VERY_NEAR
        )
    }

    private fun buildMessage(obstacle: ForwardObstacle): String {
        val label = toKoreanLabel(obstacle.detection.label)
        val subject = "$label${subjectParticle(label)}"
        return when (obstacle.proximityLevel) {
            ProximityLevel.VERY_NEAR -> "전방 가까이에 $subject 있습니다"
            ProximityLevel.NEAR -> "전방에 $subject 있습니다"
            ProximityLevel.MID,
            ProximityLevel.FAR -> "전방에 $label 감지"
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
}
