package com.samin.objectdetection.settings

import com.samin.objectdetection.distance.DistanceOpticsMode
import com.samin.objectdetection.distance.DistanceSettings
import org.junit.Assert.*
import org.junit.Test

class DistanceSettingsTest {
    @Test fun settingsAndCustomPresetsRoundTripWithoutAlteringDetectorOrWarnings() {
        for (preset in SettingsPresets.builtInNames) for (mode in DistanceOpticsMode.entries) {
            val base = SettingsPresets.byName(preset)!!
            val selected = base.copy(presetName = "CUSTOM DISTANCE", overlay = base.overlay.copy(showDistance = true),
                distance = DistanceSettings(153.5f, 12.3f, mode, 61.5f))
            val decoded = AppSettingsCodec.decode(AppSettingsCodec.encode(selected))
            assertEquals(selected, decoded)
            assertEquals(base.toDetectionConfig(), decoded.toDetectionConfig())
            assertEquals(base.warning, decoded.warning)
            assertTrue(decoded.validationErrors().isEmpty())
        }
    }

    @Test fun everyOlderSchemaLeavesDistanceOffAndMountingUncalibrated() {
        for (version in 1..7) {
            val decoded = AppSettingsCodec.decode("schemaVersion=$version\npresetName=MY PRESET\nyolo.confidenceThreshold=0.37\ncamera.previewEnabled=false")
            assertFalse(decoded.overlay.showDistance)
            assertEquals(DistanceSettings(), decoded.distance)
            assertEquals("MY PRESET", decoded.presetName)
            assertEquals(.37f, decoded.yolo.confidenceThreshold)
            assertFalse(decoded.camera.previewEnabled)
        }
        assertTrue(AppSettings(overlay = OverlaySettings(showDistance = true)).validationErrors().isEmpty())
    }

    @Test fun calibrationRangesAndNonfiniteValuesAreRejected() {
        for (bad in listOf(DistanceSettings(cameraHeightCm = 0f), DistanceSettings(cameraHeightCm = 1001f),
            DistanceSettings(cameraHeightCm = Float.NaN), DistanceSettings(downwardPitchDegrees = -1f),
            DistanceSettings(downwardPitchDegrees = 90f), DistanceSettings(manualVerticalFovDegrees = 0f),
            DistanceSettings(manualVerticalFovDegrees = Float.POSITIVE_INFINITY))) {
            assertFalse(AppSettings(distance = bad).validationErrors().isEmpty())
        }
        for (value in listOf("NaN", "Infinity", "invalid")) {
            assertThrows(IllegalArgumentException::class.java) {
                AppSettingsCodec.decode("schemaVersion=8\ndistance.cameraHeightCm=$value")
            }
        }
    }
}
