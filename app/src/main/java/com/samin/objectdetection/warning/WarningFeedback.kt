package com.samin.objectdetection.warning

enum class RiskLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

enum class FeedbackLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH
}

data class WarningFeedback(
    val riskLevel: RiskLevel,
    val beepLevel: FeedbackLevel,
    val vibrationLevel: FeedbackLevel,
    val voiceLevel: FeedbackLevel,
    val message: String?,
    val shouldNotify: Boolean
) {
    companion object {
        val NONE = WarningFeedback(
            riskLevel = RiskLevel.NONE,
            beepLevel = FeedbackLevel.NONE,
            vibrationLevel = FeedbackLevel.NONE,
            voiceLevel = FeedbackLevel.NONE,
            message = null,
            shouldNotify = false
        )
    }
}

data class FeedbackIntensity(
    val beepLevel: FeedbackLevel,
    val voiceLevel: FeedbackLevel,
    val vibrationLevel: FeedbackLevel
)

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

class FeedbackIntensityMapper {

    fun map(riskLevel: RiskLevel): FeedbackIntensity {
        return when (riskLevel) {
            RiskLevel.CRITICAL -> FeedbackIntensity(
                beepLevel = FeedbackLevel.HIGH,
                voiceLevel = FeedbackLevel.HIGH,
                vibrationLevel = FeedbackLevel.HIGH
            )
            RiskLevel.HIGH -> FeedbackIntensity(
                beepLevel = FeedbackLevel.MEDIUM,
                voiceLevel = FeedbackLevel.MEDIUM,
                vibrationLevel = FeedbackLevel.MEDIUM
            )
            RiskLevel.MEDIUM -> FeedbackIntensity(
                beepLevel = FeedbackLevel.LOW,
                voiceLevel = FeedbackLevel.LOW,
                vibrationLevel = FeedbackLevel.LOW
            )
            RiskLevel.LOW -> FeedbackIntensity(
                beepLevel = FeedbackLevel.NONE,
                voiceLevel = FeedbackLevel.NONE,
                vibrationLevel = FeedbackLevel.NONE
            )
            RiskLevel.NONE -> FeedbackIntensity(
                beepLevel = FeedbackLevel.NONE,
                voiceLevel = FeedbackLevel.NONE,
                vibrationLevel = FeedbackLevel.NONE
            )
        }
    }
}
