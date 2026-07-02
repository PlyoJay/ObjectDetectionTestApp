package com.samin.objectdetection.pipeline

import android.graphics.Rect
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.location.UserLocationSnapshot

data class DetectionPipelineResult(
    val frameWidth: Int,
    val frameHeight: Int,
    val cropRect: Rect,
    val mappedDetections: List<DetectionResult>,
    val visibleDetections: List<DetectionResult>,
    val overlayDetections: List<DetectionResult>,
    val warningDetections: List<DetectionResult>,
    val ignoredLabels: List<String>,
    val inferenceTimeMs: Long,
    val topOverlayObject: DetectionResult?,
    val userLocationSnapshot: UserLocationSnapshot
)
