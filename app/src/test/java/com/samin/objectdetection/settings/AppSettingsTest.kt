package com.samin.objectdetection.settings

import com.samin.objectdetection.camera.YoloResizeMode
import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.camera.SizeFilterMode
import com.samin.objectdetection.mlkit.MlKitDetectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AppSettingsTest {
    @Test fun mlKitModeRoundTripsIndependentlyAndOlderOrInvalidValuesKeepStreamDefault() {
        val original = AppSettings(camera = CameraSettings(previewEnabled = false),
            yolo = YoloSettings(interpreterThreadCount = 2), mlKit = MlKitSettings(detectionIntervalMs = 1500))
        assertEquals(MlKitDetectionMode.STREAM_MODE, original.mlKit.mode)
        for (mode in MlKitDetectionMode.entries) {
            val selected = original.copy(mlKit = original.mlKit.copy(mode = mode))
            val restored = AppSettingsCodec.decode(AppSettingsCodec.encode(selected))
            assertEquals(selected, restored)
            assertEquals(original.toDetectionConfig(), restored.toDetectionConfig())
            assertEquals(original, restored.copy(mlKit = original.mlKit))
            assertTrue(AppSettingsCodec.snapshotLines(restored).contains("mlKit.mode=$mode"))
        }
        val encoded = AppSettingsCodec.encode(original)
        val old = encoded.lineSequence().filterNot { it.startsWith("mlKit.mode=") }.joinToString("\n")
        assertEquals(original, AppSettingsCodec.decode(old.replace("schemaVersion=$APP_SETTINGS_SCHEMA_VERSION", "schemaVersion=6")))
        assertEquals(original, AppSettingsCodec.decode(old + "\nmlKit.mode=UNKNOWN"))
        assertEquals(original, AppSettingsCodec.decode(old + "\nmlKit.mode="))
    }
    @Test fun mlKitDiagnosticsOptInRoundTripsWithoutChangingDetectorOrOverlaySettings() {
        val original = AppSettings(yolo = YoloSettings(
            model = com.samin.objectdetection.detector.YoloModel.YOLO_512, interpreterThreadCount = 2))
        assertFalse(original.debug.enableMlKitDiagnostics)
        val enabled = original.copy(debug = original.debug.copy(enableMlKitDiagnostics = true))
        val restored = AppSettingsCodec.decode(AppSettingsCodec.encode(enabled))
        assertEquals(enabled, restored)
        assertTrue(restored.debug.enableMlKitDiagnostics)
        assertEquals(original.toDetectionConfig(), restored.toDetectionConfig())
        assertEquals(original.yolo, restored.yolo)
        assertEquals(original.mlKit, restored.mlKit)
        assertEquals(original.overlay, restored.overlay)
        assertEquals(original.warning, restored.warning)
        assertTrue(AppSettingsCodec.snapshotLines(restored).contains("debug.enableMlKitDiagnostics=true"))
    }

    @Test fun missingOrInvalidMlKitDiagnosticsDefaultsOffForOldAndCurrentSettings() {
        for (version in 1..APP_SETTINGS_SCHEMA_VERSION) {
            val restored = AppSettingsCodec.decode("schemaVersion=$version\npresetName=CUSTOM\n" +
                "yolo.interpreterThreadCount=2\nmlKit.enabled=true\noverlay.showMlKitBoxes=true")
            assertFalse("Missing opt-in must remain OFF for schema $version", restored.debug.enableMlKitDiagnostics)
            assertEquals(2, restored.yolo.interpreterThreadCount)
            assertTrue(restored.mlKit.enabled)
            assertTrue(restored.overlay.showMlKitBoxes)
        }
        assertFalse(AppSettingsCodec.decode("schemaVersion=$APP_SETTINGS_SCHEMA_VERSION\n" +
            "debug.enableMlKitDiagnostics=invalid").debug.enableMlKitDiagnostics)
    }

    @Test fun modelSelectionRoundTripsWithoutChangingPresetsThresholdsOrRoi() {
        val original = SettingsPresets.fieldTest(YoloResizeMode.LETTERBOX).copy(
            presetName = "MY MODEL TEST", camera = CameraSettings(recognitionRoi = com.samin.objectdetection.camera.RecognitionRoi.PRESET))
        for (model in listOf(com.samin.objectdetection.detector.YoloModel.YOLO_640,
            com.samin.objectdetection.detector.YoloModel.YOLO_512, com.samin.objectdetection.detector.YoloModel.YOLO_640)) {
            val selected = original.copy(yolo = original.yolo.copy(model = model))
            val restored = AppSettingsCodec.decode(AppSettingsCodec.encode(selected))
            assertEquals(selected, restored)
            assertTrue(restored.validationErrors().isEmpty())
            assertEquals(model.inputSize, restored.toDetectionConfig().inputSize)
            assertEquals(model.inputSize, restored.toDetectionConfig().inputHeight)
            assertEquals(original, restored.copy(yolo = restored.yolo.copy(model = original.yolo.model)))
        }
    }

    @Test fun oldAppliedAndCustomPresetSchemasDefaultTo640AndPreserveEveryOtherField() {
        val original = AppSettings(presetName = "OLD CUSTOM", yolo = YoloSettings(confidenceThreshold = .37f, nmsThreshold = .51f),
            camera = CameraSettings(recognitionRoi = com.samin.objectdetection.camera.RecognitionRoi.PRESET))
        for (version in 2..5) {
            val old = AppSettingsCodec.encode(original).lineSequence().filterNot { it.startsWith("yolo.model=") }
                .joinToString("\n").replace("schemaVersion=$APP_SETTINGS_SCHEMA_VERSION", "schemaVersion=$version")
            assertEquals(original, AppSettingsCodec.decode(old))
        }
        assertEquals(com.samin.objectdetection.detector.YoloModel.YOLO_640,
            AppSettingsCodec.decode("schemaVersion=6\nyolo.model=UNKNOWN").yolo.model)
    }

    @Test fun timerAndRoiPersistWithSafeMigrationAndIndependentSettings() {
        val old = AppSettingsCodec.decode("schemaVersion=4\nyolo.interpreterThreadCount=2\ncamera.previewEnabled=false")
        assertEquals(5, old.performanceLog.durationMinutes)
        assertFalse(old.camera.recognitionRoi.enabled)
        assertFalse(old.camera.previewEnabled)
        assertEquals(2, old.yolo.interpreterThreadCount)
        for (minutes in listOf(3, 5, 10)) {
            val settings = old.copy(camera = old.camera.copy(recognitionRoi = com.samin.objectdetection.camera.RecognitionRoi.PRESET),
                performanceLog = old.performanceLog.copy(durationMinutes = minutes))
            assertTrue(settings.validationErrors().isEmpty())
            assertEquals(settings, AppSettingsCodec.decode(AppSettingsCodec.encode(settings)))
            assertEquals(settings.camera.recognitionRoi, settings.toDetectionConfig().recognitionRoi)
            val snapshot = AppSettingsCodec.snapshotLines(settings)
            assertTrue(snapshot.contains("camera.roi.top=0.1"))
            assertTrue(snapshot.contains("performanceLog.durationMinutes=$minutes"))
        }
        assertTrue(old.copy(performanceLog = old.performanceLog.copy(durationMinutes = 4)).validationErrors().isNotEmpty())
        val crop = old.copy(camera = old.camera.copy(recognitionRoi = com.samin.objectdetection.camera.RecognitionRoi.PRESET))
        assertTrue(crop.copy(camera = crop.camera.copy(useCenterSquareCrop = true)).validationErrors().isNotEmpty())
        assertTrue(crop.copy(yolo = crop.yolo.copy(resizeMode = YoloResizeMode.STRETCH)).validationErrors().isNotEmpty())
    }

    @Test fun previewDefaultsAndMigrationPreservePresetsAndDetectorConfiguration() {
        assertTrue(AppSettings().camera.previewEnabled)
        SettingsPresets.builtInNames.forEach { assertTrue(SettingsPresets.byName(it)!!.camera.previewEnabled) }
        for (version in 1..3) {
            val restored = AppSettingsCodec.decode("schemaVersion=$version\npresetName=OLD\ncamera.requestedWidth=1920\noverlay.enabled=false\nyolo.confidenceThreshold=0.31")
            assertTrue(restored.camera.previewEnabled)
            assertEquals(1920, restored.camera.requestedWidth)
            assertFalse(restored.overlay.enabled)
            assertEquals(0.31f, restored.yolo.confidenceThreshold)
            assertEquals(APP_SETTINGS_SCHEMA_VERSION, restored.schemaVersion)
        }
        assertTrue(AppSettingsCodec.decode("schemaVersion=3\ncamera.previewEnabled=invalid").camera.previewEnabled)
        val source = SettingsPresets.fieldTest(YoloResizeMode.STRETCH)
        for (overlayEnabled in listOf(true, false)) {
            val on = source.copy(overlay = source.overlay.copy(enabled = overlayEnabled))
            val off = on.copy(camera = on.camera.copy(previewEnabled = false))
            val restored = AppSettingsCodec.decode(AppSettingsCodec.encode(off))
            assertEquals(off, restored)
            assertEquals(on.toDetectionConfig(), restored.toDetectionConfig())
            assertEquals(on.warning, restored.warning)
            assertEquals(on.overlay, restored.overlay)
            assertTrue(AppSettingsCodec.snapshotLines(restored).contains("camera.previewEnabled=false"))
        }
    }

    @Test fun systemDiagnosticsDefaultsAndOldStoredSettingsLeaveDetectorDebugIndependent() {
        val defaults = AppSettingsCodec.decode("schemaVersion=2")
        assertFalse(defaults.performanceLog.systemDiagnosticsEnabled)
        assertEquals(5_000L, defaults.performanceLog.sampleIntervalMs)
        val enabled = defaults.copy(performanceLog = PerformanceLogSettings(true, 1_000))
        val decoded = AppSettingsCodec.decode(AppSettingsCodec.encode(enabled))
        assertEquals(enabled, decoded)
        assertEquals(defaults.toDetectionConfig(), decoded.toDetectionConfig())
        assertEquals(defaults.debug, decoded.debug)
        assertTrue(decoded.validationErrors().isEmpty())
        assertTrue(defaults.copy(performanceLog = PerformanceLogSettings(true, 999)).validationErrors().isNotEmpty())
        assertTrue(defaults.copy(performanceLog = PerformanceLogSettings(false, 60_001)).validationErrors().isNotEmpty())
    }

    @Test
    fun defaultsRemainValidAndMapToLegacyConfigDefaults() {
        val settings = AppSettings()
        assertTrue(settings.validationErrors().isEmpty())
        val config = settings.toDetectionConfig()
        assertEquals(DetectionConfig(), config)
        assertEquals(0.20f, config.confidenceThreshold)
        assertEquals(0.45f, config.nmsThreshold)
        assertEquals(YoloResizeMode.LETTERBOX, config.yoloResizeMode)
        assertEquals(640, config.inputSize)
        assertFalse(config.useCenterSquareCrop)
        assertEquals(SizeFilterMode.DISABLED, config.sizeFilterMode)
        assertTrue(config.enableMlKitDetection)
    }

    @Test
    fun codecRoundTripPreservesEverySetting() {
        val source = SettingsPresets.fieldTest(YoloResizeMode.LETTERBOX).copy(
            motion = MotionSettings(maxHistorySize = 9, minHistorySize = 4),
            warning = WarningSettings(enableActualTts = false),
            overlay = OverlaySettings(showConfidence = true, showFps = true)
        )
        assertEquals(source, AppSettingsCodec.decode(AppSettingsCodec.encode(source)))
    }

    @Test
    fun fieldTestPresetsDifferOnlyByNameAndResizeMode() {
        val stretch = SettingsPresets.fieldTest(YoloResizeMode.STRETCH)
        val letterbox = SettingsPresets.fieldTest(YoloResizeMode.LETTERBOX)
        assertEquals(
            stretch.copy(presetName = "same", yolo = stretch.yolo.copy(resizeMode = YoloResizeMode.STRETCH)),
            letterbox.copy(presetName = "same", yolo = letterbox.yolo.copy(resizeMode = YoloResizeMode.STRETCH))
        )
    }

    @Test
    fun invalidTemporalAndNumericValuesAreRejected() {
        val invalid = AppSettings(
            yolo = YoloSettings(confidenceThreshold = 1.1f),
            filters = FilterSettings(temporalImmediateConfidence = 0.4f, temporalConfirmationConfidence = 0.6f)
        )
        val errors = invalid.validationErrors()
        assertTrue(errors.any { it.contains("Confidence") })
        assertTrue(errors.any { it.contains("Confirmation") })
    }

    @Test
    fun legacyDefaultMigratesOnceAndCustomizationsSurvive() {
        val legacyDefault = AppSettings(yolo = YoloSettings(resizeMode = YoloResizeMode.STRETCH))
        fun legacy(settings: AppSettings) = AppSettingsCodec.encode(settings).replace("schemaVersion=$APP_SETTINGS_SCHEMA_VERSION", "schemaVersion=1")
        assertEquals(AppSettings(), AppSettingsCodec.decode(legacy(legacyDefault)))
        val custom = legacyDefault.copy(yolo = legacyDefault.yolo.copy(confidenceThreshold = 0.31f))
        assertEquals(custom, AppSettingsCodec.decode(legacy(custom)))
        val explicitlyNamed = legacyDefault.copy(presetName = "MY STRETCH")
        assertEquals(explicitlyNamed, AppSettingsCodec.decode(legacy(explicitlyNamed)))
        val fieldTest = SettingsPresets.fieldTest(YoloResizeMode.STRETCH)
        assertEquals(fieldTest, AppSettingsCodec.decode(legacy(fieldTest)))
        assertEquals(legacyDefault, AppSettingsCodec.decode(AppSettingsCodec.encode(legacyDefault)))
        val migrated = AppSettingsCodec.decode(legacy(legacyDefault))
        assertEquals(migrated, AppSettingsCodec.decode(AppSettingsCodec.encode(migrated)))
    }

    @Test
    fun missingLegacyResizeUsesStretchForCustomSettingsOnly() {
        assertEquals(YoloResizeMode.LETTERBOX, AppSettingsCodec.decode("schemaVersion=1").yolo.resizeMode)
        val custom = AppSettingsCodec.decode("schemaVersion=1\npresetName=MY PRESET\nyolo.confidenceThreshold=0.3")
        assertEquals(YoloResizeMode.STRETCH, custom.yolo.resizeMode)
        assertEquals(0.3f, custom.yolo.confidenceThreshold)
    }

    @Test
    fun fieldTestLetterboxExposesYoloWithoutOptionalFilters() {
        val settings = requireNotNull(SettingsPresets.byName(SettingsPresets.FIELD_TEST_LETTERBOX))
        val config = settings.toDetectionConfig()
        assertEquals(DetectionConfig.fieldTest(YoloResizeMode.LETTERBOX), config)
        assertEquals(640, config.inputSize)
        assertEquals(0L, config.detectIntervalMs)
        assertFalse(config.useCenterSquareCrop)
        assertFalse(config.bollardGeometryFilterEnabled)
        assertFalse(config.adaptiveTemporalEnabled)
        assertFalse(config.enableMlKitDetection)
        assertTrue(config.enableDetectorDiagnostics)
        assertTrue(config.debugSaveInferenceInput)
    }

    @Test fun xnnpackDefaultsAndMigrationPreserveOldSettingsAndThreadIndependence() {
        for (version in listOf(1, 2, 3)) {
            val old = "schemaVersion=$version\npresetName=OLD\nyolo.interpreterThreadCount=2\nyolo.confidenceThreshold=0.3"
            val settings = AppSettingsCodec.decode(old)
            assertTrue(settings.yolo.useXnnpack)
            assertEquals(2, settings.yolo.interpreterThreadCount)
            assertEquals(0.3f, settings.yolo.confidenceThreshold)
            assertEquals(APP_SETTINGS_SCHEMA_VERSION, settings.schemaVersion)
        }
        assertTrue(AppSettingsCodec.decode("schemaVersion=2\nyolo.useXnnpack=invalid").yolo.useXnnpack)
        for (threads in listOf(2, 4)) for (enabled in listOf(true, false, true)) {
            val settings = AppSettings(yolo = YoloSettings(interpreterThreadCount = threads, useXnnpack = enabled))
            val restored = AppSettingsCodec.decode(AppSettingsCodec.encode(settings))
            assertEquals(settings, restored)
            assertEquals(enabled, restored.toDetectionConfig().useXnnpack)
            assertEquals(threads, restored.toDetectionConfig().interpreterThreadCount)
            assertEquals(AppSettings().toDetectionConfig().copy(interpreterThreadCount = threads, useXnnpack = enabled),
                restored.toDetectionConfig())
        }
    }
}
