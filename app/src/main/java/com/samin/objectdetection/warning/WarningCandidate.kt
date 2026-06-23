package com.samin.objectdetection.warning

import com.samin.objectdetection.detector.DetectionResult

data class WarningCandidate(
    val label: String,
    val confidence: Float,
    val priority: ObjectPriority,
    val proximityLevel: ProximityLevel,
    val riskLevel: RiskLevel,
    val horizontalPosition: HorizontalPosition,
    val feedback: WarningFeedback,
    val warningKey: String
) {
    companion object {
        fun fromDetection(
            detection: DetectionResult,
            warningKey: String
        ): WarningCandidate {
            return WarningCandidate(
                label = detection.label,
                confidence = detection.confidence,
                priority = detection.objectPriority,
                proximityLevel = detection.proximityLevel,
                riskLevel = detection.riskLevel,
                horizontalPosition = detection.horizontalPosition,
                feedback = detection.warningFeedback,
                warningKey = warningKey
            )
        }
    }
}
