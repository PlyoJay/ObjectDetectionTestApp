package com.samin.objectdetection.detector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.camera.SizeFilterMode
import com.samin.objectdetection.camera.YoloResizeMode
import org.junit.Assert.*
import org.junit.Test
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Run on a device: compares the original tensor I/O + parser against optimized CPU inference. */
class DetectorCpuOptimizationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun cpuTwoAndFourThreadsMatchOriginalInputRawOutputAndDetections() {
        val input = InstrumentationRegistry.getArguments().getString("selfTestImage")
        val bitmap = if (input != null) requireNotNull(BitmapFactory.decodeFile(input)) else
            Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(128, 64, 32))
            }
        try {
            for (threads in listOf(2, 4)) for (xnnpack in listOf(true, false)) {
                val detector = VisionStyleYoloDetector(context, confidenceThreshold = 0.2f, nmsThreshold = 0.45f,
                    sizeFilterMode = SizeFilterMode.DISABLED, interpreterThreadCount = threads, useXnnpack = xnnpack)
                try {
                    val directory = File(context.filesDir, "golden/cpu_optimization_$threads${if (xnnpack) "" else "_xnnpack_off"}")
                    val actual = detector.runSelfTest(bitmap, input ?: "portrait_color_control", directory)
                    val modelInput = requireNotNull(BitmapFactory.decodeFile(File(directory, "input.png").path))
                    try {
                        // Original options: XNNPACK was left at the bundled default (true).
                        Interpreter(ModelAssets.load(context), Interpreter.Options().setNumThreads(threads)).use { reference ->
                            val inputTensor = reference.getInputTensor(0)
                            val shape = RgbTensorShape.from(inputTensor.shape())
                            val pixels = IntArray(shape.width * shape.height)
                            modelInput.getPixels(pixels, 0, shape.width, 0, 0, shape.width, shape.height)
                            val inputBuffer = ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder())
                            val inputQuant = inputTensor.quantizationParams()
                            RgbTensorWriter.fill(inputBuffer, pixels, shape.layout,
                                TensorValueCodec(inputTensor.dataType(), inputQuant.scale, inputQuant.zeroPoint))
                            val oldInput = ByteArray(inputBuffer.capacity())
                            inputBuffer.get(oldInput); inputBuffer.rewind()
                            assertArrayEquals(oldInput, File(directory, "input.bin").readBytes())

                            val outputTensor = reference.getOutputTensor(0)
                            val output = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder())
                            reference.run(inputBuffer, output)
                            output.rewind()
                            val quant = outputTensor.quantizationParams()
                            val codec = TensorValueCodec(outputTensor.dataType(), quant.scale, quant.zeroPoint)
                            val optimized = ByteBuffer.wrap(File(directory, "output_0.bin").readBytes()).order(ByteOrder.nativeOrder())
                            val outputShape = YoloOutputShape.from(outputTensor.shape(), ModelConfig.CLASS_LABELS.size)
                            val rows = Array(outputShape.channels) { FloatArray(outputShape.candidates) }
                            for (index in 0 until outputTensor.numElements()) {
                                val expected = codec.read(output)
                                val value = codec.read(optimized)
                                assertTrue("Raw output $index is non-finite", expected.isFinite() && value.isFinite())
                                assertEquals("Raw output $index differs", expected, value, 1e-5f + kotlin.math.abs(expected) * 1e-4f)
                                val c = if (outputShape.channelsFirst) index / outputShape.candidates else index % outputShape.channels
                                val i = if (outputShape.channelsFirst) index % outputShape.candidates else index / outputShape.channels
                                rows[c][i] = expected
                            }
                            val transform = YoloResizeTransform.calculate(YoloResizeMode.LETTERBOX,
                                bitmap.width, bitmap.height, shape.width, shape.height)
                            val expected = originalDetections(rows, transform)
                            assertEquals(expected.size, actual.size)
                            expected.zip(actual).forEach { (old, new) ->
                                assertEquals(old.label, new.label)
                                assertEquals(old.confidence, new.confidence, 1e-4f)
                                assertEquals(old.left, new.left, 0.1f); assertEquals(old.top, new.top, 0.1f)
                                assertEquals(old.right, new.right, 0.1f); assertEquals(old.bottom, new.bottom, 0.1f)
                            }
                        }
                    } finally { modelInput.recycle() }
                    assertEquals(threads, detector.executionInfo().cpuThreads)
                    assertEquals(xnnpack, detector.executionInfo().xnnpackRequested)
                    assertEquals("CPU", detector.executionInfo().appliedBackend)
                    assertEquals("SUCCESS", detector.executionInfo().lastInference)
                    assertEquals(0L, detector.executionInfo().inferenceErrors)
                } finally { detector.close(); detector.close() }
            }
        } finally { bitmap.recycle() }
    }

    @Test fun repeatedThreadSettingChangesCanCreateRunAndCloseIndependentDetectors() {
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        try {
            repeat(6) { iteration ->
                val threads = if (iteration % 2 == 0) 2 else 4
                val detector = VisionStyleYoloDetector(context, confidenceThreshold = 0.2f,
                    nmsThreshold = 0.45f, interpreterThreadCount = threads)
                try {
                    repeat(2) { detector.detect(bitmap); assertNotNull(detector.frameDiagnostics()) }
                } finally { detector.close(); detector.close() }
                assertThrows(IllegalStateException::class.java) { detector.detect(bitmap) }
            }
        } finally { bitmap.recycle() }
    }

    // Frozen pre-optimization box/clipping/NMS behavior, with size filtering disabled for comparison.
    private fun originalDetections(rows: Array<FloatArray>, transform: YoloResizeTransform): List<DetectionResult> {
        val candidates = mutableListOf<DetectionResult>()
        for (i in rows[0].indices) {
            var id = -1
            var score = 0f
            for (c in ModelConfig.CLASS_LABELS.indices) {
                val value = rows[4 + c][i]
                if (value.isFinite() && value > score) { id = c; score = value }
            }
            if (id < 0 || score < 0.2f) continue
            val cx = rows[0][i]; val cy = rows[1][i]; val w = rows[2][i]; val h = rows[3][i]
            if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0 || h <= 0) continue
            val normalized = RectF((cx-w/2f).coerceIn(0f,1f), (cy-h/2f).coerceIn(0f,1f),
                (cx+w/2f).coerceIn(0f,1f), (cy+h/2f).coerceIn(0f,1f))
            val box = DetectionResult(ModelConfig.CLASS_LABELS[id], score,
                transform.sourceX(normalized.left), transform.sourceY(normalized.top),
                transform.sourceX(normalized.right), transform.sourceY(normalized.bottom))
            if (box.right > box.left && box.bottom > box.top) candidates.add(box)
        }
        val pending = candidates.sortedByDescending { it.confidence }.take(100).toMutableList()
        val kept = mutableListOf<DetectionResult>()
        while (pending.isNotEmpty()) {
            val best = pending.removeAt(0)
            kept.add(best)
            pending.removeAll { other ->
                if (best.label != other.label) false else {
                    val intersection = maxOf(0f, minOf(best.right,other.right)-maxOf(best.left,other.left)) *
                        maxOf(0f, minOf(best.bottom,other.bottom)-maxOf(best.top,other.top))
                    val areaA = (best.right-best.left)*(best.bottom-best.top)
                    val areaB = (other.right-other.left)*(other.bottom-other.top)
                    intersection / (areaA+areaB-intersection+1e-6f) > 0.45f
                }
            }
        }
        return kept
    }
}
