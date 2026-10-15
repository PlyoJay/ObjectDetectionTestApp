package com.samin.objectdetection.detector

import android.os.SystemClock
import android.os.Process
import android.graphics.BitmapFactory
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
        for (threads in listOf(4, 2, 1)) for ((phase, xnnpack) in listOf(true, false, true).withIndex()) {
            val options = Interpreter.Options().setNumThreads(threads).setUseXNNPACK(xnnpack)
            // Emit before construction so native delegate/partition logs belong to this variant.
            Log.i(TAG, "[XNNPACK_VARIANT_BEGIN] phase=$phase threads=$threads requested=$xnnpack " +
                "pid=${Process.myPid()} runtime=${TensorFlowLite.runtimeVersion()}")
            Interpreter(ModelAssets.load(context), options).use { interpreter ->
                val input = ByteBuffer.allocateDirect(interpreter.getInputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
                val output = ByteBuffer.allocateDirect(interpreter.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
                while (input.hasRemaining()) input.putFloat(114 / 255f)
                val wall = mutableListOf<Long>(); val native = mutableListOf<Long>()
                var cpuStartMs = 0L
                var wallStartNs = 0L
                repeat(13) { iteration ->
                    input.rewind(); output.rewind()
                    if (iteration == 3) {
                        cpuStartMs = Process.getElapsedCpuTime()
                        wallStartNs = SystemClock.elapsedRealtimeNanos()
                    }
                    val start = SystemClock.elapsedRealtimeNanos()
                    interpreter.run(input, output)
                    val duration = SystemClock.elapsedRealtimeNanos() - start
                    if (iteration >= 3) {
                        wall.add(duration)
                        interpreter.lastNativeInferenceDurationNanoseconds?.let { native.add(it) }
                    }
                }
                val cpuMs = Process.getElapsedCpuTime() - cpuStartMs
                val wallNs = SystemClock.elapsedRealtimeNanos() - wallStartNs
                Log.i(TAG, "[YOLO_PROCESS_CPU] phase=$phase threads=$threads xnnpack=$xnnpack " +
                    "samples=${wall.size} cpuMs=$cpuMs wallMs=${wallNs/1e6} " +
                    "percentOneCore=${cpuMs * 1e8 / wallNs} scope=all_process_threads_camera_free")
                output.rewind()
                val values = FloatArray(output.capacity() / 4).also { output.asFloatBuffer().get(it) }
                val old = reference.getOrPut(threads) { values }
                values.forEachIndexed { i, value ->
                    assertTrue("non-finite output $i", value.isFinite())
                    assertEquals("XNNPACK output $i", old[i], value, 1e-5f + kotlin.math.abs(old[i]) * 1e-4f)
                }
                report("phase=$phase threads=$threads xnnpack=$xnnpack scope=run", wall)
                report("phase=$phase threads=$threads xnnpack=$xnnpack scope=nativeInvoke", native)
                if (native.size == wall.size) {
                    report("phase=$phase threads=$threads xnnpack=$xnnpack scope=runMinusNative",
                        wall.zip(native) { total, invoke -> total - invoke })
                }
            }
            Log.i(TAG, "[XNNPACK_VARIANT_END] phase=$phase threads=$threads requested=$xnnpack")
        }
    }

    @Test fun reusedInvocationContainersMatchRunByteForByteOnChangingPositiveInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = context.assets.open("self_test/known.png").use { requireNotNull(BitmapFactory.decodeStream(it)) }
        try {
            for (xnnpack in listOf(true, false)) {
                Interpreter(ModelAssets.load(context), Interpreter.Options().setNumThreads(4)
                    .setUseXNNPACK(xnnpack)).use { interpreter ->
                    val shape = RgbTensorShape.from(interpreter.getInputTensor(0).shape())
                    assertEquals(shape.width, bitmap.width); assertEquals(shape.height, bitmap.height)
                    val pixels = IntArray(shape.width * shape.height)
                    val input = ByteBuffer.allocateDirect(interpreter.getInputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
                    val outputBytes = interpreter.getOutputTensor(0).numBytes()
                    val original = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())
                    val reused = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())
                    val inputs = arrayOf<Any>(input)
                    val outputs = mapOf<Int, Any>(0 to reused)
                    val expected = ByteArray(outputBytes); val actual = ByteArray(outputBytes)
                    val times = Array(2) { mutableListOf<Long>() }
                    repeat(14) { round ->
                        bitmap.getPixels(pixels, 0, shape.width, 0, 0, shape.width, shape.height)
                        // Alternate real image and color control to expose stale buffer positions/data.
                        if (round % 2 == 1) pixels.fill(0xff724020.toInt())
                        RgbTensorWriter.fill(input, pixels, shape.layout, TensorValueCodec(DataType.FLOAT32))
                        for (offset in 0..1) {
                            val variant = (round + offset) % 2
                            input.rewind(); original.rewind(); reused.rewind()
                            val start = SystemClock.elapsedRealtimeNanos()
                            if (variant == 0) interpreter.run(input, original)
                            else interpreter.runForMultipleInputsOutputs(inputs, outputs)
                            val elapsed = SystemClock.elapsedRealtimeNanos() - start
                            if (round >= 4) times[variant].add(elapsed)
                        }
                        original.rewind(); original.get(expected)
                        reused.rewind(); reused.get(actual)
                        assertArrayEquals("xnnpack=$xnnpack round=$round", expected, actual)
                    }
                    report("xnnpack=$xnnpack invocation=originalRun", times[0])
                    report("xnnpack=$xnnpack invocation=reusedContainers", times[1])
                }
            }
        } finally { bitmap.recycle() }
    }

    private fun report(variant: String, values: List<Long>) {
        if (values.isEmpty()) {
            Log.i(TAG, "[YOLO_MICROBENCHMARK] $variant samples=0 avgMs=N/A p95Ms=N/A")
            return
        }
        val sorted = values.sorted()
        Log.i(TAG, "[YOLO_MICROBENCHMARK] $variant samples=${values.size} avgMs=${values.average()/1e6} " +
            "p95Ms=${sorted[(kotlin.math.ceil(sorted.size * 0.95).toInt() - 1).coerceAtLeast(0)]/1e6}")
    }

    companion object { private const val TAG = "YoloBottleneckBenchmark" }
}
