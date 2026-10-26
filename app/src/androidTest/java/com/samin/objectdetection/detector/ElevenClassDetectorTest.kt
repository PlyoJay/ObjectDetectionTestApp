package com.samin.objectdetection.detector

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.camera.*
import com.samin.objectdetection.location.UserLocationSnapshot
import com.samin.objectdetection.metrics.DetectionMetricsCollector
import com.samin.objectdetection.motion.ObjectMotionTracker
import com.samin.objectdetection.pipeline.DetectionPipeline
import org.junit.Assert.*
import org.junit.Test
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer

/** Synthetic tensor scores exercise the production parser/pipeline; no accuracy claim about weights. */
class ElevenClassDetectorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val expectedLabels = listOf("bollard", "person", "bicycle", "car", "motorcycle",
        "bus", "truck", "stop sign", "bench", "cat", "dog")

    private fun field(detector: VisionStyleYoloDetector, name: String): Any =
        detector.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(detector)!!

    private fun detector(mode: YoloResizeMode) = VisionStyleYoloDetector(context,
        YoloModel.YOLO_512.assetName, .2f, .45f, SizeFilterMode.DISABLED, mode)

    private fun clearOutput(detector: VisionStyleYoloDetector): ByteBuffer =
        (field(detector, "outputBuffer") as ByteBuffer).also { buffer ->
            val values = buffer.asFloatBuffer()
            repeat(values.capacity()) { values.put(it, 0f) }
        }

    private fun box(buffer: ByteBuffer, candidate: Int, classId: Int, score: Float,
                    cx: Float, cy: Float, width: Float, height: Float) {
        val values = buffer.asFloatBuffer()
        listOf(cx, cy, width, height).forEachIndexed { channel, value ->
            values.put(channel * 5376 + candidate, value)
        }
        values.put((4 + classId) * 5376 + candidate, score)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(detector: VisionStyleYoloDetector, buffer: ByteBuffer,
                      transform: YoloResizeTransform): List<DetectionResult> =
        detector.javaClass.declaredMethods.single { it.name == "parseOutput" }.apply { isAccessible = true }
            .invoke(detector, buffer, transform, 0L, 0L, 0L, SystemClock.elapsedRealtimeNanos(),
                System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), SystemClock.elapsedRealtimeNanos(),
                0L, 0L, 0L) as List<DetectionResult>

    @Test fun allElevenClassesKeepExactScoresAndRoiBoxesWithClassAwareNmsInBothResizeModes() {
        val source = Bitmap.createBitmap(721, 1281, Bitmap.Config.ARGB_8888)
        try {
            for (mode in YoloResizeMode.entries) {
                val detector = detector(mode)
                try {
                    val identity = detector.modelIdentity()
                    assertEquals("[1, 3, 512, 512]", identity.inputShape)
                    assertEquals("[1, 15, 5376]", identity.outputShape)
                    assertEquals(11, identity.classCount)
                    assertEquals(expectedLabels, ModelAssets.labels(context, identity.assetName))
                    val interpreter = field(detector, "interpreter") as Interpreter
                    assertThrows(IllegalArgumentException::class.java) {
                        ModelAssets.validate(interpreter, identity.assetName, 9)
                    }
                    val output = clearOutput(detector)
                    assertEquals(15 * 5376 * 4, output.capacity())
                    val roi = FrameRoi.calculate(source.width, source.height, RecognitionRoi.PRESET, false)
                    val transform = YoloResizeTransform.calculate(mode, roi.width, roi.height, 512, 512)
                    val left = (roi.width * .2f * transform.scaleX + transform.padLeft) / 512
                    val right = (roi.width * .4f * transform.scaleX + transform.padLeft) / 512
                    val top = (roi.height * .25f * transform.scaleY + transform.padTop) / 512
                    val bottom = (roi.height * .75f * transform.scaleY + transform.padTop) / 512
                    // Identical boxes across classes survive; only the lower same-class duplicate is removed.
                    for (classId in expectedLabels.indices) for (duplicate in 0..1) {
                        box(output, classId * 2 + duplicate, classId, .95f - classId * .01f - duplicate * .2f,
                            (left + right) / 2, (top + bottom) / 2, right - left, bottom - top)
                    }
                    val proxy = object : ObjectDetector {
                        override fun detect(bitmap: Bitmap): List<DetectionResult> {
                            assertEquals(roi.width, bitmap.width)
                            assertEquals(roi.height, bitmap.height)
                            return parse(detector, output, transform)
                        }
                        override fun close() = Unit
                    }
                    val pipeline = DetectionPipeline(proxy, DetectionConfig(inputSize = 512,
                        recognitionRoi = RecognitionRoi.PRESET, yoloResizeMode = mode,
                        adaptiveTemporalEnabled = true), ObjectMotionTracker(), { UserLocationSnapshot() })
                    val result = pipeline.process(source, 1234, 0)
                    assertEquals(expectedLabels, result.mappedDetections.map { it.label })
                    result.mappedDetections.forEachIndexed { classId, detection ->
                        assertEquals(.95f - classId * .01f, detection.confidence, 0f)
                        assertEquals(roi.left + roi.width * .2f, detection.left, .001f)
                        assertEquals(roi.left + roi.width * .4f, detection.right, .001f)
                        assertEquals(roi.top + roi.height * .25f, detection.top, .001f)
                        assertEquals(roi.top + roi.height * .75f, detection.bottom, .001f)
                    }
                    assertEquals(expectedLabels, result.overlayDetections.map { it.label })
                    assertEquals(expectedLabels.take(9), result.warningDetections.map { it.label })
                    assertTrue(result.ignoredLabels.isEmpty())
                    assertEquals(22, detector.frameDiagnostics()!!.nmsInputCount)
                    assertEquals(11, detector.frameDiagnostics()!!.nmsOutputCount)
                    result.overlayDetections.filter { it.label in listOf("cat", "dog") }.forEach {
                        assertFalse(it.warningFeedback.shouldNotify)
                    }
                    val metrics = DetectionMetricsCollector()
                    metrics.recordYoloDetections(result.mappedDetections, result.visibleDetections,
                        result.warningDetections, 1234)
                    val summary = metrics.buildSummary()
                    val visibleLine = summary.lineSequence().single { it.startsWith("Label visible:") }
                    val confidenceLine = summary.lineSequence().single { it.startsWith("Visible avg conf:") }
                    expectedLabels.forEach { label ->
                        assertTrue(visibleLine, visibleLine.contains("$label 1"))
                        assertTrue(confidenceLine, confidenceLine.contains(label))
                    }
                } finally { detector.close() }
            }
        } finally { source.recycle() }
    }

    @Test fun animalScoresUseUnchangedConfidenceThresholdClippingAndInvalidBoxRules() {
        val detector = detector(YoloResizeMode.LETTERBOX)
        try {
            val output = clearOutput(detector)
            box(output, 0, 10, .2f, 0f, .5f, .4f, .5f) // threshold inclusive, clipped left edge
            box(output, 1, 9, .1999f, .5f, .5f, .2f, .2f)
            box(output, 2, 10, .99f, Float.NaN, .5f, .2f, .2f)
            box(output, 3, 9, .9f, .5f, .5f, 0f, .2f)
            box(output, 4, 9, .8f, .5f, .5f, .2f, .2f)
            box(output, 5, 10, .9f, -1f, .5f, .2f, .2f)
            box(output, 6, 10, Float.NaN, .5f, .5f, .2f, .2f)
            box(output, 7, 10, .7f, .8f, .5f, .2f, .2f) // same class, non-overlapping: survives
            val results = parse(detector, output,
                YoloResizeTransform.calculate(YoloResizeMode.LETTERBOX, 100, 100, 512, 512))
            assertEquals(listOf("cat", "dog", "dog"), results.map { it.label })
            assertEquals(listOf(.8f, .7f, .2f), results.map { it.confidence })
            val clipped = results.last()
            assertEquals(0f, clipped.left, .0001f)
            assertEquals(25f, clipped.top, .0001f)
            assertEquals(20f, clipped.right, .0001f)
            assertEquals(75f, clipped.bottom, .0001f)
            assertEquals(3, detector.frameDiagnostics()!!.invalidBoxCount)
        } finally { detector.close() }
    }
}
