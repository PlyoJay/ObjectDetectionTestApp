package com.samin.objectdetection.camera

import com.samin.objectdetection.ui.OverlayDebugMode

data class DetectionConfig(
    // Used by RoiCalculator/CameraFrameAnalyzer. MainActivity currently uses a center square crop
    // to preserve the YOLO input aspect ratio and does not apply these ratios.
    val leftCropRatio: Float = 0.05f,
    val rightCropRatio: Float = 0.05f,
    val topCropRatio: Float = 0.10f,
    val detectIntervalMs: Long = 500L,
    val inputSize: Int = 640,
    // Model-level YOLO confidence threshold. Per-object warning thresholds live in YoloDefaultPolicyRegistry.
    val confidenceThreshold: Float = 0.35f,
    val minBoxAreaRatio: Float = 0.015f,
    val minBoxWidthRatio: Float = 0.025f,
    val minBoxHeightRatio: Float = 0.025f,
    val ignoreTopRatioForGuide: Float = 0.25f,
    val maxGuideObjectCount: Int = 2,
    val overlayDebugMode: OverlayDebugMode = OverlayDebugMode.SIMPLE,
    val saveDebugImage: Boolean = false,
    val enableDetectorDebugImage: Boolean = false
)
