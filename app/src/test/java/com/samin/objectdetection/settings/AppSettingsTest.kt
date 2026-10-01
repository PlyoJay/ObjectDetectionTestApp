package com.samin.objectdetection.settings

import com.samin.objectdetection.camera.YoloResizeMode
import com.samin.objectdetection.camera.DetectionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AppSettingsTest {
    @Test
    fun defaultsRemainValidAndMapToLegacyConfigDefaults() {
        val settings = AppSettings()
        assertTrue(settings.validationErrors().isEmpty())
        val config = settings.toDetectionConfig()
        assertEquals(DetectionConfig(), config)
        assertEquals(0.20f, config.confidenceThreshold)
        assertEquals(0.45f, config.nmsThreshold)
        assertEquals(YoloResizeMode.LETTERBOX, config.yoloResizeMode)
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
        fun legacy(settings: AppSettings) = AppSettingsCodec.encode(settings).replace("schemaVersion=2", "schemaVersion=1")
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
}
