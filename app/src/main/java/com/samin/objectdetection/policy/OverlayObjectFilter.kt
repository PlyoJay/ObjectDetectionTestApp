package com.samin.objectdetection.policy

object OverlayObjectFilter {

    val allowedLabels = setOf(
        "person",
        "bicycle",
        "car",
        "motorcycle",
        "bus",
        "truck",
        "traffic light",
        "stop sign",
        "bench",
        "chair",
        "fire hydrant",
        "bollard",
        "stairs",
        "curb",
        "low_obstacle"
    )

    fun isAllowed(label: String): Boolean {
        return normalize(label) in allowedLabels
    }

    fun normalize(label: String): String {
        return label.trim().lowercase()
    }
}
