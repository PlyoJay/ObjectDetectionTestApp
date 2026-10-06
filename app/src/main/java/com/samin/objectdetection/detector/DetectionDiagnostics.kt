package com.samin.objectdetection.detector

data class ModelIdentity(
    val assetName: String,
    val assetSizeBytes: Long,
    val sha256: String,
    val inputShape: String,
    val inputType: String,
    val outputShape: String,
    val outputType: String,
    val classCount: Int,
    val coordinateScale: String = "normalized_xywh"
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
    /** Resize plus RGB float-buffer creation. */
    val preprocessTimeMs: Long,
    val resizeTimeMs: Long,
    val inputBufferTimeMs: Long,
    /** Interpreter.run only. */
    val inferenceTimeMs: Long,
    val outputCopyTimeMs: Long,
    val candidateScanTimeMs: Long,
    val nmsTimeMs: Long,
    val postprocessTimeMs: Long,
    val detectorTotalTimeMs: Long,
    val rawCoordinateMin: Float? = null,
    val rawCoordinateMax: Float? = null
)

/** Actual execution order: confidence, valid box, detector size, candidate limit, NMS,
 * geometry, pipeline size, overlay class, temporal, final YOLO overlay. ML Kit is separate. */
data class DetectionStageCounts(
    val rawCandidateCount: Int?,
    val confidencePassedCount: Int?,
    val invalidBoxCount: Int?,
    val detectorSizeRejectedCount: Int?,
    val nmsInputCount: Int?,
    val nmsPassedCount: Int,
    val geometryPassedCount: Int,
    val sizeFilterPassedCount: Int,
    val overlayClassPassedCount: Int,
    val temporalPassedCount: Int,
    val finalDetectionCount: Int
)
