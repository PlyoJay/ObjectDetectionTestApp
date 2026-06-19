package com.samin.objectdetection.policy

object YoloDefaultPolicyRegistry {

    // COCO labels.txt stays complete for YOLO recognition.
    // A label must pass both OverlayObjectFilter and this registry to become a warningDetection.
    private val policies = mapOf(
        // Explicit guidance target: pedestrian safety signal.
        "traffic light" to DetectionPolicy(
            label = "traffic light",
            category = ObjectCategory.SAFETY,
            priority = WarningPriority.CRITICAL,
            minConfidence = 0.55f,
            shouldVoiceGuide = true
        ),

        // Explicit guidance targets: moving or parked vehicles in the walking path.
        "car" to DetectionPolicy(
            label = "car",
            category = ObjectCategory.VEHICLE,
            priority = WarningPriority.HIGH,
            minConfidence = 0.5f,
            shouldVoiceGuide = true
        ),

        "bicycle" to DetectionPolicy(
            label = "bicycle",
            category = ObjectCategory.VEHICLE,
            priority = WarningPriority.HIGH,
            minConfidence = 0.5f,
            shouldVoiceGuide = true
        ),

        "motorcycle" to DetectionPolicy(
            label = "motorcycle",
            category = ObjectCategory.VEHICLE,
            priority = WarningPriority.HIGH,
            minConfidence = 0.5f,
            shouldVoiceGuide = true
        ),

        "bus" to DetectionPolicy(
            label = "bus",
            category = ObjectCategory.VEHICLE,
            priority = WarningPriority.HIGH,
            minConfidence = 0.5f,
            shouldVoiceGuide = true
        ),

        "truck" to DetectionPolicy(
            label = "truck",
            category = ObjectCategory.VEHICLE,
            priority = WarningPriority.HIGH,
            minConfidence = 0.5f,
            shouldVoiceGuide = true
        ),

        "stop sign" to DetectionPolicy(
            label = "stop sign",
            category = ObjectCategory.SAFETY,
            priority = WarningPriority.HIGH,
            minConfidence = 0.45f,
            shouldVoiceGuide = true
        ),

        // Explicit guidance targets: fixed outdoor obstacles that can block a walking path.
        "fire hydrant" to DetectionPolicy(
            label = "fire hydrant",
            category = ObjectCategory.OBSTACLE,
            priority = WarningPriority.MEDIUM,
            minConfidence = 0.45f,
            shouldVoiceGuide = true
        ),

        "bench" to DetectionPolicy(
            label = "bench",
            category = ObjectCategory.OBSTACLE,
            priority = WarningPriority.MEDIUM,
            minConfidence = 0.45f,
            shouldVoiceGuide = true
        ),

        // Ambiguous indoor/small object: keep as low-priority debug/selection data, no voice guide by default.
        "chair" to DetectionPolicy(
            label = "chair",
            category = ObjectCategory.OBSTACLE,
            priority = WarningPriority.LOW,
            minConfidence = 0.45f,
            shouldVoiceGuide = false
        ),

        // Human is useful for visual/debug risk context, but voice guidance is disabled to reduce noisy alerts.
        "person" to DetectionPolicy(
            label = "person",
            category = ObjectCategory.HUMAN,
            priority = WarningPriority.MEDIUM,
            minConfidence = 0.5f,
            shouldVoiceGuide = false
        )
    )

    // Ambiguous COCO classes intentionally not registered as warning targets for now:
    // clock, cell phone, laptop, cup, bottle, book, remote, tv, keyboard, mouse.
    // They are ignored by both overlay and warning flows unless the app-level filters are updated.

    fun get(label: String): DetectionPolicy? {
        return policies[normalize(label)]
    }

    fun isSupported(label: String): Boolean {
        return policies.containsKey(normalize(label))
    }

    fun getAll(): List<DetectionPolicy> {
        return policies.values.toList()
    }

    private fun normalize(label: String): String {
        return label.trim().lowercase()
    }
}
