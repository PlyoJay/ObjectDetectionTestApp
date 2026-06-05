package com.samin.objectdetection.warning

import com.samin.objectdetection.model.DetectedObject

object WarningMessageBuilder {

    fun build(detectedObject: DetectedObject): String {
        return when (detectedObject.label.trim().lowercase()) {
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
}
