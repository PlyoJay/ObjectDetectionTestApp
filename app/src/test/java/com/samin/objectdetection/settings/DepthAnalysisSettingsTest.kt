package com.samin.objectdetection.settings

import org.junit.Assert.*
import org.junit.Test

class DepthAnalysisSettingsTest {
    @Test fun debugIsOptInAndOlderPresetsRemainOffWithNoGroundTruth() {
        assertFalse(AppSettings().debug.depthAnalysisEnabled)
        for (version in 1..9) {
            val value = AppSettingsCodec.decode("schemaVersion=$version\npresetName=MY PRESET")
            assertFalse(value.debug.depthAnalysisEnabled); assertNull(value.debug.depthGroundTruthMeters)
            assertEquals("MY PRESET", value.presetName)
        }
    }
    @Test fun manualTruthAndDebugRoundTripWithoutChangingYoloWarningsOrDistanceSwitch() {
        val base = AppSettings()
        val value = base.copy(debug = base.debug.copy(depthAnalysisEnabled = true, depthGroundTruthMeters = 2f, depthGroundTruthLabel = "person"))
        assertEquals(value, AppSettingsCodec.decode(AppSettingsCodec.encode(value)))
        assertEquals(base.toDetectionConfig(), value.toDetectionConfig()); assertEquals(base.warning, value.warning)
        assertEquals(base.overlay, value.overlay)
        for (invalid in listOf(value.copy(debug = value.debug.copy(depthGroundTruthMeters = 0f)),
            value.copy(debug = value.debug.copy(depthGroundTruthMeters = Float.NaN)),
            value.copy(debug = value.debug.copy(depthGroundTruthLabel = "")), value.copy(debug = value.debug.copy(depthGroundTruthLabel = "unknown"))))
            assertFalse(invalid.validationErrors().isEmpty())
    }
}
