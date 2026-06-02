package com.samin.objectdetection.policy

data class DetectionPolicy(
    val label: String,
    val category: ObjectCategory,
    val priority: WarningPriority,
    val minConfidence: Float,
    val shouldVoiceGuide: Boolean
)