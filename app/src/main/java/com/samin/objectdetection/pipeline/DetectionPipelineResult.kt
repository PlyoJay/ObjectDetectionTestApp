package com.samin.objectdetection.pipeline

import android.graphics.Rect
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.DetectionStageCounts
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
    val timing: PipelineTiming,
    val topOverlayObject: DetectionResult?,
    val userLocationSnapshot: UserLocationSnapshot,
    val debugFrameId: Long? = null,
    val stageCounts: DetectionStageCounts? = null
)

data class PipelineTiming(
    val pipelineStartedAtMs: Long,
    val inferenceStartedAtMs: Long,
    val cropTimeMs: Long,
    val detectorTimeMs: Long,
    val postprocessTimeMs: Long,
    val pipelineTimeMs: Long
)
