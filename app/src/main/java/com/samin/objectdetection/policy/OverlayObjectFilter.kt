package com.samin.objectdetection.policy

object OverlayObjectFilter {

    val currentModelLabels = ObjectTuningPolicyRegistry.currentModelLabels

    // Policy templates only; excluded from allowedLabels until the model contract is extended.
    val futureCustomLabels = ObjectTuningPolicyRegistry.futureCustomLabels

    val allowedLabels = ObjectTuningPolicyRegistry.overlayLabels

    fun isAllowed(label: String): Boolean {
        return ObjectTuningPolicyRegistry.shouldShowOverlay(label)
    }

    fun normalize(label: String): String {
        return ObjectTuningPolicyRegistry.normalize(label)
    }
}
