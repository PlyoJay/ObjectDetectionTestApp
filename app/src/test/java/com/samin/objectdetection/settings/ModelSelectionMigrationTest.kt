package com.samin.objectdetection.settings

import com.samin.objectdetection.camera.RecognitionRoi
import com.samin.objectdetection.detector.YoloClassOptions
import com.samin.objectdetection.detector.YoloModel
import org.junit.Assert.*
import org.junit.Test

class ModelSelectionMigrationTest {
    private val custom = AppSettings(presetName = "SAVED CUSTOM",
        camera = CameraSettings(previewEnabled = false, recognitionRoi = RecognitionRoi.PRESET),
        yolo = YoloSettings(confidenceThreshold = .37f, nmsThreshold = .51f,
            interpreterThreadCount = 2, useXnnpack = false,
            classOptions = mapOf("dog" to YoloClassOptions(false), "cardboard_box" to YoloClassOptions(true, .35f))),
        warning = WarningSettings(enableActualTts = false))

    @Test fun retiredMissingEmptyAndUnknownSelectionsRecoverAcrossEveryStoredSchema() {
        for (version in 1..APP_SETTINGS_SCHEMA_VERSION) {
            val encoded = AppSettingsCodec.encode(custom).lineSequence()
                .filterNot { it.startsWith("yolo.model=") }.joinToString("\n")
                .replace("schemaVersion=$APP_SETTINGS_SCHEMA_VERSION", "schemaVersion=$version")
            for (name in listOf(null, "", "YOLO_640", "YOLO_512_V1", "UNKNOWN", "best.tflite", "best_512_v1_11class.tflite")) {
                val restored = AppSettingsCodec.decode(encoded + (name?.let { "\nyolo.model=$it" } ?: ""))
                assertEquals("schema=$version model=$name", custom, restored)
                assertEquals(YoloModel.YOLO_512, restored.yolo.model)
                assertTrue(restored.validationErrors().isEmpty())
                assertEquals(restored, AppSettingsCodec.decode(AppSettingsCodec.encode(restored)))
            }
        }
    }

    @Test fun activeSelectionsSurviveEncodingAndSchemaMigrationWithAllOtherSettings() {
        for (version in 1..APP_SETTINGS_SCHEMA_VERSION) for (model in YoloModel.entries) {
            val selected = custom.copy(yolo = custom.yolo.copy(model = model))
            val encoded = AppSettingsCodec.encode(selected)
                .replace("schemaVersion=$APP_SETTINGS_SCHEMA_VERSION", "schemaVersion=$version")
            assertEquals(selected, AppSettingsCodec.decode(encoded))
        }
        assertTrue(YoloModel.YOLO_512.displayName.contains("YOLO11s"))
        assertTrue(YoloModel.YOLO_11N_512.displayName.contains("YOLO11n"))
        for (retired in listOf("best.tflite", "best_512_v1_11class.tflite")) {
            assertThrows(IllegalArgumentException::class.java) {
                com.samin.objectdetection.detector.ModelConfig.labelsFor(retired)
            }
        }
    }
}
