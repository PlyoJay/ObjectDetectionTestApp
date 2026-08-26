package com.samin.objectdetection.camera

import com.samin.objectdetection.ui.OverlayDebugMode

data class DetectionConfig(
    val detectIntervalMs: Long = 500L,
    val inputSize: Int = 640,
    // Base YOLO candidate threshold. Object-specific warning thresholds are applied later by ObjectTuningPolicyRegistry.
    val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    val nmsThreshold: Float = DEFAULT_NMS_THRESHOLD,
    val minBoxAreaRatio: Float = 0.015f,
    val minBoxWidthRatio: Float = 0.025f,
    val minBoxHeightRatio: Float = 0.025f,
    val ignoreTopRatioForGuide: Float = 0.25f,
    val maxGuideObjectCount: Int = 2,
    val overlayDebugMode: OverlayDebugMode = OverlayDebugMode.SIMPLE,
    val saveDebugImage: Boolean = false,
    val enableDetectorDebugImage: Boolean = false
) {
    companion object {
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.20f
        const val DEFAULT_NMS_THRESHOLD = 0.45f
    }
}
