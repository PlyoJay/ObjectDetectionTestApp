package com.samin.objectdetection.detector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.camera.YoloResizeMode
import org.junit.Assert.*
import org.junit.Test
import org.tensorflow.lite.DataType
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DetectorPreprocessingRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun field(detector: VisionStyleYoloDetector, name: String): Any =
        VisionStyleYoloDetector::class.java.getDeclaredField(name).apply { isAccessible = true }.get(detector)!!

    @Test fun independentLetterboxReferenceMatchesAcrossShapeChangesAndReusesStorage() {
        val detector = VisionStyleYoloDetector(context, confidenceThreshold = 0.2f, nmsThreshold = 0.45f)
        val names = listOf("inputBuffer", "outputBuffer", "pixels", "outputData", "inputFloatView",
            "outputFloatView", "inputFloatData", "reusableInputBitmap", "reusableInputCanvas")
        val original = names.associateWith { field(detector, it) }
        @Suppress("UNCHECKED_CAST")
        val rows = (original.getValue("outputData") as Array<FloatArray>).toList()
        try {
            for ((width, height) in listOf(720 to 1280, 1280 to 720, 640 to 640, 511 to 733, 720 to 1280)) {
                val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val pattern = IntArray(width * height) { 0xff000000.toInt() or (it * 7919 and 0xffffff) }
                source.setPixels(pattern, 0, width, 0, 0, width, height)
                val referenceBitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
                try {
                    // Frozen pre-change Canvas sampling, independent of detector's exported input.
                    val transform = YoloResizeTransform.calculate(YoloResizeMode.LETTERBOX, width, height, 640, 640)
                    Canvas(referenceBitmap).apply {
                        drawColor(Color.rgb(114, 114, 114))
                        drawBitmap(source, null, RectF(transform.padLeft.toFloat(), transform.padTop.toFloat(),
                            (transform.padLeft + transform.scaledWidth).toFloat(),
                            (transform.padTop + transform.scaledHeight).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                    val pixels = IntArray(640 * 640)
                    referenceBitmap.getPixels(pixels, 0, 640, 0, 0, 640, 640)
                    val reference = ByteBuffer.allocate(640 * 640 * 12).order(ByteOrder.nativeOrder())
                    RgbTensorWriter.fill(reference, pixels, RgbTensorLayout.NCHW, TensorValueCodec(DataType.FLOAT32))
                    detector.detect(source)
                    val actual = (field(detector, "inputBuffer") as ByteBuffer).duplicate().apply { rewind() }
                    val bytes = ByteArray(actual.capacity()); actual.get(bytes)
                    assertArrayEquals("Letterbox $width x $height", reference.array(), bytes)
                    names.forEach { assertSame("storage $it", original.getValue(it), field(detector, it)) }
                    @Suppress("UNCHECKED_CAST")
                    (field(detector, "outputData") as Array<FloatArray>).forEachIndexed { i, row -> assertSame(rows[i], row) }
                    val timing = checkNotNull(detector.frameDiagnostics())
                    assertEquals(timing.resizeTimeMs + timing.inputBufferTimeMs, timing.preprocessTimeMs)
                    assertEquals(0L, timing.inputDiagnosticsTimeNs)
                    assertEquals(0L, timing.outputDiagnosticsTimeNs)
                    assertTrue(timing.getPixelsTimeNs >= 0 && timing.rgbWriteTimeNs >= 0 && timing.nativeInferenceTimeNs > 0)
                } finally { source.recycle(); referenceBitmap.recycle() }
            }
        } finally { detector.close() }
    }

    @Test fun repeatedPositiveImageKeepsRawOutputAndDetectionsAndReportsStageTimings() {
        val source = context.assets.open("self_test/known.png").use { requireNotNull(BitmapFactory.decodeStream(it)) }
        val detector = VisionStyleYoloDetector(context, confidenceThreshold = 0.2f, nmsThreshold = 0.45f)
        try {
            val expected = detector.detect(source).map { it.copy(frameTimestampMs = 0) }
            assertTrue("Known image must exercise detections", expected.isNotEmpty())
            fun output() = (field(detector, "outputBuffer") as ByteBuffer).duplicate().order(ByteOrder.nativeOrder())
                .apply { rewind() }.asFloatBuffer().let { view -> FloatArray(view.capacity()).also { view.get(it) } }
            val raw = output()
            val samples = mutableListOf<DetectorFrameDiagnostics>()
            repeat(13) { i ->
                val results = detector.detect(source)
                assertEquals(expected, results.map { it.copy(frameTimestampMs = 0) })
                assertArrayEquals(raw, output(), 0f)
                if (i >= 3) samples.add(checkNotNull(detector.frameDiagnostics()))
            }
            Log.i("YoloBottleneckBenchmark", "[YOLO_STAGE_SAMPLE] scope=detector_no_camera positiveImage=true samples=${samples.size} " +
                "preprocessAvgMs=${samples.map { it.preprocessTimeMs }.average()} " +
                "resizeAvgMs=${samples.map { it.resizeTimeMs }.average()} " +
                "getPixelsAvgMs=${samples.map { it.getPixelsTimeNs }.average()/1e6} " +
                "rgbWriteAvgMs=${samples.map { it.rgbWriteTimeNs }.average()/1e6} " +
                "inferenceAvgMs=${samples.map { it.inferenceTimeMs }.average()} " +
                "nativeInvokeAvgMs=${samples.map { it.nativeInferenceTimeNs }.average()/1e6} " +
                "outputCopyAvgMs=${samples.map { it.outputCopyTimeMs }.average()} " +
                "candidateScanAvgMs=${samples.map { it.candidateScanTimeMs }.average()} " +
                "nmsAvgMs=${samples.map { it.nmsTimeMs }.average()}")
        } finally { detector.close(); source.recycle() }
    }

    @Test fun xnnpackOnOffPositiveImageMatchesWithinToleranceWithoutChangingDefault() {
        val source = context.assets.open("self_test/known.png").use { requireNotNull(BitmapFactory.decodeStream(it)) }
        val directories = listOf(true, false).map { File(context.filesDir, "golden/xnnpack_positive_$it") }
        val results = mutableListOf<List<DetectionResult>>()
        try {
            listOf(true, false).forEachIndexed { index, enabled ->
                val detector = VisionStyleYoloDetector(context, confidenceThreshold = 0.2f, nmsThreshold = 0.45f,
                    useXnnpack = enabled)
                try {
                    results.add(detector.runSelfTest(source, "known_positive", directories[index]))
                    assertEquals(enabled, detector.executionInfo().xnnpackRequested)
                } finally { detector.close() }
            }
            assertArrayEquals(File(directories[0], "input.bin").readBytes(), File(directories[1], "input.bin").readBytes())
            val old = ByteBuffer.wrap(File(directories[0], "output_0.bin").readBytes()).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val new = ByteBuffer.wrap(File(directories[1], "output_0.bin").readBytes()).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in 0 until old.capacity()) {
                val expected = old.get(); val actual = new.get()
                assertTrue(expected.isFinite() && actual.isFinite())
                assertEquals("raw output $i", expected, actual, 1e-5f + kotlin.math.abs(expected) * 1e-4f)
            }
            assertTrue(results[0].isNotEmpty())
            assertEquals(results[0].size, results[1].size)
            results[0].zip(results[1]).forEach { (oldResult, newResult) ->
                assertEquals(oldResult.label, newResult.label)
                assertEquals(oldResult.confidence, newResult.confidence, 1e-4f)
                assertEquals(oldResult.left, newResult.left, 0.1f); assertEquals(oldResult.top, newResult.top, 0.1f)
                assertEquals(oldResult.right, newResult.right, 0.1f); assertEquals(oldResult.bottom, newResult.bottom, 0.1f)
            }
        } finally { source.recycle() }
    }
}
