package com.samin.objectdetection.camera

import com.samin.objectdetection.ui.OverlayDebugMode

enum class SizeFilterMode {
    /** Keep every geometrically valid bbox, regardless of its size. */
    DISABLED,

    /** Reject only extremely small or nearly full-frame bboxes. */
    RELAXED,

    /** Apply the existing detector and class-specific production thresholds. */
    NORMAL
}

data class DetectionConfig(
    val detectIntervalMs: Long = 500L,
    val inputSize: Int = 640,
    // Keep the full camera frame by default. Enable only for comparison with the legacy center-square ROI.
    val useCenterSquareCrop: Boolean = false,
    // Base YOLO candidate threshold. Object-specific warning thresholds are applied later by ObjectTuningPolicyRegistry.
    val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    val nmsThreshold: Float = DEFAULT_NMS_THRESHOLD,
    // Field-test default: expose the retrained model's detections without bbox size suppression.
    val sizeFilterMode: SizeFilterMode = SizeFilterMode.DISABLED,
    val minBoxAreaRatio: Float = 0.015f,
    val minBoxWidthRatio: Float = 0.025f,
    val minBoxHeightRatio: Float = 0.025f,
    val ignoreTopRatioForGuide: Float = 0.25f,
    val maxGuideObjectCount: Int = 2,
    val overlayDebugMode: OverlayDebugMode = OverlayDebugMode.SIMPLE,
    val saveDebugImage: Boolean = false,
    val enableDetectorDebugImage: Boolean = false,
    // This is an evaluation app: keep stage-by-stage diagnostics on for field-test builds.
    val enableDetectorDiagnostics: Boolean = true
) {
    companion object {
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.20f
        const val DEFAULT_NMS_THRESHOLD = 0.45f
    }
}
