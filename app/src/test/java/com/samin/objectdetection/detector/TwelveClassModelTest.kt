package com.samin.objectdetection.detector

import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.metrics.DetectionMetricsCollector
import com.samin.objectdetection.policy.ObjectTuningPolicyRegistry
import com.samin.objectdetection.policy.OverlayObjectFilter
import com.samin.objectdetection.settings.*
import org.junit.Assert.*
import org.junit.Test
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TwelveClassModelTest {
    private val expected = listOf("bollard", "person", "bicycle", "car", "motorcycle", "bus",
        "truck", "stop sign", "bench", "cat", "dog", "cardboard_box")

    @Test fun mappingPreservesEveryPreviousIdAndNewInstallDefaultsToV2() {
        assertEquals(expected, ModelConfig.CLASS_LABELS)
        assertEquals(expected, YoloModel.YOLO_512.classLabels)
        assertEquals(expected.take(11), YoloModel.YOLO_512_V1.classLabels)
        assertEquals(expected.take(9), YoloModel.YOLO_640.classLabels)
        assertEquals(YoloModel.YOLO_512, AppSettings().yolo.model)
        for (model in YoloModel.entries) {
            val settings = AppSettings(yolo = YoloSettings(model = model))
            assertEquals(settings, AppSettingsCodec.decode(AppSettingsCodec.encode(settings)))
        }
        assertEquals(YoloModel.YOLO_640, AppSettingsCodec.decode("schemaVersion=10").yolo.model)
        assertEquals(YoloModel.YOLO_512, AppSettingsCodec.decode("schemaVersion=10\nyolo.model=YOLO_512").yolo.model)
    }

    @Test fun twelveClassTensorReaderCopiesEveryValueIncludingId11InBothLayouts() {
        for (first in listOf(true, false)) {
            val shape = if (first) intArrayOf(1, 16, 5376) else intArrayOf(1, 5376, 16)
            assertEquals(YoloOutputShape(first, 16, 5376), YoloOutputShape.from(shape, 12))
            val bytes = ByteBuffer.allocate(16 * 5376 * 4).order(ByteOrder.nativeOrder())
            for (a in 0 until if (first) 16 else 5376) for (b in 0 until if (first) 5376 else 16) {
                val channel = if (first) a else b
                val candidate = if (first) b else a
                bytes.putFloat(channel * 10000f + candidate)
            }
            bytes.rewind()
            val rows = Array(16) { FloatArray(5376) }
            YoloOutputReader.copy(bytes, bytes.asFloatBuffer(), TensorValueCodec(DataType.FLOAT32), first, rows)
            rows.forEachIndexed { c, row -> row.forEachIndexed { i, value -> assertEquals(c * 10000f + i, value, 0f) } }
        }
        for (shape in listOf(intArrayOf(1, 15, 5376), intArrayOf(1, 300, 6), intArrayOf(1, 16, 16))) {
            assertThrows(IllegalArgumentException::class.java) { YoloOutputShape.from(shape, 12) }
        }
    }

    @Test fun perClassThresholdsAndEnableSettingsPersistWithoutChangingOtherClasses() {
        val options = mapOf("cardboard_box" to YoloClassOptions(true, .1f), "person" to YoloClassOptions(false))
        val settings = AppSettings(yolo = YoloSettings(confidenceThreshold = .2f, classOptions = options))
        assertTrue(settings.validationErrors().isEmpty())
        assertEquals(settings, AppSettingsCodec.decode(AppSettingsCodec.encode(settings)))
        val filter = YoloClassFilter(expected, .2f, options)
        assertTrue(filter.accepts(11, .1f)); assertFalse(filter.accepts(11, .09999f))
        assertFalse(filter.accepts(1, 1f))
        for (id in expected.indices.filter { it != 1 && it != 11 }) {
            assertTrue(filter.accepts(id, .2f)); assertFalse(filter.accepts(id, .19999f))
        }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) assertFalse(filter.accepts(11, value))
        assertFalse(filter.accepts(-1, 1f)); assertFalse(filter.accepts(12, 1f))
        assertFalse(YoloClassFilter(expected, .2f, mapOf("cardboard_box" to YoloClassOptions(false))).accepts(11, 1f))
        val oldModel = settings.copy(yolo = settings.yolo.copy(model = YoloModel.YOLO_640))
        assertEquals(options, AppSettingsCodec.decode(AppSettingsCodec.encode(oldModel)).yolo.classOptions)
        for (value in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse(settings.copy(yolo = settings.yolo.copy(classOptions = mapOf("dog" to YoloClassOptions(true, value))))
                .validationErrors().isEmpty())
        }
    }

    @Test fun cardboardBoxIsVisibleAndRecordedWithoutChangingWarningOutputs() {
        val box = DetectionResult("cardboard_box", .9f, 10f, 20f, 30f, 40f)
        assertTrue(OverlayObjectFilter.isAllowed(box.label))
        val policy = ObjectTuningPolicyRegistry.get(box.label)!!
        assertTrue(policy.enableOverlay); assertFalse(policy.enableWarning); assertFalse(policy.enableVoice)
        val frame = DetectionDebugFrame(1, 0, DetectionConfig(debugDetectionLogging = true))
        frame.raw(0, 11, box.label, .9f, .5f, .5f, .2f, .2f, .2f)
        assertTrue(frame.text().contains("classId=11 className=cardboard_box confidence=0.9"))
        val metrics = DetectionMetricsCollector()
        val all = expected.map { box.copy(label = it) }
        metrics.recordYoloDetections(all, all, emptyList(), 1234)
        val summary = metrics.buildSummary()
        val visible = summary.lineSequence().single { it.startsWith("Label visible:") }
        expected.forEach { assertTrue(visible, visible.contains("$it 1")) }
        assertTrue(summary.contains("cardboard_box 0.90"))
    }
}
