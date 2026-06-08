package com.samin.objectdetection.camera

data class DetectionConfig(
    // Used by RoiCalculator/CameraFrameAnalyzer. MainActivity currently uses a center square crop
    // to preserve the YOLO input aspect ratio and does not apply these ratios.
    val leftCropRatio: Float = 0.05f,
    val rightCropRatio: Float = 0.05f,
    val topCropRatio: Float = 0.10f,
    val detectIntervalMs: Long = 500L,
    val inputSize: Int = 640,
    val confidenceThreshold: Float = 0.60f,
    val minBoxAreaRatio: Float = 0.015f,
    val minBoxWidthRatio: Float = 0.025f,
    val minBoxHeightRatio: Float = 0.025f,
    val ignoreTopRatioForGuide: Float = 0.25f,
    val maxGuideObjectCount: Int = 2,
    val saveDebugImage: Boolean = false,
    val enableDetectorDebugImage: Boolean = false
)
