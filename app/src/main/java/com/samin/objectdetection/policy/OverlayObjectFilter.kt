package com.samin.objectdetection.policy

object OverlayObjectFilter {

    val cocoAllowedLabels = setOf(
        "person",
        "bicycle",
        "car",
        "motorcycle",
        "bus",
        "truck",
        "traffic light",
        "stop sign",
        "bench",
        "fire hydrant"
    )

    // The current yolo11n_float32.tflite model uses COCO labels, so these custom labels may not be emitted
    // until a future custom model and matching labels.txt are shipped.
    val futureCustomLabels = setOf(
        "bollard",
        "stairs",
        "curb",
        "low_obstacle"
    )

    val allowedLabels = cocoAllowedLabels + futureCustomLabels

    fun isAllowed(label: String): Boolean {
        return normalize(label) in allowedLabels
    }

    fun normalize(label: String): String {
        return label.trim().lowercase()
    }
}
