package com.samin.objectdetection.warning

data class FeedbackIntensity(
    val beepLevel: FeedbackLevel,
    val voiceLevel: FeedbackLevel,
    val vibrationLevel: FeedbackLevel
)
