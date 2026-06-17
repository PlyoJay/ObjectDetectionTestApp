package com.samin.objectdetection.warning

import com.samin.objectdetection.model.DetectedObject
import com.samin.objectdetection.motion.UserObjectRelation

object WarningMessageBuilder {

    fun build(detectedObject: DetectedObject): String {
        val label = detectedObject.label.trim().lowercase()
        val koreanLabel = toKoreanLabel(label)

        return when (detectedObject.userObjectRelation) {
            UserObjectRelation.USER_APPROACHING_OBJECT -> "전방 $koreanLabel 가까워짐"
            UserObjectRelation.USER_LEAVING_OBJECT -> "전방 $koreanLabel 주의"
            UserObjectRelation.OBJECT_APPROACHING_USER -> "전방 $koreanLabel 접근 중"
            UserObjectRelation.OBJECT_LEAVING_USER,
            UserObjectRelation.STABLE_OR_DISTANT,
            UserObjectRelation.UNKNOWN -> buildDefaultMessage(label)
        }
    }

    private fun buildDefaultMessage(label: String): String {
        return when (label) {
            "person" -> "전방에 사람이 있습니다."
            "car" -> "전방에 차량이 있습니다."
            "bus" -> "전방에 버스가 있습니다."
            "truck" -> "전방에 트럭이 있습니다."
            "motorcycle" -> "전방에 오토바이가 있습니다."
            "bicycle" -> "전방에 자전거가 있습니다."
            "traffic light" -> "전방에 신호등이 있습니다."
            "stop sign" -> "전방에 정지 표지판이 있습니다."
            "bench" -> "전방에 벤치가 있습니다."
            "fire hydrant",
            "parking meter" -> "전방에 장애물이 있습니다."
            else -> "전방에 객체가 감지되었습니다."
        }
    }

    private fun toKoreanLabel(label: String): String {
        return when (label) {
            "person" -> "사람"
            "car" -> "차량"
            "bus" -> "버스"
            "truck" -> "트럭"
            "motorcycle" -> "오토바이"
            "bicycle" -> "자전거"
            "traffic light" -> "신호등"
            "stop sign" -> "정지 표지판"
            "bench" -> "벤치"
            "fire hydrant",
            "parking meter" -> "장애물"
            else -> "객체"
        }
    }
}

class WarningThrottle(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    private var lastMessage: String? = null
    private var lastWarnTimeMs: Long = 0L

    fun canShow(
        message: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val canShow = lastMessage != message || nowMs - lastWarnTimeMs >= cooldownMs

        if (canShow) {
            lastMessage = message
            lastWarnTimeMs = nowMs
        }

        return canShow
    }

    companion object {
        private const val DEFAULT_COOLDOWN_MS = 2_500L
    }
}
