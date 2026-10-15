package com.samin.objectdetection.detector

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.TensorFlowLite
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opt-in isolated measurements, not camera FPS or a performance assertion. */
class YoloBottleneckBenchmarkTest {
    @Test fun compareRgbWritersOnIdenticalReusableBuffers() {
        val pixels = IntArray(640 * 640) { i -> 0xff000000.toInt() or (i * 7919 and 0xffffff) }
        val buffer = ByteBuffer.allocateDirect(pixels.size * 12).order(ByteOrder.nativeOrder())
        val view = buffer.asFloatBuffer()
        val staging = FloatArray(pixels.size * 3)
        val writers = listOf<Pair<String, () -> Unit>>(
            "legacyDivision" to { RgbTensorWriter.fill(buffer, pixels, RgbTensorLayout.NCHW, TensorValueCodec(DataType.FLOAT32)) },
            "scalarLookup" to { RgbTensorWriter.fillFloat32(view, pixels, RgbTensorLayout.NCHW) },
            "bulkLookup" to { RgbTensorWriter.fillFloat32Bulk(view, pixels, RgbTensorLayout.NCHW, staging) }
        )
        val reference = ByteArray(buffer.capacity())
        writers[0].second(); buffer.get(reference); buffer.rewind()
        for ((name, writer) in writers) {
            writer(); buffer.rewind()
            val actual = ByteArray(buffer.capacity()); buffer.get(actual); buffer.rewind()
            assertArrayEquals(name, reference, actual)
        }
        val samples = Array(writers.size) { mutableListOf<Long>() }
        repeat(40) { round ->
            // Rotate order to reduce consistent thermal/order bias.
            for (offset in writers.indices) {
                val index = (round + offset) % writers.size
                val start = SystemClock.elapsedRealtimeNanos()
                writers[index].second()
                if (round >= 10) samples[index].add(SystemClock.elapsedRealtimeNanos() - start)
            }
        }
        writers.forEachIndexed { index, (name, _) -> report("writer=$name", samples[index]) }
    }

    @Test fun compareXnnpackOptionsAndNativeInvokeWithoutCamera() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Identical input for every variant; re-create so thread settings reach delegate construction.
        val reference = mutableMapOf<Int, FloatArray>()
        for (threads in listOf(4, 2)) for (xnnpack in listOf(true, false, true)) {
            val options = Interpreter.Options().setNumThreads(threads).setUseXNNPACK(xnnpack)
            Interpreter(ModelAssets.load(context), options).use { interpreter ->
                val input = ByteBuffer.allocateDirect(interpreter.getInputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
                val output = ByteBuffer.allocateDirect(interpreter.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
                while (input.hasRemaining()) input.putFloat(114 / 255f)
                val wall = mutableListOf<Long>(); val native = mutableListOf<Long>()
                Log.i(TAG, "[XNNPACK_VARIANT_BEGIN] threads=$threads requested=$xnnpack runtime=${TensorFlowLite.runtimeVersion()}")
                repeat(13) { iteration ->
                    input.rewind(); output.rewind()
                    val start = SystemClock.elapsedRealtimeNanos()
                    interpreter.run(input, output)
                    val duration = SystemClock.elapsedRealtimeNanos() - start
                    if (iteration >= 3) {
                        wall.add(duration)
                        interpreter.lastNativeInferenceDurationNanoseconds?.let { native.add(it) }
                    }
                }
                output.rewind()
                val values = FloatArray(output.capacity() / 4).also { output.asFloatBuffer().get(it) }
                val old = reference.getOrPut(threads) { values }
                values.forEachIndexed { i, value ->
                    assertTrue("non-finite output $i", value.isFinite())
                    assertEquals("XNNPACK output $i", old[i], value, 1e-5f + kotlin.math.abs(old[i]) * 1e-4f)
                }
                report("threads=$threads xnnpack=$xnnpack scope=run", wall)
                report("threads=$threads xnnpack=$xnnpack scope=nativeInvoke", native)
            }
        }
    }

    private fun report(variant: String, values: List<Long>) {
        val sorted = values.sorted()
        Log.i(TAG, "[YOLO_MICROBENCHMARK] $variant samples=${values.size} avgMs=${values.average()/1e6} " +
            "p95Ms=${sorted[(kotlin.math.ceil(sorted.size * 0.95).toInt() - 1).coerceAtLeast(0)]/1e6}")
    }

    companion object { private const val TAG = "YoloBottleneckBenchmark" }
}
