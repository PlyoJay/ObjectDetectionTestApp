package com.samin.objectdetection.settings

import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.camera.SizeFilterMode
import com.samin.objectdetection.camera.YoloResizeMode
import com.samin.objectdetection.ui.OverlayDebugMode

const val APP_SETTINGS_SCHEMA_VERSION = 1

data class CameraSettings(
    val detectIntervalMs: Long = 0L,
    val useCenterSquareCrop: Boolean = false,
    val requestedWidth: Int = 1280,
    val requestedHeight: Int = 720
)

data class YoloSettings(
    val resizeMode: YoloResizeMode = YoloResizeMode.STRETCH,
    val confidenceThreshold: Float = DetectionConfig.DEFAULT_CONFIDENCE_THRESHOLD,
    val nmsThreshold: Float = DetectionConfig.DEFAULT_NMS_THRESHOLD,
    val interpreterThreadCount: Int = 4,
    val maxCandidates: Int = 100
)

data class FilterSettings(
    val geometryFilterEnabled: Boolean = true,
    val sizeFilterMode: SizeFilterMode = SizeFilterMode.DISABLED,
    val minBoxAreaRatio: Float = 0.015f,
    val minBoxWidthRatio: Float = 0.025f,
    val minBoxHeightRatio: Float = 0.025f,
    val bollardMinAreaRatio: Float = 0.00001f,
    val bollardMaxWidthToHeightRatio: Float = 4f,
    val adaptiveTemporalEnabled: Boolean = false,
    val temporalImmediateConfidence: Float = 0.75f,
    val temporalConfirmationConfidence: Float = 0.50f,
    val temporalMatchIouThreshold: Float = 0.30f
)

data class MotionSettings(
    val maxHistorySize: Int = 5,
    val minHistorySize: Int = 3,
    val maxMatchDistanceRatio: Float = 0.18f,
    val minSampleIntervalMs: Long = 500L,
    val staleTrackTimeoutMs: Long = 2_000L,
    val minAbsoluteAreaChange: Float = 0.005f,
    val minRelativeAreaChangeRatio: Float = 0.15f,
    val minAbsoluteHeightChange: Float = 0.03f,
    val minRelativeHeightChangeRatio: Float = 0.10f
)

data class WarningSettings(
    val cooldownMs: Long = 5_000L,
    val ignoreTopRatio: Float = 0.25f,
    val maxGuideObjectCount: Int = 2,
    val veryNearHeightRatio: Float = 0.35f,
    val nearHeightRatio: Float = 0.22f,
    val midHeightRatio: Float = 0.12f,
    val veryNearAreaRatio: Float = 0.15f,
    val nearAreaRatio: Float = 0.07f,
    val midAreaRatio: Float = 0.02f,
    val enableActualVibration: Boolean = true,
    val enableActualBeep: Boolean = true,
    val enableActualTts: Boolean = true
)

data class OverlaySettings(
    val enabled: Boolean = true,
    val debugMode: OverlayDebugMode = OverlayDebugMode.SIMPLE,
    val showYoloBoxes: Boolean = true,
    val showMlKitBoxes: Boolean = true,
    val showConfidence: Boolean = false,
    val showFps: Boolean = false,
    val staleTimeoutMs: Long = 1_500L
)

data class MlKitSettings(
    val enabled: Boolean = true,
    val detectionIntervalMs: Long = 1_500L
)

data class DebugSettings(
    val enableDetectorDiagnostics: Boolean = false,
    val debugDetectionLogging: Boolean = false,
    val diagnosticRawConfidenceThreshold: Float = 0.01f,
    val diagnosticMaxRawCandidates: Int = 200,
    val debugSaveInferenceInput: Boolean = false,
    val debugSaveIntervalMs: Long = 2_000L,
    val debugSaveOnDetection: Boolean = false,
    val legacyDebugImageEnabled: Boolean = false
)

data class AppSettings(
    val schemaVersion: Int = APP_SETTINGS_SCHEMA_VERSION,
    val presetName: String = SettingsPresets.DEFAULT,
    val camera: CameraSettings = CameraSettings(),
    val yolo: YoloSettings = YoloSettings(),
    val filters: FilterSettings = FilterSettings(),
    val motion: MotionSettings = MotionSettings(),
    val warning: WarningSettings = WarningSettings(),
    val overlay: OverlaySettings = OverlaySettings(),
    val mlKit: MlKitSettings = MlKitSettings(),
    val debug: DebugSettings = DebugSettings()
) {
    fun validationErrors(): List<String> = buildList {
        if (schemaVersion <= 0) add("설정 버전이 올바르지 않습니다.")
        if (camera.detectIntervalMs !in 0L..60_000L) add("Detect Interval은 0~60000ms여야 합니다.")
        if (camera.requestedWidth !in 320..7680 || camera.requestedHeight !in 240..4320) add("카메라 해상도가 허용 범위를 벗어났습니다.")
        if (yolo.confidenceThreshold !in 0f..1f) add("Confidence Threshold는 0~1이어야 합니다.")
        if (yolo.nmsThreshold !in 0f..1f) add("NMS Threshold는 0~1이어야 합니다.")
        if (yolo.interpreterThreadCount !in 1..16) add("Interpreter Thread Count는 1~16이어야 합니다.")
        if (yolo.maxCandidates !in 1..10_000) add("Max Candidates는 1~10000이어야 합니다.")
        listOf(filters.minBoxAreaRatio, filters.minBoxWidthRatio, filters.minBoxHeightRatio,
            filters.bollardMinAreaRatio, filters.temporalImmediateConfidence,
            filters.temporalConfirmationConfidence, filters.temporalMatchIouThreshold).forEach {
            if (it !in 0f..1f) add("필터 비율과 confidence 값은 0~1이어야 합니다.")
        }
        if (filters.bollardMaxWidthToHeightRatio <= 0f) add("Bollard 종횡비 기준은 0보다 커야 합니다.")
        if (filters.temporalConfirmationConfidence > filters.temporalImmediateConfidence) {
            add("Temporal Confirmation Confidence는 Immediate Confidence보다 클 수 없습니다.")
        }
        if (motion.maxHistorySize !in 1..100 || motion.minHistorySize !in 1..motion.maxHistorySize) {
            add("Motion history 크기를 확인하세요 (1 <= min <= max <= 100).")
        }
        if (motion.maxMatchDistanceRatio !in 0f..1f || motion.minSampleIntervalMs < 0L ||
            motion.staleTrackTimeoutMs < motion.minSampleIntervalMs) add("Motion 시간/거리 설정이 올바르지 않습니다.")
        if (listOf(motion.minAbsoluteAreaChange, motion.minRelativeAreaChangeRatio,
                motion.minAbsoluteHeightChange, motion.minRelativeHeightChangeRatio).any { it < 0f }) {
            add("Motion 변화 기준은 음수일 수 없습니다.")
        }
        if (warning.cooldownMs !in 0L..3_600_000L) add("Warning Cooldown은 0~3600000ms여야 합니다.")
        if (warning.ignoreTopRatio !in 0f..1f || warning.maxGuideObjectCount !in 1..100) add("Warning 화면 범위 설정이 올바르지 않습니다.")
        if (!(warning.midHeightRatio <= warning.nearHeightRatio && warning.nearHeightRatio <= warning.veryNearHeightRatio) ||
            !(warning.midAreaRatio <= warning.nearAreaRatio && warning.nearAreaRatio <= warning.veryNearAreaRatio) ||
            listOf(warning.midHeightRatio, warning.nearHeightRatio, warning.veryNearHeightRatio,
                warning.midAreaRatio, warning.nearAreaRatio, warning.veryNearAreaRatio).any { it !in 0f..1f }) {
            add("근접도 기준은 0~1 범위에서 MID <= NEAR <= VERY_NEAR 순서여야 합니다.")
        }
        if (overlay.staleTimeoutMs !in 100L..60_000L) add("Overlay Stale Timeout은 100~60000ms여야 합니다.")
        if (mlKit.detectionIntervalMs !in 0L..60_000L) add("ML Kit Detection Interval은 0~60000ms여야 합니다.")
        if (debug.diagnosticRawConfidenceThreshold !in 0f..1f || debug.diagnosticMaxRawCandidates !in 0..100_000 ||
            debug.debugSaveIntervalMs !in 1L..3_600_000L) add("Debug/Logging 설정값이 허용 범위를 벗어났습니다.")
    }.distinct()

    fun toDetectionConfig(): DetectionConfig = DetectionConfig(
        detectIntervalMs = camera.detectIntervalMs,
        yoloResizeMode = yolo.resizeMode,
        useCenterSquareCrop = camera.useCenterSquareCrop,
        confidenceThreshold = yolo.confidenceThreshold,
        nmsThreshold = yolo.nmsThreshold,
        sizeFilterMode = filters.sizeFilterMode,
        minBoxAreaRatio = filters.minBoxAreaRatio,
        minBoxWidthRatio = filters.minBoxWidthRatio,
        minBoxHeightRatio = filters.minBoxHeightRatio,
        ignoreTopRatioForGuide = warning.ignoreTopRatio,
        maxGuideObjectCount = warning.maxGuideObjectCount,
        overlayDebugMode = overlay.debugMode,
        saveDebugImage = debug.legacyDebugImageEnabled,
        enableDetectorDebugImage = debug.legacyDebugImageEnabled,
        enableDetectorDiagnostics = debug.enableDetectorDiagnostics,
        debugDetectionLogging = debug.debugDetectionLogging,
        diagnosticRawConfidenceThreshold = debug.diagnosticRawConfidenceThreshold,
        diagnosticMaxRawCandidates = debug.diagnosticMaxRawCandidates,
        debugSaveInferenceInput = debug.debugSaveInferenceInput,
        debugSaveIntervalMs = debug.debugSaveIntervalMs,
        debugSaveOnDetection = debug.debugSaveOnDetection,
        interpreterThreadCount = yolo.interpreterThreadCount,
        maxCandidates = yolo.maxCandidates,
        enableMlKitDetection = mlKit.enabled,
        mlKitDetectionIntervalMs = mlKit.detectionIntervalMs,
        bollardGeometryFilterEnabled = filters.geometryFilterEnabled,
        bollardMinAreaRatio = filters.bollardMinAreaRatio,
        bollardMaxWidthToHeightRatio = filters.bollardMaxWidthToHeightRatio,
        adaptiveTemporalEnabled = filters.adaptiveTemporalEnabled,
        temporalImmediateConfidence = filters.temporalImmediateConfidence,
        temporalConfirmationConfidence = filters.temporalConfirmationConfidence,
        temporalMatchIouThreshold = filters.temporalMatchIouThreshold,
        motionMaxHistorySize = motion.maxHistorySize,
        motionMinHistorySize = motion.minHistorySize,
        motionMaxMatchDistanceRatio = motion.maxMatchDistanceRatio,
        motionMinSampleIntervalMs = motion.minSampleIntervalMs,
        motionStaleTrackTimeoutMs = motion.staleTrackTimeoutMs,
        motionMinAbsoluteAreaChange = motion.minAbsoluteAreaChange,
        motionMinRelativeAreaChangeRatio = motion.minRelativeAreaChangeRatio,
        motionMinAbsoluteHeightChange = motion.minAbsoluteHeightChange,
        motionMinRelativeHeightChangeRatio = motion.minRelativeHeightChangeRatio,
        warningVeryNearHeightRatio = warning.veryNearHeightRatio,
        warningNearHeightRatio = warning.nearHeightRatio,
        warningMidHeightRatio = warning.midHeightRatio,
        warningVeryNearAreaRatio = warning.veryNearAreaRatio,
        warningNearAreaRatio = warning.nearAreaRatio,
        warningMidAreaRatio = warning.midAreaRatio
    )
}

object SettingsPresets {
    const val DEFAULT = "DEFAULT"
    const val FIELD_TEST_STRETCH = "FIELD TEST - STRETCH"
    const val FIELD_TEST_LETTERBOX = "FIELD TEST - LETTERBOX"
    const val CUSTOM = "CUSTOM"

    val builtInNames = listOf(DEFAULT, FIELD_TEST_STRETCH, FIELD_TEST_LETTERBOX)

    fun byName(name: String): AppSettings? = when (name) {
        DEFAULT -> AppSettings()
        FIELD_TEST_STRETCH -> fieldTest(YoloResizeMode.STRETCH)
        FIELD_TEST_LETTERBOX -> fieldTest(YoloResizeMode.LETTERBOX)
        else -> null
    }

    fun fieldTest(mode: YoloResizeMode): AppSettings = AppSettings(
        presetName = if (mode == YoloResizeMode.STRETCH) FIELD_TEST_STRETCH else FIELD_TEST_LETTERBOX,
        yolo = YoloSettings(resizeMode = mode, confidenceThreshold = 0.20f),
        filters = FilterSettings(
            geometryFilterEnabled = false,
            sizeFilterMode = SizeFilterMode.DISABLED,
            adaptiveTemporalEnabled = false
        ),
        mlKit = MlKitSettings(enabled = false),
        debug = DebugSettings(debugDetectionLogging = true, debugSaveInferenceInput = true)
    )
}
