package com.samin.objectdetection.warning

import com.samin.objectdetection.model.DetectionCategory
import com.samin.objectdetection.model.DetectionPriority

object DetectionCategoryMapper {

    fun mapCategory(label: String): DetectionCategory {
        return when (normalize(label)) {
            "traffic light",
            "stop sign" -> DetectionCategory.SAFETY
            "car",
            "bus",
            "truck",
            "motorcycle",
            "bicycle" -> DetectionCategory.VEHICLE
            "chair",
            "bench",
            "fire hydrant" -> DetectionCategory.OBSTACLE
            "person" -> DetectionCategory.HUMAN
            else -> DetectionCategory.ETC
        }
    }

    fun mapPriority(label: String): DetectionPriority {
        return when (normalize(label)) {
            "traffic light",
            "stop sign",
            "car",
            "bus",
            "truck",
            "motorcycle",
            "bicycle" -> DetectionPriority.HIGH
            "person",
            "bench",
            "fire hydrant",
            "parking meter" -> DetectionPriority.MEDIUM
            else -> DetectionPriority.LOW
        }
    }

    fun map(label: String): Pair<DetectionCategory, DetectionPriority> {
        return mapCategory(label) to mapPriority(label)
    }

    private fun normalize(label: String): String {
        return label.trim().lowercase()
    }
}
