package com.samin.objectdetection.detector

data class ModelIdentity(
    val assetName: String,
    val assetSizeBytes: Long,
    val sha256: String,
    val inputShape: String,
    val inputType: String,
    val outputShape: String,
    val outputType: String
) {
    val sha256Prefix: String get() = sha256.take(12)
}

data class DetectorFrameDiagnostics(
    val rawCandidateCount: Int,
    val rawTopConfidences: List<Float>,
    val confidencePassedCount: Int,
    val invalidBoxCount: Int,
    val detectorAreaRejectedCount: Int,
    val nmsInputCount: Int,
    val nmsOutputCount: Int,
    val inferenceTimeMs: Long
)
