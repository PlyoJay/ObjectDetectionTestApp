package com.samin.objectdetection.settings

import com.samin.objectdetection.camera.YoloResizeMode
import com.samin.objectdetection.camera.DetectionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals(YoloResizeMode.STRETCH, config.yoloResizeMode)
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
}
