package com.samin.objectdetection.policy

object YoloDefaultPolicyRegistry {

    fun get(label: String): DetectionPolicy? {
        if (ObjectTuningPolicyRegistry.normalize(label) !in ObjectTuningPolicyRegistry.currentModelLabels) return null
        val policy = ObjectTuningPolicyRegistry.get(label) ?: return null
        if (!policy.enableWarning) return null
        return policy.toDetectionPolicy()
    }

    fun isSupported(label: String): Boolean {
        return get(label) != null
    }

    fun getAll(): List<DetectionPolicy> {
        return ObjectTuningPolicyRegistry.getAll()
            .filter { it.label in ObjectTuningPolicyRegistry.currentModelLabels && it.enableWarning }
            .map { it.toDetectionPolicy() }
    }

    private fun ObjectTuningPolicy.toDetectionPolicy(): DetectionPolicy {
        return DetectionPolicy(
            label = label,
            category = when (category) {
                com.samin.objectdetection.warning.RiskObjectCategory.TRAFFIC_CONTROL -> ObjectCategory.SAFETY
                com.samin.objectdetection.warning.RiskObjectCategory.VEHICLE_RISK -> ObjectCategory.VEHICLE
                com.samin.objectdetection.warning.RiskObjectCategory.STATIC_OBSTACLE,
                com.samin.objectdetection.warning.RiskObjectCategory.TEMPORARY_OBSTACLE -> ObjectCategory.OBSTACLE
                com.samin.objectdetection.warning.RiskObjectCategory.HUMAN_FLOW -> ObjectCategory.HUMAN
                com.samin.objectdetection.warning.RiskObjectCategory.UNKNOWN -> ObjectCategory.OBSTACLE
            },
            priority = when (priority) {
                com.samin.objectdetection.warning.ObjectPriority.HIGH -> WarningPriority.HIGH
                com.samin.objectdetection.warning.ObjectPriority.LOW -> WarningPriority.LOW
            },
            minConfidence = minConfidence,
            shouldVoiceGuide = enableVoice
        )
    }
}
