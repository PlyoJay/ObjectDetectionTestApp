package com.samin.objectdetection.policy

object YoloDefaultPolicyRegistry {

    private val policies = mapOf(
        // Safety
        "traffic light" to DetectionPolicy(
            label = "traffic light",
            category = ObjectCategory.SAFETY,
            priority = WarningPriority.CRITICAL,
            minConfidence = 0.55f,
            shouldVoiceGuide = true
        ),

        // Vehicle
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

        // Obstacle
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

        "parking meter" to DetectionPolicy(
            label = "parking meter",
            category = ObjectCategory.OBSTACLE,
            priority = WarningPriority.MEDIUM,
            minConfidence = 0.45f,
            shouldVoiceGuide = true
        ),

        "backpack" to DetectionPolicy(
            label = "backpack",
            category = ObjectCategory.OBSTACLE,
            priority = WarningPriority.MEDIUM,
            minConfidence = 0.45f,
            shouldVoiceGuide = true
        ),

        "umbrella" to DetectionPolicy(
            label = "umbrella",
            category = ObjectCategory.OBSTACLE,
            priority = WarningPriority.MEDIUM,
            minConfidence = 0.45f,
            shouldVoiceGuide = true
        ),

        "chair" to DetectionPolicy(
            label = "chair",
            category = ObjectCategory.OBSTACLE,
            priority = WarningPriority.LOW,
            minConfidence = 0.45f,
            shouldVoiceGuide = false
        ),

        // Human
        "person" to DetectionPolicy(
            label = "person",
            category = ObjectCategory.HUMAN,
            priority = WarningPriority.MEDIUM,
            minConfidence = 0.5f,
            shouldVoiceGuide = false
        )
    )

    fun get(label: String): DetectionPolicy? {
        return policies[label]
    }

    fun isSupported(label: String): Boolean {
        return policies.containsKey(label)
    }

    fun getAll(): List<DetectionPolicy> {
        return policies.values.toList()
    }
}
