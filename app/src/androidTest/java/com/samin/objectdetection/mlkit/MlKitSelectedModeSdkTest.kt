package com.samin.objectdetection.mlkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.camera.FrameRoi
import com.samin.objectdetection.camera.RecognitionRoi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Uses the production constructor/options and a real SDK, without FakeDetector. */
class MlKitSelectedModeSdkTest {
    @Test fun selectedModesProcessIdenticalReusedRoiAndReportCountBoxesAndLatency(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = instrumentation.context.assets.open("mlkit_validation/640px-shoes.jpg").use {
            checkNotNull(BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }))
        }
        val buffer = MlKitInputBuffer()
        val lease = buffer.acquire(source, FrameRoi.calculate(source.width, source.height,
            RecognitionRoi(true, top = .2f, left = .1f, right = .1f), false))
        val pixels = IntArray(640 * 360).also { lease.bitmap.getPixels(it, 0, 640, 0, 0, 640, 360) }
        val output = File(instrumentation.targetContext.filesDir, "mlkit_selected_mode").apply { mkdirs() }
        val rows = mutableListOf("mode\titeration\toutcome\tsdkCount\telapsedMs\tboxes")
        val events = mutableListOf<String>()
        var singlePositive = false
        try {
            for (mode in MlKitDetectionMode.entries) {
                val detector = MlKitObjectDetector(mode)
                try {
                    repeat(4) { index ->
                        if (index > 0) kotlinx.coroutines.delay(1500)
                        val logs = java.util.concurrent.ConcurrentLinkedQueue<String>()
                        val result = detector.detect(lease.bitmap, diagnosticLog = { logs.add(it) })
                        val completion = logs.singleOrNull { it.startsWith("event=TASK_COMPLETE ") }.orEmpty()
                        fun field(key: String) = completion.split(' ').firstOrNull { it.startsWith("$key=") }?.substringAfter('=') ?: "MISSING"
                        rows.add("$mode\t$index\t${field("outcome")}\t${field("sdkCount")}\t${field("elapsedMs")}\t" +
                            result.joinToString { "${it.boundingBox.toShortString()} trackingId=${it.trackingId}" })
                        events.addAll(logs.map { "mode=$mode iteration=$index $it" })
                        File(output, "results.tsv").writeText(rows.joinToString("\n") + "\n", Charsets.UTF_8)
                        File(output, "events.txt").writeText(events.joinToString("\n") + "\n", Charsets.UTF_8)
                        assertEquals("SDK errors are not a successful zero result: $logs", "SUCCESS", field("outcome"))
                        assertEquals(result.size.toString(), field("sdkCount"))
                        assertTrue(field("elapsedMs").toDouble() >= 0)
                        assertTrue(logs.single { it.startsWith("event=PROCESS_REQUEST ") }.contains("mode=$mode"))
                        assertTrue(completion.contains("mode=$mode"))
                        if (mode == MlKitDetectionMode.SINGLE_IMAGE_MODE && result.isNotEmpty()) singlePositive = true
                        val after = IntArray(pixels.size).also { lease.bitmap.getPixels(it, 0, 640, 0, 0, 640, 360) }
                        assertArrayEquals(pixels, after)
                    }
                } finally { detector.close(); detector.close() }
            }
            assertTrue("SINGLE_IMAGE_MODE positive fixture was not detected; see results.tsv", singlePositive)
        } finally { lease.close(); buffer.close(); source.recycle() }
    }
}
