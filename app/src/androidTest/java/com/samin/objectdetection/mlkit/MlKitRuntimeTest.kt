package com.samin.objectdetection.mlkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.camera.FrameRoi
import com.samin.objectdetection.camera.RecognitionRoi
import com.samin.objectdetection.metrics.FrameLatencyEvent
import com.samin.objectdetection.metrics.FrameLatencyRecorder
import com.samin.objectdetection.metrics.FrameTrace
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/** Actual ML Kit smoke test. SDK/model unavailability is a failure, never a successful empty result. */
class MlKitRuntimeTest {
    @Test fun realStreamSdkReportsLegacyAndReusedInputsAtProductionInterval() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = checkNotNull(context.assets.open("self_test/known.png").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        })
        val roi = FrameRoi.calculate(source.width, source.height,
            RecognitionRoi(true, top = .20f, left = .10f, right = .10f), false)
        val reference = legacyInput(source, roi)
        val counts = linkedMapOf<String, MutableList<Int>>()
        try {
            val expectedPixels = pixels(reference)
            for (path in listOf("LEGACY", "P1")) {
                // Each path starts with its own STREAM_MODE/multiple-objects client.
                // Tracking state and IDs are deliberately not compared across clients.
                val detector = MlKitObjectDetector()
                val buffer = if (path == "P1") MlKitInputBuffer() else null
                var previousP1Input: Bitmap? = null
                val pathCounts = mutableListOf<Int>().also { counts[path] = it }
                try {
                    repeat(4) { index ->
                        if (index > 0) delay(1500L)
                        val lease = buffer?.acquire(source, roi)
                        val input = lease?.bitmap ?: legacyInput(source, roi)
                        try {
                            assertArrayEquals("Pre-SDK pixels must match legacy: path=$path iteration=$index",
                                expectedPixels, pixels(input))
                            if (buffer != null) {
                                previousP1Input?.let { assertSame(it, input) }
                                previousP1Input = input
                            }
                            val events = ConcurrentLinkedQueue<FrameLatencyEvent>()
                            val dispatchedNs = SystemClock.elapsedRealtimeNanos()
                            val recorder = FrameLatencyRecorder(
                                FrameTrace("mlkit-comparison-$path", index.toLong(), System.currentTimeMillis(),
                                    dispatchedNs, true, System.currentTimeMillis(), dispatchedNs),
                                SystemClock::elapsedRealtimeNanos, { events.add(it) })
                            val results = detector.detect(input, recorder, dispatchedNs)
                            val event = events.single() as FrameLatencyEvent.MlKit
                            assertEquals("path=$path iteration=$index SDK must complete successfully", "SUCCESS", event.outcome)
                            assertEquals("TASK_COMPLETION_LISTENER", event.completionBasis)
                            results.forEach { detection ->
                                val box = detection.boundingBox
                                assertTrue("path=$path iteration=$index out-of-range box=$box",
                                    box.left in 0..640 && box.right in 0..640 && box.top in 0..360 && box.bottom in 0..360 &&
                                        box.left <= box.right && box.top <= box.bottom)
                            }
                            pathCounts.add(results.size)
                            Log.i("MlKitRuntimeComparison", "path=$path iteration=$index mode=STREAM_MODE multi=true " +
                                "fixture=self_test/known.png source=${source.width}x${source.height} roi=$roi " +
                                "input=640x360 intervalMs=1500 outcome=${event.outcome} count=${results.size} " +
                                "boxes=${results.joinToString { "${it.boundingBox}/trackingId=${it.trackingId}" }}")
                        } finally { if (lease != null) lease.close() else input.recycle() }
                    }
                } finally { detector.close(); buffer?.close() }
            }
            Log.i("MlKitRuntimeComparison", "summary legacy=${counts["LEGACY"]} p1=${counts["P1"]} " +
                "bothAlwaysZero=${counts.values.flatten().all { it == 0 }}")
        } finally { reference.recycle(); source.recycle() }
        Unit // JUnit requires a void method; Log.i above returns Int.
    }

    @Test fun realSdkCompletesTwiceWithReusedInputAfterCameraSourceIsRecycled() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = MlKitObjectDetector()
        val buffer = MlKitInputBuffer()
        var previousInput: Bitmap? = null
        try {
            repeat(2) { index ->
                val options = BitmapFactory.Options().apply {
                    inMutable = true
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val source = checkNotNull(context.assets.open("self_test/known.png").use {
                    BitmapFactory.decodeStream(it, null, options)
                })
                try {
                    val roi = FrameRoi.calculate(source.width, source.height, RecognitionRoi.PRESET, false)
                    val input = buffer.acquire(source, roi)
                    try {
                        previousInput?.let { assertSame("Input must be reused after SDK completion", it, input.bitmap) }
                        previousInput = input.bitmap
                        val preparedPixels = pixels(input.bitmap)
                        source.eraseColor(Color.MAGENTA)
                        source.recycle()
                        assertArrayEquals("Camera recycling must not change ML Kit's input", preparedPixels, pixels(input.bitmap))

                        val events = ConcurrentLinkedQueue<FrameLatencyEvent>()
                        val dispatchedNs = SystemClock.elapsedRealtimeNanos()
                        val recorder = FrameLatencyRecorder(
                            FrameTrace("mlkit-runtime-test", index.toLong(), System.currentTimeMillis(),
                                dispatchedNs, true, System.currentTimeMillis(), dispatchedNs),
                            SystemClock::elapsedRealtimeNanos, { events.add(it) })
                        val results = detector.detect(input.bitmap, recorder, dispatchedNs)
                        val event = events.single() as FrameLatencyEvent.MlKit
                        assertEquals("ML Kit must really complete, rather than return empty after an SDK error",
                            "SUCCESS", event.outcome)
                        assertEquals("TASK_COMPLETION_LISTENER", event.completionBasis)
                        assertTrue(event.requestedElapsedNs >= dispatchedNs)
                        assertTrue(event.observedElapsedNs >= event.requestedElapsedNs)
                        assertArrayEquals("ML Kit must leave reusable input unchanged", preparedPixels, pixels(input.bitmap))
                        assertFalse(input.bitmap.isRecycled)
                        results.forEach { detection ->
                            val box = detection.boundingBox
                            assertTrue("Out-of-range input box: $box", box.left in 0..640 && box.right in 0..640 &&
                                box.top in 0..360 && box.bottom in 0..360)
                            assertTrue(box.left <= box.right && box.top <= box.bottom)
                        }
                    } finally { input.close() }
                } finally { if (!source.isRecycled) source.recycle() }
            }
        } finally { detector.close(); buffer.close() }
        assertTrue(checkNotNull(previousInput).isRecycled)
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun legacyInput(source: Bitmap, roi: FrameRoi): Bitmap {
        val cropped = Bitmap.createBitmap(source, roi.left, roi.top, roi.width, roi.height)
        val scaled = Bitmap.createScaledBitmap(cropped, 640, 360, true)
        val owned = if (scaled === source) checkNotNull(scaled.copy(Bitmap.Config.ARGB_8888, false)) else scaled
        if (cropped !== source && cropped !== owned) cropped.recycle()
        return owned
    }
}
