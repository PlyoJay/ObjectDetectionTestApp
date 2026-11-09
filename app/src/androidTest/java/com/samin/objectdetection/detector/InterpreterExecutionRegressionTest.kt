package com.samin.objectdetection.detector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.camera.SizeFilterMode
import com.samin.objectdetection.camera.YoloResizeMode
import org.junit.Assert.*
import org.junit.Test
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InterpreterExecutionRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun ownedOutputsMatchImmediateNativeViewsAcrossChangingInputsAndModels() {
        val source = context.assets.open("self_test/known.png").use { requireNotNull(BitmapFactory.decodeStream(it)) }
        try {
            for (model in YoloModel.entries) {
                Interpreter(ModelAssets.load(context, model.assetName), Interpreter.Options()
                    .setNumThreads(4).setUseXNNPACK(true)).use { interpreter ->
                    val labels = ModelAssets.labels(context, model.assetName)
                    val contract = ModelAssets.validate(interpreter, model.assetName, labels.size)
                    val input = ByteBuffer.allocateDirect(interpreter.getInputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
                    val output = ByteBuffer.allocateDirect(interpreter.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
                    val inputs = arrayOf<Any>(input)
                    val outputs = mapOf<Int, Any>(0 to output)
                    val emptyOutputs = emptyMap<Int, Any>()
                    val image = Bitmap.createScaledBitmap(source, contract.input.width, contract.input.height, true)
                    try {
                        val pixels = IntArray(image.width * image.height)
                        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                        val samples = mutableListOf<Pair<Long, Long>>()
                        repeat(5) { iteration ->
                            // Changing the same input storage prevents a stale output from passing.
                            pixels[iteration] = pixels[iteration] xor 0x00ffffff
                            RgbTensorWriter.fill(input, pixels, contract.input.layout, contract.inputCodec)
                            output.rewind()
                            val start = SystemClock.elapsedRealtimeNanos()
                            interpreter.runForMultipleInputsOutputs(inputs, outputs)
                            val elapsed = SystemClock.elapsedRealtimeNanos() - start
                            val nativeNs = checkNotNull(interpreter.lastNativeInferenceDurationNanoseconds)
                            if (iteration >= 2) samples.add(elapsed to nativeNs)
                            val expected = bytes(output)
                            // The view is consumed now, never cached across another inference or close.
                            val immediate = interpreter.getOutputTensor(0).asReadOnlyBuffer()
                            assertTrue(immediate.isReadOnly)
                            assertArrayEquals(expected, bytes(immediate))
                            input.rewind()
                            interpreter.runForMultipleInputsOutputs(inputs, emptyOutputs)
                            assertArrayEquals(expected, bytes(interpreter.getOutputTensor(0).asReadOnlyBuffer()))
                        }
                        Log.i("YoloPhase3Probe", "[OWNED_BUFFER_CALL] model=${model.assetName} samples=${samples.size} " +
                            "wallAvgMs=${samples.map { it.first }.average() / 1e6} " +
                            "nativeAvgMs=${samples.map { it.second }.average() / 1e6} " +
                            "wallMinusNativeAvgMs=${samples.map { it.first - it.second }.average() / 1e6} " +
                            "scope=isolated_call_no_camera notAnOptimizationAB=true")
                    } finally { if (image !== source) image.recycle() }
                }
            }
        } finally { source.recycle() }
    }

    @Test fun cachedAreaPolicyKeepsLegacyBoundaryDecisionsAndCandidateOrder() {
        val model = YoloModel.YOLO_512
        for (mode in SizeFilterMode.entries) {
            val detector = VisionStyleYoloDetector(context, model.assetName, .2f, .45f, sizeFilterMode = mode)
            try {
                val buffer = detector.javaClass.getDeclaredField("outputBuffer").apply { isAccessible = true }
                    .get(detector) as ByteBuffer
                val shape = YoloOutputShape.from(detector.modelIdentity().outputShape
                    .trim('[', ']').split(',').map { it.trim().toInt() }.toIntArray(), model.classLabels.size)
                val values = buffer.asFloatBuffer()
                val transform = YoloResizeTransform.calculate(YoloResizeMode.LETTERBOX, 512, 512, 512, 512)
                val parser = detector.javaClass.declaredMethods.single { it.name == "parseOutput" }.apply { isAccessible = true }
                for ((width, height) in listOf(.5f to .00002f, .5f to .0002f, .5f to .001f,
                    .5f to .01f, .999f to .97f, .999f to .999f)) {
                    repeat(values.capacity()) { values.put(it, 0f) }
                    fun put(c: Int, i: Int, value: Float) {
                        values.put(if (shape.channelsFirst) c * shape.candidates + i else i * shape.channels + c, value)
                    }
                    // Same-class duplicates exercise the original confidence order and NMS.
                    for (i in 0..1) {
                        put(0, i, .5f); put(1, i, .5f); put(2, i, width); put(3, i, height)
                        put(4, i, .9f - i * .1f)
                    }
                    val area = ((.5f + width / 2).coerceIn(0f, 1f) - (.5f - width / 2).coerceIn(0f, 1f)) *
                        ((.5f + height / 2).coerceIn(0f, 1f) - (.5f - height / 2).coerceIn(0f, 1f))
                    val legacyRange = when (mode) {
                        SizeFilterMode.DISABLED -> null
                        SizeFilterMode.RELAXED -> .00005f.. .99f
                        SizeFilterMode.NORMAL -> .0005f.. .95f
                    }
                    val expectedCount = if (legacyRange == null || area in legacyRange) 1 else 0
                    @Suppress("UNCHECKED_CAST")
                    val result = parser.invoke(detector, buffer, transform, 0L, 0L, 0L,
                        SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis(),
                        SystemClock.elapsedRealtimeNanos(), SystemClock.elapsedRealtimeNanos(), 0L, 0L, 0L) as List<DetectionResult>
                    assertEquals("mode=$mode area=$area", expectedCount, result.size)
                    result.singleOrNull()?.let { assertEquals(.9f, it.confidence, 0f); assertEquals("bollard", it.label) }
                }
            } finally { detector.close() }
        }
    }

    private fun bytes(buffer: ByteBuffer): ByteArray = buffer.duplicate().apply { rewind() }.let {
        ByteArray(it.remaining()).also { copy -> it.get(copy) }
    }
}
