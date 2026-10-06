package com.samin.objectdetection.evaluation

import com.samin.objectdetection.detector.ModelIdentity
import com.samin.objectdetection.settings.AppSettingsCodec
import com.samin.objectdetection.settings.CameraSettings
import com.samin.objectdetection.settings.SettingsPresets
import com.samin.objectdetection.camera.YoloResizeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.util.Properties

class EvaluationJsonTest {
    @Test
    fun evaluationCapturesEverySettingsKeyAsTypedMetadata() {
        val settings = SettingsPresets.fieldTest(YoloResizeMode.LETTERBOX).copy(
            presetName = "123", camera = CameraSettings(requestedWidth = 1920, requestedHeight = 1080)
        )
        val json = settings.toJson()
        val properties = Properties().apply { load(StringReader(AppSettingsCodec.encode(settings))) }
        // Verify coverage against persisted configuration, including motion/warning/overlay settings.
        for (key in properties.stringPropertyNames()) {
            if ('.' in key) assertTrue(json.getJSONObject(key.substringBefore('.')).has(key.substringAfter('.')))
            else assertTrue(json.has(key))
        }
        assertEquals("123", json.getString("presetName"))
        assertEquals("LETTERBOX", json.getJSONObject("yolo").getString("resizeMode"))
        assertEquals(0.20, json.getJSONObject("yolo").getDouble("confidenceThreshold"), 1e-6)
        assertEquals(1920, json.getJSONObject("camera").getInt("requestedWidth"))
        assertFalse(json.getJSONObject("mlKit").getBoolean("enabled"))
        assertTrue(json.getJSONObject("debug").getBoolean("enableDetectorDiagnostics"))
    }

    @Test
    fun modelMetadataKeepsFullHashAndTensorContract() {
        val model = ModelIdentity("bollard_v2.tflite", 123456L, "abcdef0123456789".repeat(4),
            "[1, 640, 640, 3]", "FLOAT32", "[1, 5, 8400]", "FLOAT32", 1)
        val json = model.toJson()
        assertEquals(model.sha256, json.getString("sha256"))
        assertEquals(64, json.getString("sha256").length)
        assertEquals(model.assetName, json.getString("assetName"))
        assertEquals(model.assetSizeBytes, json.getLong("assetSizeBytes"))
        assertEquals(model.inputShape, json.getString("inputShape"))
        assertEquals(model.outputShape, json.getString("outputShape"))
        assertEquals(1, json.getInt("classCount"))
        assertEquals("normalized_xywh", json.getString("coordinateScale"))
    }
}
