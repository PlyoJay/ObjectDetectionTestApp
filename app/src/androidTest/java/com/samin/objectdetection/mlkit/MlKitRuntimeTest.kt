package com.samin.objectdetection.mlkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.camera.FrameRoi
import com.samin.objectdetection.camera.RecognitionRoi
import com.samin.objectdetection.metrics.FrameLatencyEvent
import com.samin.objectdetection.metrics.FrameLatencyRecorder
import com.samin.objectdetection.metrics.FrameTrace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/** Actual ML Kit smoke test. SDK/model unavailability is a failure, never a successful empty result. */
class MlKitRuntimeTest {
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
}
