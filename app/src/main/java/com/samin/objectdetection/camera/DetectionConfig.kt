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
    // CameraX drops queued frames for us. A fixed 500 ms throttle made the overlay update at most 2 FPS.
    // Keep this at zero for latency-first operation; raise it only for an explicit A/B test.
    val detectIntervalMs: Long = 0L,
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
    // Legacy alias for rate-limited inference input PNG saving.
    val enableDetectorDebugImage: Boolean = false,
    // Per-candidate logging is expensive. Perf summaries remain available when this is false.
    val enableDetectorDiagnostics: Boolean = false,
    // Independent of the production confidence threshold and legacy Logcat diagnostics.
    val debugDetectionLogging: Boolean = false,
    val diagnosticRawConfidenceThreshold: Float = 0.01f,
    val diagnosticMaxRawCandidates: Int = 200,
    val debugSaveInferenceInput: Boolean = false,
    val debugSaveIntervalMs: Long = 2000L,
    val debugSaveOnDetection: Boolean = false,
    // Allows an on-device CPU thread-count sweep without changing detector code.
    val interpreterThreadCount: Int = 4,
    // ML Kit is retained, but can be disabled for YOLO-only latency comparison.
    val enableMlKitDetection: Boolean = true,
    val bollardGeometryFilterEnabled: Boolean = true,
    val adaptiveTemporalEnabled: Boolean = false,
    val temporalImmediateConfidence: Float = 0.75f,
    val temporalConfirmationConfidence: Float = 0.50f
) {
    companion object {
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.20f
        const val DEFAULT_NMS_THRESHOLD = 0.45f
    }
}
