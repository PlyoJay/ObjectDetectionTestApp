package com.samin.objectdetection.warning

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
