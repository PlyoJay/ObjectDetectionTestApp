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

enum class YoloResizeMode { STRETCH, LETTERBOX }

data class DetectionConfig(
    // CameraX drops queued frames for us. A fixed 500 ms throttle made the overlay update at most 2 FPS.
    // Keep this at zero for latency-first operation; raise it only for an explicit A/B test.
    val detectIntervalMs: Long = 0L,
    val inputSize: Int = 640,
    // Preserve aspect ratio by default; STRETCH remains available for explicit A/B runs.
    val yoloResizeMode: YoloResizeMode = YoloResizeMode.LETTERBOX,
    // Keep the full camera frame by default. Enable only for comparison with the legacy center-square ROI.
    val useCenterSquareCrop: Boolean = false,
    // Base YOLO candidate threshold. Object-specific warning thresholds are applied later by ObjectTuningPolicyRegistry.
    val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    val nmsThreshold: Float = DEFAULT_NMS_THRESHOLD,
    // Field-test setting: expose the retrained model's detections without bbox size suppression.
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
    val maxCandidates: Int = 100,
    // ML Kit is retained, but can be disabled for YOLO-only latency comparison.
    val enableMlKitDetection: Boolean = true,
    val mlKitDetectionIntervalMs: Long = 1500L,
    val bollardGeometryFilterEnabled: Boolean = true,
    val bollardMinAreaRatio: Float = 0.00001f,
    val bollardMaxWidthToHeightRatio: Float = 4f,
    val adaptiveTemporalEnabled: Boolean = false,
    val temporalImmediateConfidence: Float = 0.75f,
    val temporalConfirmationConfidence: Float = 0.50f,
    val temporalMatchIouThreshold: Float = 0.30f,
    val motionMaxHistorySize: Int = 5,
    val motionMinHistorySize: Int = 3,
    val motionMaxMatchDistanceRatio: Float = 0.18f,
    val motionMinSampleIntervalMs: Long = 500L,
    val motionStaleTrackTimeoutMs: Long = 2_000L,
    val motionMinAbsoluteAreaChange: Float = 0.005f,
    val motionMinRelativeAreaChangeRatio: Float = 0.15f,
    val motionMinAbsoluteHeightChange: Float = 0.03f,
    val motionMinRelativeHeightChangeRatio: Float = 0.10f,
    val warningVeryNearHeightRatio: Float = 0.35f,
    val warningNearHeightRatio: Float = 0.22f,
    val warningMidHeightRatio: Float = 0.12f,
    val warningVeryNearAreaRatio: Float = 0.15f,
    val warningNearAreaRatio: Float = 0.07f,
    val warningMidAreaRatio: Float = 0.02f
) {
    companion object {
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.20f
        const val DEFAULT_NMS_THRESHOLD = 0.45f

        /** YOLO-only field test. Keep every setting fixed except [yoloResizeMode] for A/B runs. */
        fun fieldTest(yoloResizeMode: YoloResizeMode) = DetectionConfig(
            detectIntervalMs = 0L,
            useCenterSquareCrop = false,
            yoloResizeMode = yoloResizeMode,
            confidenceThreshold = 0.20f,
            sizeFilterMode = SizeFilterMode.DISABLED,
            bollardGeometryFilterEnabled = false,
            adaptiveTemporalEnabled = false,
            enableMlKitDetection = false,
            enableDetectorDiagnostics = true,
            debugDetectionLogging = true,
            debugSaveInferenceInput = true
        )
    }
}
