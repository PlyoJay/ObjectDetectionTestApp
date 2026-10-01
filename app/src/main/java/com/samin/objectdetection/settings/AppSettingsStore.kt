package com.samin.objectdetection.settings

import android.content.Context
import com.samin.objectdetection.camera.SizeFilterMode
import com.samin.objectdetection.camera.YoloResizeMode
import com.samin.objectdetection.ui.OverlayDebugMode
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

class AppSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadApplied(): AppSettings {
        if (preferences.getString(KEY_APPLY_STATE, null) == STATE_ATTEMPTING) {
            val backup = preferences.getString(KEY_BACKUP, null)
            preferences.edit()
                .putString(KEY_APPLIED, backup ?: AppSettingsCodec.encode(AppSettings()))
                .remove(KEY_APPLY_STATE)
                .remove(KEY_BACKUP)
                .commit()
        } else if (preferences.getString(KEY_APPLY_STATE, null) == STATE_PENDING) {
            preferences.edit().putString(KEY_APPLY_STATE, STATE_ATTEMPTING).commit()
        }
        val encoded = preferences.getString(KEY_APPLIED, null) ?: return AppSettings()
        val decoded = runCatching { AppSettingsCodec.decode(encoded) }
            .getOrNull()
            ?.takeIf { it.validationErrors().isEmpty() }
        if (decoded != null) {
            // Persist the migrated schema without clearing custom presets or rollback markers.
            if (AppSettingsCodec.storedSchemaVersion(encoded) < APP_SETTINGS_SCHEMA_VERSION) {
                preferences.edit().putString(KEY_APPLIED, AppSettingsCodec.encode(decoded)).commit()
            }
            return decoded
        }
        return AppSettings().also { safeDefault ->
            preferences.edit().putString(KEY_APPLIED, AppSettingsCodec.encode(safeDefault))
                .remove(KEY_APPLY_STATE).remove(KEY_BACKUP).commit()
        }
    }

    fun saveApplied(settings: AppSettings) {
        require(settings.validationErrors().isEmpty()) { settings.validationErrors().joinToString("\n") }
        val previous = preferences.getString(KEY_APPLIED, AppSettingsCodec.encode(AppSettings()))
        preferences.edit()
            .putString(KEY_BACKUP, previous)
            .putString(KEY_APPLIED, AppSettingsCodec.encode(settings))
            .putString(KEY_APPLY_STATE, STATE_PENDING)
            .commit()
    }

    /** Clears the rollback marker only after all setting-dependent runtime components were created. */
    fun markAppliedHealthy() {
        preferences.edit().remove(KEY_APPLY_STATE).remove(KEY_BACKUP).apply()
    }

    fun resetApplied() = saveApplied(AppSettings())

    fun customPresetNames(): List<String> =
        preferences.getStringSet(KEY_CUSTOM_NAMES, emptySet()).orEmpty().sorted()

    fun loadCustomPreset(name: String): AppSettings? = preferences
        .getString(customKey(name), null)
        ?.let { encoded -> runCatching { AppSettingsCodec.decode(encoded) }.getOrNull() }
        ?.takeIf { it.validationErrors().isEmpty() }
        ?.copy(presetName = name)

    fun saveCustomPreset(name: String, settings: AppSettings) {
        val safeName = name.trim()
        require(safeName.isNotEmpty()) { "프리셋 이름을 입력하세요." }
        require(safeName !in SettingsPresets.builtInNames && safeName != SettingsPresets.CUSTOM) {
            "기본 제공 프리셋 이름은 사용할 수 없습니다."
        }
        require(settings.validationErrors().isEmpty()) { settings.validationErrors().joinToString("\n") }
        val names = customPresetNames().toMutableSet().apply { add(safeName) }
        preferences.edit()
            .putStringSet(KEY_CUSTOM_NAMES, names)
            .putString(customKey(safeName), AppSettingsCodec.encode(settings.copy(presetName = safeName)))
            .apply()
    }

    fun renameCustomPreset(oldName: String, newName: String) {
        val settings = requireNotNull(loadCustomPreset(oldName)) { "프리셋을 찾을 수 없습니다." }
        saveCustomPreset(newName, settings.copy(presetName = newName))
        deleteCustomPreset(oldName)
    }

    fun deleteCustomPreset(name: String) {
        val names = customPresetNames().toMutableSet().apply { remove(name) }
        preferences.edit().putStringSet(KEY_CUSTOM_NAMES, names).remove(customKey(name)).apply()
    }

    private fun customKey(name: String) = "$KEY_CUSTOM_PREFIX${name.trim()}"

    companion object {
        private const val PREFERENCES_NAME = "object_detection_app_settings"
        private const val KEY_APPLIED = "applied_settings"
        private const val KEY_BACKUP = "backup_settings"
        private const val KEY_APPLY_STATE = "apply_state"
        private const val STATE_PENDING = "pending"
        private const val STATE_ATTEMPTING = "attempting"
        private const val KEY_CUSTOM_NAMES = "custom_preset_names"
        private const val KEY_CUSTOM_PREFIX = "custom_preset."
    }
}

/** Human-readable, forward-compatible properties format. Missing/invalid fields retain current defaults. */
object AppSettingsCodec {
    fun storedSchemaVersion(encoded: String): Int = Properties().apply { load(StringReader(encoded)) }
        .getProperty("schemaVersion")?.trim()?.toIntOrNull() ?: 1

    fun encode(value: AppSettings): String {
        val p = Properties()
        fun put(key: String, item: Any) { p.setProperty(key, item.toString()) }
        put("schemaVersion", APP_SETTINGS_SCHEMA_VERSION)
        put("presetName", value.presetName)
        with(value.camera) {
            put("camera.detectIntervalMs", detectIntervalMs); put("camera.useCenterSquareCrop", useCenterSquareCrop)
            put("camera.requestedWidth", requestedWidth); put("camera.requestedHeight", requestedHeight)
        }
        with(value.yolo) {
            put("yolo.resizeMode", resizeMode.name); put("yolo.confidenceThreshold", confidenceThreshold)
            put("yolo.nmsThreshold", nmsThreshold); put("yolo.interpreterThreadCount", interpreterThreadCount)
            put("yolo.maxCandidates", maxCandidates)
        }
        with(value.filters) {
            put("filters.geometryFilterEnabled", geometryFilterEnabled); put("filters.sizeFilterMode", sizeFilterMode.name)
            put("filters.minBoxAreaRatio", minBoxAreaRatio); put("filters.minBoxWidthRatio", minBoxWidthRatio)
            put("filters.minBoxHeightRatio", minBoxHeightRatio); put("filters.bollardMinAreaRatio", bollardMinAreaRatio)
            put("filters.bollardMaxWidthToHeightRatio", bollardMaxWidthToHeightRatio)
            put("filters.adaptiveTemporalEnabled", adaptiveTemporalEnabled)
            put("filters.temporalImmediateConfidence", temporalImmediateConfidence)
            put("filters.temporalConfirmationConfidence", temporalConfirmationConfidence)
            put("filters.temporalMatchIouThreshold", temporalMatchIouThreshold)
        }
        with(value.motion) {
            put("motion.maxHistorySize", maxHistorySize); put("motion.minHistorySize", minHistorySize)
            put("motion.maxMatchDistanceRatio", maxMatchDistanceRatio); put("motion.minSampleIntervalMs", minSampleIntervalMs)
            put("motion.staleTrackTimeoutMs", staleTrackTimeoutMs); put("motion.minAbsoluteAreaChange", minAbsoluteAreaChange)
            put("motion.minRelativeAreaChangeRatio", minRelativeAreaChangeRatio)
            put("motion.minAbsoluteHeightChange", minAbsoluteHeightChange)
            put("motion.minRelativeHeightChangeRatio", minRelativeHeightChangeRatio)
        }
        with(value.warning) {
            put("warning.cooldownMs", cooldownMs); put("warning.ignoreTopRatio", ignoreTopRatio)
            put("warning.maxGuideObjectCount", maxGuideObjectCount); put("warning.veryNearHeightRatio", veryNearHeightRatio)
            put("warning.nearHeightRatio", nearHeightRatio); put("warning.midHeightRatio", midHeightRatio)
            put("warning.veryNearAreaRatio", veryNearAreaRatio); put("warning.nearAreaRatio", nearAreaRatio)
            put("warning.midAreaRatio", midAreaRatio); put("warning.enableActualVibration", enableActualVibration)
            put("warning.enableActualBeep", enableActualBeep); put("warning.enableActualTts", enableActualTts)
        }
        with(value.overlay) {
            put("overlay.enabled", enabled); put("overlay.debugMode", debugMode.name)
            put("overlay.showYoloBoxes", showYoloBoxes); put("overlay.showMlKitBoxes", showMlKitBoxes)
            put("overlay.showConfidence", showConfidence); put("overlay.showFps", showFps)
            put("overlay.staleTimeoutMs", staleTimeoutMs)
        }
        with(value.mlKit) { put("mlKit.enabled", enabled); put("mlKit.detectionIntervalMs", detectionIntervalMs) }
        with(value.debug) {
            put("debug.enableDetectorDiagnostics", enableDetectorDiagnostics)
            put("debug.debugDetectionLogging", debugDetectionLogging)
            put("debug.diagnosticRawConfidenceThreshold", diagnosticRawConfidenceThreshold)
            put("debug.diagnosticMaxRawCandidates", diagnosticMaxRawCandidates)
            put("debug.debugSaveInferenceInput", debugSaveInferenceInput)
            put("debug.debugSaveIntervalMs", debugSaveIntervalMs)
            put("debug.debugSaveOnDetection", debugSaveOnDetection)
            put("debug.legacyDebugImageEnabled", legacyDebugImageEnabled)
        }
        return StringWriter().also { p.store(it, "ObjectDetection AppSettings") }.toString()
    }

    fun decode(encoded: String): AppSettings {
        val p = Properties().apply { load(StringReader(encoded)) }
        val storedVersion = storedSchemaVersion(encoded)
        val d = AppSettings().let { defaults ->
            if (storedVersion < 2) defaults.copy(yolo = defaults.yolo.copy(resizeMode = YoloResizeMode.STRETCH))
            else defaults
        }
        fun s(key: String, default: String) = p.getProperty(key)?.trim()?.takeIf(String::isNotEmpty) ?: default
        fun i(key: String, default: Int) = s(key, default.toString()).toIntOrNull() ?: default
        fun l(key: String, default: Long) = s(key, default.toString()).toLongOrNull() ?: default
        fun f(key: String, default: Float) = s(key, default.toString()).toFloatOrNull()?.takeIf(Float::isFinite) ?: default
        fun b(key: String, default: Boolean) = when (s(key, default.toString()).lowercase()) {
            "true" -> true; "false" -> false; else -> default
        }
        fun resize(key: String, default: YoloResizeMode) =
            YoloResizeMode.entries.firstOrNull { it.name == s(key, default.name) } ?: default
        fun sizeMode(key: String, default: SizeFilterMode) =
            SizeFilterMode.entries.firstOrNull { it.name == s(key, default.name) } ?: default
        fun overlayMode(key: String, default: OverlayDebugMode) =
            OverlayDebugMode.entries.firstOrNull { it.name == s(key, default.name) } ?: default

        val decoded = AppSettings(
            schemaVersion = APP_SETTINGS_SCHEMA_VERSION,
            presetName = s("presetName", d.presetName),
            camera = CameraSettings(
                l("camera.detectIntervalMs", d.camera.detectIntervalMs), b("camera.useCenterSquareCrop", d.camera.useCenterSquareCrop),
                i("camera.requestedWidth", d.camera.requestedWidth), i("camera.requestedHeight", d.camera.requestedHeight)
            ),
            yolo = YoloSettings(resize("yolo.resizeMode", d.yolo.resizeMode), f("yolo.confidenceThreshold", d.yolo.confidenceThreshold),
                f("yolo.nmsThreshold", d.yolo.nmsThreshold), i("yolo.interpreterThreadCount", d.yolo.interpreterThreadCount),
                i("yolo.maxCandidates", d.yolo.maxCandidates)),
            filters = FilterSettings(
                b("filters.geometryFilterEnabled", d.filters.geometryFilterEnabled), sizeMode("filters.sizeFilterMode", d.filters.sizeFilterMode),
                f("filters.minBoxAreaRatio", d.filters.minBoxAreaRatio), f("filters.minBoxWidthRatio", d.filters.minBoxWidthRatio),
                f("filters.minBoxHeightRatio", d.filters.minBoxHeightRatio), f("filters.bollardMinAreaRatio", d.filters.bollardMinAreaRatio),
                f("filters.bollardMaxWidthToHeightRatio", d.filters.bollardMaxWidthToHeightRatio),
                b("filters.adaptiveTemporalEnabled", d.filters.adaptiveTemporalEnabled),
                f("filters.temporalImmediateConfidence", d.filters.temporalImmediateConfidence),
                f("filters.temporalConfirmationConfidence", d.filters.temporalConfirmationConfidence),
                f("filters.temporalMatchIouThreshold", d.filters.temporalMatchIouThreshold)
            ),
            motion = MotionSettings(
                i("motion.maxHistorySize", d.motion.maxHistorySize), i("motion.minHistorySize", d.motion.minHistorySize),
                f("motion.maxMatchDistanceRatio", d.motion.maxMatchDistanceRatio), l("motion.minSampleIntervalMs", d.motion.minSampleIntervalMs),
                l("motion.staleTrackTimeoutMs", d.motion.staleTrackTimeoutMs), f("motion.minAbsoluteAreaChange", d.motion.minAbsoluteAreaChange),
                f("motion.minRelativeAreaChangeRatio", d.motion.minRelativeAreaChangeRatio),
                f("motion.minAbsoluteHeightChange", d.motion.minAbsoluteHeightChange),
                f("motion.minRelativeHeightChangeRatio", d.motion.minRelativeHeightChangeRatio)
            ),
            warning = WarningSettings(
                l("warning.cooldownMs", d.warning.cooldownMs), f("warning.ignoreTopRatio", d.warning.ignoreTopRatio),
                i("warning.maxGuideObjectCount", d.warning.maxGuideObjectCount),
                f("warning.veryNearHeightRatio", d.warning.veryNearHeightRatio), f("warning.nearHeightRatio", d.warning.nearHeightRatio),
                f("warning.midHeightRatio", d.warning.midHeightRatio), f("warning.veryNearAreaRatio", d.warning.veryNearAreaRatio),
                f("warning.nearAreaRatio", d.warning.nearAreaRatio), f("warning.midAreaRatio", d.warning.midAreaRatio),
                b("warning.enableActualVibration", d.warning.enableActualVibration),
                b("warning.enableActualBeep", d.warning.enableActualBeep), b("warning.enableActualTts", d.warning.enableActualTts)
            ),
            overlay = OverlaySettings(
                b("overlay.enabled", d.overlay.enabled), overlayMode("overlay.debugMode", d.overlay.debugMode),
                b("overlay.showYoloBoxes", d.overlay.showYoloBoxes), b("overlay.showMlKitBoxes", d.overlay.showMlKitBoxes),
                b("overlay.showConfidence", d.overlay.showConfidence), b("overlay.showFps", d.overlay.showFps),
                l("overlay.staleTimeoutMs", d.overlay.staleTimeoutMs)
            ),
            mlKit = MlKitSettings(b("mlKit.enabled", d.mlKit.enabled), l("mlKit.detectionIntervalMs", d.mlKit.detectionIntervalMs)),
            debug = DebugSettings(
                b("debug.enableDetectorDiagnostics", d.debug.enableDetectorDiagnostics),
                b("debug.debugDetectionLogging", d.debug.debugDetectionLogging),
                f("debug.diagnosticRawConfidenceThreshold", d.debug.diagnosticRawConfidenceThreshold),
                i("debug.diagnosticMaxRawCandidates", d.debug.diagnosticMaxRawCandidates),
                b("debug.debugSaveInferenceInput", d.debug.debugSaveInferenceInput),
                l("debug.debugSaveIntervalMs", d.debug.debugSaveIntervalMs),
                b("debug.debugSaveOnDetection", d.debug.debugSaveOnDetection),
                b("debug.legacyDebugImageEnabled", d.debug.legacyDebugImageEnabled)
            )
        )
        // Only an unchanged v1 DEFAULT can be identified safely as the former default.
        // Explicit STRETCH presets and every customized configuration retain their resize mode.
        val legacyDefault = d.copy(yolo = d.yolo.copy(resizeMode = YoloResizeMode.STRETCH))
        return if (storedVersion < 2 && decoded == legacyDefault) {
            decoded.copy(yolo = decoded.yolo.copy(resizeMode = YoloResizeMode.LETTERBOX))
        } else decoded
    }

    fun snapshotLines(settings: AppSettings): String = encode(settings)
        .lineSequence().filterNot { it.startsWith("#") }.sorted().joinToString("\n")
}
