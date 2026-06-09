package com.samin.objectdetection.warning

data class WarningDecision(
    val obstacle: ForwardObstacle?,
    val message: String,
    val riskLevel: RiskLevel,
    val beepLevel: FeedbackLevel,
    val voiceLevel: FeedbackLevel,
    val vibrationLevel: FeedbackLevel
) {
    val shouldVoiceGuide: Boolean
        get() = voiceLevel != FeedbackLevel.NONE

    val shouldVibrate: Boolean
        get() = vibrationLevel != FeedbackLevel.NONE
}
