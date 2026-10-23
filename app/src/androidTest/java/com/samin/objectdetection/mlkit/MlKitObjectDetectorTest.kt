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
    @Test fun modesMapToSdkOptionsAndDiagnosticRequestsUseImmutableSelectedMode() = runBlocking {
        val bitmap = inputBitmap()
        try {
            for (mode in MlKitDetectionMode.entries) {
                val options = MlKitObjectDetector.options(mode)
                assertEquals(if (mode == MlKitDetectionMode.STREAM_MODE)
                    com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions.STREAM_MODE
                    else com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions.SINGLE_IMAGE_MODE, options.detectorMode)
                assertTrue(options.isMultipleObjectsEnabled)
                assertFalse(options.isClassificationEnabled)
                val fake = FakeDetector { TaskCompletionSource<List<DetectedObject>>().apply { setResult(emptyList()) }.task }
                val detector = MlKitObjectDetector(fake, mode)
                val lines = mutableListOf<String>()
                detector.detect(bitmap, diagnosticLog = lines::add)
                assertTrue(lines.single { it.startsWith("event=PROCESS_REQUEST") }.contains("mode=$mode"))
                val complete = lines.single { it.startsWith("event=TASK_COMPLETE") }
                assertTrue(complete.contains("mode=$mode")); assertTrue(complete.contains("elapsedMs="))
                detector.close(); detector.close()
                assertEquals(1, fake.closeCalls)
            }
        } finally { bitmap.recycle() }
    }
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

    @Test fun successfulZeroSingleAndMultipleObjectsPreserveSdkCountAndOrder() = runBlocking {
        val bitmap = inputBitmap()
        var requestedObjects = emptyList<DetectedObject>()
        val detector = MlKitObjectDetector(FakeDetector {
            TaskCompletionSource<List<DetectedObject>>().apply { setResult(requestedObjects) }.task
        })
        try {
            for (count in listOf(0, 1, 3)) {
                requestedObjects = List(count) { index ->
                    DetectedObject(Rect(index * 100, index * 40, index * 100 + 80, index * 40 + 30),
                        if (index == 1) null else index + 10, emptyList())
                }
                val events = ConcurrentLinkedQueue<FrameLatencyEvent>()
                val results = detector.detect(bitmap, latency(events), 90L)
                assertEquals(count, results.size)
                requestedObjects.zip(results).forEach { (sdk, actual) ->
                    assertEquals(sdk.boundingBox, actual.boundingBox)
                    assertEquals(sdk.trackingId, actual.trackingId)
                    assertNotSame(sdk.boundingBox, actual.boundingBox)
                }
                // A legitimate zero-object completion remains distinguishable from SDK errors.
                assertCompletion(events, "SUCCESS")
            }
        } finally { detector.close(); bitmap.recycle() }
    }

    @Test fun diagnosticsWithoutP0DistinguishZeroOneMultipleObjectsAndSdkFailure() = runBlocking {
        val bitmap = inputBitmap()
        var requestedCount = 0
        val detector = MlKitObjectDetector(FakeDetector {
            TaskCompletionSource<List<DetectedObject>>().apply {
                if (requestedCount < 0) setException(IllegalStateException("scripted SDK error"))
                else setResult(List(requestedCount) { DetectedObject(Rect(10, 20, 80, 90), it, emptyList()) })
            }.task
        })
        try {
            for (count in listOf(0, 1, 3, -1)) {
                requestedCount = count
                val diagnostics = mutableListOf<String>()
                val results = detector.detect(bitmap, diagnosticLog = diagnostics::add)
                assertEquals(count.coerceAtLeast(0), results.size)
                val request = diagnostics.single { it.startsWith("event=PROCESS_REQUEST ") }
                assertTrue(request.contains("input=640x360"))
                assertTrue(request.contains("rotation=0"))
                assertTrue(request.contains("mode=STREAM_MODE"))
                val completion = diagnostics.single { it.startsWith("event=TASK_COMPLETE ") }
                assertTrue(completion.contains("outcome=${if (count < 0) "ERROR" else "SUCCESS"} "))
                assertTrue(completion.contains("sdkCount=$count "))
                if (count < 0) {
                    assertTrue(completion.contains("boxes=N/A"))
                    assertTrue(diagnostics.any { it.startsWith("event=DETECT_ERROR ") })
                } else {
                    assertFalse(diagnostics.any { it.startsWith("event=DETECT_ERROR ") })
                    assertTrue(completion.contains("error=none"))
                }
            }
        } finally { detector.close(); bitmap.recycle() }
    }

    @Test fun throwingDiagnosticsCannotChangeSdkResultsOrDuplicateP0Completion() = runBlocking {
        val bitmap = inputBitmap()
        var sdkFails = false
        val detector = MlKitObjectDetector(FakeDetector {
            TaskCompletionSource<List<DetectedObject>>().apply {
                if (sdkFails) setException(IllegalStateException("actual SDK failure"))
                else setResult(listOf(DetectedObject(Rect(10, 20, 80, 90), 23, emptyList())))
            }.task
        })
        try {
            for (fails in listOf(false, true)) {
                sdkFails = fails
                val diagnosticsAttempted = mutableListOf<String>()
                val events = ConcurrentLinkedQueue<FrameLatencyEvent>()
                val results = detector.detect(bitmap, latency(events), 90L) { line ->
                    diagnosticsAttempted.add(line)
                    throw IllegalArgumentException("diagnostic sink failed")
                }
                assertEquals(if (fails) 0 else 1, results.size)
                if (!fails) assertEquals(23, results.single().trackingId)
                assertTrue(diagnosticsAttempted.any { it.startsWith("event=PROCESS_REQUEST ") })
                assertTrue(diagnosticsAttempted.any { it.startsWith("event=TASK_COMPLETE ") })
                assertCompletion(events, if (fails) "ERROR" else "SUCCESS")
                assertFalse(bitmap.isRecycled)
            }
        } finally { detector.close(); bitmap.recycle() }
    }

    @Test fun repeatedProcessFailuresAndSdkCancellationReleaseLeaseAndAllowNextSuccess() = runBlocking {
        val source = inputBitmap()
        val buffer = MlKitInputBuffer()
        val failed = TaskCompletionSource<List<DetectedObject>>().apply {
            setException(IllegalStateException("scripted asynchronous failure"))
        }.task
        val cancellation = CancellationTokenSource()
        val cancelled = TaskCompletionSource<List<DetectedObject>>(cancellation.token).task
        cancellation.cancel()
        val succeeded = TaskCompletionSource<List<DetectedObject>>().apply {
            setResult(listOf(DetectedObject(Rect(10, 20, 100, 120), 17, emptyList())))
        }.task
        var requests = 0
        val detector = MlKitObjectDetector(FakeDetector {
            when (requests++ % 4) {
                0 -> throw IllegalStateException("scripted synchronous failure")
                1 -> failed
                2 -> cancelled
                else -> succeeded
            }
        })
        var retained: Bitmap? = null
        try {
            repeat(8) { attempt ->
                val lease = buffer.acquire(source, FrameRoi(0, 0, 640, 360))
                retained?.let { assertSame(it, lease.bitmap) }
                retained = lease.bitmap
                val inFlight = AtomicBoolean(true)
                val events = ConcurrentLinkedQueue<FrameLatencyEvent>()
                var results: List<MlKitObjectDetection>? = null
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    results = detector.detect(lease.bitmap, latency(events), 90L)
                }
                job.invokeOnCompletion { lease.close(); inFlight.set(false) }
                try {
                    withTimeout(5_000) { job.join() }
                    assertFalse(inFlight.get())
                    assertFalse(lease.bitmap.isRecycled)
                    val stage = attempt % 4
                    val event = events.single() as FrameLatencyEvent.MlKit
                    assertEquals(listOf("ERROR", "ERROR", "CANCELLED", "SUCCESS")[stage], event.outcome)
                    assertEquals(if (stage == 0) "AWAIT_RETURN_FALLBACK" else "TASK_COMPLETION_LISTENER",
                        event.completionBasis)
                    assertEquals(if (stage == 3) 1 else 0, checkNotNull(results).size)
                    if (stage == 3) assertEquals(17, checkNotNull(results).single().trackingId)
                } finally { lease.close() }
            }
            assertEquals(8, requests)
        } finally { detector.close(); buffer.close(); source.recycle() }
        assertTrue(checkNotNull(retained).isRecycled)
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
        var closeCalls = 0
        override fun process(image: InputImage) = onProcess(image)
        override fun process(image: MlImage): Task<List<DetectedObject>> = error("Unexpected MlImage input")
        override fun process(bitmap: Bitmap, rotation: Int): Task<List<DetectedObject>> = error("Unexpected Bitmap input")
        override fun process(image: Image, rotation: Int): Task<List<DetectedObject>> = error("Unexpected Image input")
        override fun process(image: Image, rotation: Int, matrix: Matrix): Task<List<DetectedObject>> = error("Unexpected Image input")
        override fun process(buffer: ByteBuffer, width: Int, height: Int, rotation: Int, format: Int): Task<List<DetectedObject>> =
            error("Unexpected ByteBuffer input")
        override fun getDetectorType() = 5
        override fun close() { closed = true; closeCalls++ }
    }
}
