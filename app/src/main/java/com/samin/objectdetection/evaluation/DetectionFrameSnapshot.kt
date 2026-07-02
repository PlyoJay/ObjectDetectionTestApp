package com.samin.objectdetection.evaluation

import android.graphics.Bitmap
import android.graphics.Rect
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.location.UserLocationSnapshot
import com.samin.objectdetection.warning.RiskLevel
import com.samin.objectdetection.warning.WarningCandidate

data class DetectionFrameSnapshot(
    val bitmap: Bitmap,
    val detections: List<DetectionResult>,
    val evaluationDetections: List<DetectionResult> = detections,
    val frameTimestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val roiApplied: Boolean,
    val roi: Rect?,
    val rawDetectionCount: Int = detections.size,
    val visibleDetectionCount: Int = detections.size,
    val warningDetectionCount: Int = 0,
    val topDetection: DetectionResult? = detections.maxByOrNull { it.confidence },
    val selectedWarningCandidate: WarningCandidate? = null,
    val inferenceTimeMs: Long = 0L,
    val fps: Int = 0,
    val userLocationSnapshot: UserLocationSnapshot? = null
)

data class EvaluationFrameSummary(
    val timestampMs: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val detectionCount: Int,
    val visibleDetectionCount: Int,
    val warningDetectionCount: Int,
    val topLabel: String?,
    val topConfidence: Float?,
    val selectedWarningLabel: String?,
    val selectedRiskLevel: RiskLevel?,
    val selectedWarningMessage: String?,
    val inferenceTimeMs: Long,
    val fps: Int,
    val userMotionState: String?,
    val gpsSpeedMps: Float?,
    val gpsAccuracyMeters: Float?
)
