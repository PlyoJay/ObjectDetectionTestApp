package com.samin.objectdetection.warning

data class WarningDecision(
    val obstacle: ForwardObstacle?,
    val message: String,
    val shouldVoiceGuide: Boolean,
    val shouldVibrate: Boolean
)
