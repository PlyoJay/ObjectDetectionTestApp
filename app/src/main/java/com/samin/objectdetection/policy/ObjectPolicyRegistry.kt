package com.samin.objectdetection.policy

object ObjectPolicyRegistry {

    private val policies = mapOf(
        // Safety
        "crosswalk" to DetectionPolicy(
            label = "crosswalk",
            category = ObjectCategory.SAFETY,
            priority = WarningPriority.CRITICAL,
            minConfidence = 0.6f,
            shouldVoiceGuide = true
        ),
        "traffic_light" to DetectionPolicy(
            label = "traffic_light",
            category = ObjectCategory.SAFETY,
            priority = WarningPriority.CRITICAL,
            minConfidence = 0.6f,
            shouldVoiceGuide = true
        ),
        "tactile_paving" to DetectionPolicy(
            label = "tactile_paving",
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

        // Obstacle
        "bollard" to DetectionPolicy(
            label = "bollard",
            category = ObjectCategory.OBSTACLE,
            priority = WarningPriority.MEDIUM,
            minConfidence = 0.45f,
            shouldVoiceGuide = true
        ),
        "pillar" to DetectionPolicy(
            label = "pillar",
            category = ObjectCategory.OBSTACLE,
            priority = WarningPriority.MEDIUM,
            minConfidence = 0.45f,
            shouldVoiceGuide = true
        ),
        "wall" to DetectionPolicy(
            label = "wall",
            category = ObjectCategory.OBSTACLE,
            priority = WarningPriority.MEDIUM,
            minConfidence = 0.45f,
            shouldVoiceGuide = true
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
}