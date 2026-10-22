package com.samin.objectdetection.mlkit

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.odml.image.MlImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetector as GoogleObjectDetector
import com.samin.objectdetection.camera.FrameRoi
import com.samin.objectdetection.metrics.FrameLatencyEvent
import com.samin.objectdetection.metrics.FrameLatencyRecorder
import com.samin.objectdetection.metrics.FrameTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Deterministic SDK Tasks exercise the production wrapper without requiring a downloaded ML model. */
class MlKitObjectDetectorTest {
    @Test fun successPreservesInputAndCopiesBoxesTrackingIdsAndCompletionLatency() = runBlocking {
        val bitmap = inputBitmap()
        val sdkObject = DetectedObject(Rect(4, 5, 20, 30), 42, emptyList())
        val completion = TaskCompletionSource<List<DetectedObject>>()
        completion.setResult(listOf(sdkObject))
        val fake = FakeDetector { image ->
            assertSame(bitmap, image.bitmapInternal)
            assertEquals(640, image.width)
            assertEquals(360, image.height)
            assertEquals(0, image.rotationDegrees)
            assertEquals(InputImage.IMAGE_FORMAT_BITMAP, image.format)
            completion.task
        }
        val detector = MlKitObjectDetector(fake)
        val events = ConcurrentLinkedQueue<FrameLatencyEvent>()
        try {
            val result = detector.detect(bitmap, latency(events), 90L).single()
            assertEquals(Rect(4, 5, 20, 30), result.boundingBox)
            assertEquals(42, result.trackingId)
            assertNotSame(sdkObject.boundingBox, result.boundingBox)
            sdkObject.boundingBox.offset(100, 100)
            assertEquals(Rect(4, 5, 20, 30), result.boundingBox)
            assertCompletion(events, "SUCCESS")
            assertFalse(bitmap.isRecycled)
        } finally { detector.close(); bitmap.recycle() }
        assertTrue(fake.closed)
    }

    @Test fun sdkFailureReturnsEmptyAndRecordsErrorOnce() = runBlocking {
        val completion = TaskCompletionSource<List<DetectedObject>>()
        completion.setException(IllegalStateException("SDK failure"))
        assertTerminalTask(completion.task, "ERROR")
    }

    @Test fun sdkCancellationReturnsEmptyOnlyAfterTaskIsTerminal() = runBlocking {
        val cancellation = CancellationTokenSource()
        val completion = TaskCompletionSource<List<DetectedObject>>(cancellation.token)
        cancellation.cancel()
        withTimeout(5_000) { assertTerminalTask(completion.task, "CANCELLED") }
        assertTrue(completion.task.isCanceled)
        assertTrue(completion.task.isComplete)
    }

    @Test fun synchronousProcessExceptionReturnsEmptyAndKeepsFallbackLatency() = runBlocking {
        val bitmap = inputBitmap()
        val detector = MlKitObjectDetector(FakeDetector { throw IllegalStateException("process failed") })
        val events = ConcurrentLinkedQueue<FrameLatencyEvent>()
        try {
            assertTrue(detector.detect(bitmap, latency(events), 90L).isEmpty())
            val event = events.single() as FrameLatencyEvent.MlKit
            assertEquals("ERROR", event.outcome)
            assertEquals("AWAIT_RETURN_FALLBACK", event.completionBasis)
            assertEquals(90L, event.dispatchedElapsedNs)
            assertTrue(event.requestedElapsedNs > 90L)
            assertFalse(bitmap.isRecycled)
        } finally { detector.close(); bitmap.recycle() }
    }

    @Test fun coroutineCancellationKeepsLeaseAndInputUnchangedUntilSdkCompletion() = runBlocking {
        val source = inputBitmap()
        source.eraseColor(Color.GREEN)
        val buffer = MlKitInputBuffer()
        val input = buffer.acquire(source, FrameRoi(0, 0, 640, 360))
        val completion = TaskCompletionSource<List<DetectedObject>>()
        val called = AtomicBoolean(false)
        val fake = FakeDetector { image ->
            assertSame(input.bitmap, image.bitmapInternal)
            called.set(true)
            completion.task
        }
        val detector = MlKitObjectDetector(fake)
        val events = ConcurrentLinkedQueue<FrameLatencyEvent>()
        val inFlight = AtomicBoolean(true)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            detector.detect(input.bitmap, latency(events), 90L)
        }
        // Same completion ownership pattern as MainActivity, including a cancelled launch.
        job.invokeOnCompletion { input.close(); inFlight.set(false) }
        try {
            assertTrue(called.get())
            job.cancel()
            assertTrue(job.isCancelled)
            assertFalse("Caller must still drain the SDK Task", job.isCompleted)
            assertTrue(inFlight.get())
            assertFalse(completion.task.isComplete)
            assertTrue(events.isEmpty())
            source.eraseColor(Color.RED)
            source.recycle()
            assertEquals(Color.GREEN, input.bitmap.getPixel(320, 180))
            val otherSource = inputBitmap()
            try {
                assertThrows(IllegalStateException::class.java) {
                    buffer.acquire(otherSource, FrameRoi(0, 0, 640, 360))
                }
            } finally { otherSource.recycle() }
            buffer.close()
            assertFalse("close must defer recycling a Task's input", input.bitmap.isRecycled)
            completion.setResult(emptyList())
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCompleted)
            assertFalse(inFlight.get())
            assertTrue(input.bitmap.isRecycled)
            assertCompletion(events, "SUCCESS")
        } finally {
            completion.trySetResult(emptyList())
            job.join()
            input.close()
            buffer.close()
            detector.close()
            if (!source.isRecycled) source.recycle()
        }
    }

    @Test fun alreadyCancelledScopeReleasesPreparedInputWithoutCallingSdk() = runBlocking {
        val source = inputBitmap()
        val buffer = MlKitInputBuffer()
        val input = buffer.acquire(source, FrameRoi(0, 0, 640, 360))
        val detector = MlKitObjectDetector(FakeDetector { error("Cancelled launch entered its body") })
        val scope = CoroutineScope(Job()).also { it.cancel() }
        val inFlight = AtomicBoolean(true)
        val entered = AtomicBoolean(false)
        try {
            val job = scope.launch { entered.set(true); detector.detect(input.bitmap) }
            job.invokeOnCompletion { input.close(); inFlight.set(false) }
            withTimeout(5_000) { job.join() }
            assertFalse(entered.get())
            assertFalse(inFlight.get())
            val next = buffer.acquire(source, FrameRoi(0, 0, 640, 360))
            try { assertSame(input.bitmap, next.bitmap) } finally { next.close() }
        } finally { input.close(); buffer.close(); detector.close(); source.recycle() }
    }

    private suspend fun assertTerminalTask(task: Task<List<DetectedObject>>, outcome: String) {
        val bitmap = inputBitmap()
        val detector = MlKitObjectDetector(FakeDetector { task })
        val events = ConcurrentLinkedQueue<FrameLatencyEvent>()
        try {
            assertTrue(detector.detect(bitmap, latency(events), 90L).isEmpty())
            assertTrue(task.isComplete)
            assertCompletion(events, outcome)
            assertFalse(bitmap.isRecycled)
        } finally { detector.close(); bitmap.recycle() }
    }

    private fun latency(events: ConcurrentLinkedQueue<FrameLatencyEvent>): FrameLatencyRecorder {
        val clock = AtomicLong(100L)
        return FrameLatencyRecorder(FrameTrace("mlkit-test", 7, 10, 10, true, 11, 20),
            clock::incrementAndGet, { events.add(it) })
    }

    private fun assertCompletion(events: ConcurrentLinkedQueue<FrameLatencyEvent>, outcome: String) {
        val event = events.single() as FrameLatencyEvent.MlKit
        assertEquals(outcome, event.outcome)
        assertEquals("TASK_COMPLETION_LISTENER", event.completionBasis)
        assertEquals(90L, event.dispatchedElapsedNs)
        assertTrue(event.requestedElapsedNs > 90L)
        assertTrue(event.observedElapsedNs >= event.requestedElapsedNs)
    }

    private fun inputBitmap() = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)

    private class FakeDetector(private val onProcess: (InputImage) -> Task<List<DetectedObject>>) : GoogleObjectDetector {
        var closed = false
        override fun process(image: InputImage) = onProcess(image)
        override fun process(image: MlImage): Task<List<DetectedObject>> = error("Unexpected MlImage input")
        override fun process(bitmap: Bitmap, rotation: Int): Task<List<DetectedObject>> = error("Unexpected Bitmap input")
        override fun process(image: Image, rotation: Int): Task<List<DetectedObject>> = error("Unexpected Image input")
        override fun process(image: Image, rotation: Int, matrix: Matrix): Task<List<DetectedObject>> = error("Unexpected Image input")
        override fun process(buffer: ByteBuffer, width: Int, height: Int, rotation: Int, format: Int): Task<List<DetectedObject>> =
            error("Unexpected ByteBuffer input")
        override fun getDetectorType() = 5
        override fun close() { closed = true }
    }
}
