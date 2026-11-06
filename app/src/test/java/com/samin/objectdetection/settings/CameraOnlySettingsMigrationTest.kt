package com.samin.objectdetection.settings

import com.samin.objectdetection.detector.YoloModel
import org.junit.Assert.*
import org.junit.Test

class CameraOnlySettingsMigrationTest {
    @Test fun obsoleteDistanceSettingsDoNotResetModelOrOtherPreferences() {
        for (version in 8..11) for (model in YoloModel.entries) {
            val migrated = AppSettingsCodec.decode("""
                schemaVersion=$version
                presetName=MY CUSTOM
                yolo.model=${model.name}
                yolo.confidenceThreshold=0.37
                yolo.interpreterThreadCount=3
                camera.previewEnabled=false
                overlay.showConfidence=true
                overlay.showDistance=true
                overlay.distanceMethod=ARCORE_DEPTH
                overlay.depthInputMode=RAW_WITH_FULL_FALLBACK
                debug.depthAnalysisEnabled=true
                debug.depthGroundTruthMeters=NaN
            """.trimIndent())
            assertEquals(model, migrated.yolo.model)
            assertEquals(.37f, migrated.yolo.confidenceThreshold)
            assertEquals(3, migrated.yolo.interpreterThreadCount)
            assertEquals("MY CUSTOM", migrated.presetName)
            assertFalse(migrated.camera.previewEnabled)
            assertTrue(migrated.overlay.showConfidence)
            assertTrue(migrated.validationErrors().isEmpty())
            val encoded = AppSettingsCodec.encode(migrated)
            assertFalse(encoded.contains("overlay.showDistance"))
            assertFalse(encoded.contains("overlay.distanceMethod"))
            assertFalse(encoded.contains("depth"))
            assertEquals(migrated, AppSettingsCodec.decode(encoded))
        }
    }
}
