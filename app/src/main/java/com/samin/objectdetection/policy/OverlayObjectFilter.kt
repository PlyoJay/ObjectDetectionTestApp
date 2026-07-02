package com.samin.objectdetection.policy

object OverlayObjectFilter {

    val cocoAllowedLabels = ObjectTuningPolicyRegistry.cocoTuningLabels
        .filter { ObjectTuningPolicyRegistry.shouldShowOverlay(it) }
        .toSet()

    // The current yolo11n_float32.tflite model uses COCO labels, so these custom labels may not be emitted
    // until a future custom model and matching labels.txt are shipped.
    val futureCustomLabels = ObjectTuningPolicyRegistry.futureCustomLabels

    val allowedLabels = ObjectTuningPolicyRegistry.overlayLabels

    fun isAllowed(label: String): Boolean {
        return ObjectTuningPolicyRegistry.shouldShowOverlay(label)
    }

    fun normalize(label: String): String {
        return ObjectTuningPolicyRegistry.normalize(label)
    }
}
