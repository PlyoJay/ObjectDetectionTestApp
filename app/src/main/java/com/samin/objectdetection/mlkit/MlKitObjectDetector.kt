package com.samin.objectdetection.mlkit

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.os.SystemClock
import androidx.core.os.trace
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.samin.objectdetection.metrics.FrameLatencyEvent
import com.samin.objectdetection.metrics.FrameLatencyRecorder
import java.util.concurrent.Executor

data class MlKitObjectDetection(
    val boundingBox: Rect,
    val trackingId: Int?
)

class MlKitObjectDetector internal constructor(private val detector: ObjectDetector) {
    constructor() : this(ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .build()
    ))

    suspend fun detect(bitmap: Bitmap, latency: FrameLatencyRecorder? = null,
        dispatchedElapsedNs: Long = -1L, diagnosticLog: ((String) -> Unit)? = null): List<MlKitObjectDetection> {
        val start = System.currentTimeMillis()
        var requestedNs = -1L
        var completionRegistered = false
        var outcome = "ERROR"
        return try {
            val inputStartNs = SystemClock.elapsedRealtimeNanos()
            val image = trace("MLKit.InputImage") { InputImage.fromBitmap(bitmap, 0) }
            val inputImageNs = SystemClock.elapsedRealtimeNanos() - inputStartNs
            requestedNs = latency?.nowNs() ?: -1L
            diagnosticLog?.let { log -> diagnose(log) {
                "event=PROCESS_REQUEST input=${bitmap.width}x${bitmap.height} rotation=0 mode=STREAM_MODE"
            } }
            val task = detector.process(image)
            if (latency != null || diagnosticLog != null) {
                val requestForCallbackNs = requestedNs
                // P0 observes completion before await resumes. Text diagnostics are opt-in and bounded.
                completionRegistered = runCatching {
                    task.addOnCompleteListener(DIRECT_EXECUTOR) { completed ->
                        latency?.record(FrameLatencyEvent.MlKit(dispatchedElapsedNs, requestForCallbackNs, latency.nowNs(),
                            if (completed.isSuccessful) "SUCCESS" else if (completed.isCanceled) "CANCELLED" else "ERROR",
                            "TASK_COMPLETION_LISTENER"))
                        diagnosticLog?.let { log -> diagnose(log) { "event=TASK_COMPLETE outcome=" +
                            (if (completed.isSuccessful) "SUCCESS" else if (completed.isCanceled) "CANCELLED" else "ERROR") +
                            " sdkCount=${if (completed.isSuccessful) completed.result.size else -1} " +
                            "boxes=${if (completed.isSuccessful) completed.result.take(5).joinToString { it.boundingBox.toShortString() } else "N/A"} " +
                            "error=${completed.exception?.javaClass?.simpleName ?: "none"}" } }
                    }
                    true
                }.getOrDefault(false)
            }
            // await() alone stops waiting on coroutine cancellation without stopping ML Kit.
            // Keep the caller's input lease alive until the Task reaches a terminal state.
            withContext(NonCancellable) { task.await() }.map { detectedObject ->
                MlKitObjectDetection(
                    boundingBox = Rect(detectedObject.boundingBox),
                    trackingId = detectedObject.trackingId
                )
            }.also { results ->
                outcome = "SUCCESS"
                Log.d(TAG, "detections=${results.size}, time=${System.currentTimeMillis() - start}ms inputImageNs=$inputImageNs")
            }
        } catch (e: Exception) {
            diagnosticLog?.let { log -> diagnose(log) {
                "event=DETECT_ERROR error=${e.javaClass.simpleName} detail=${e.message?.replace('\n', ' ')?.take(160)}"
            } }
            Log.e(TAG, "detect error", e)
            emptyList()
        } finally {
            if (!completionRegistered) latency?.let {
                it.record(FrameLatencyEvent.MlKit(dispatchedElapsedNs, requestedNs, it.nowNs(), outcome,
                    if (requestedNs < 0) "BEFORE_PROCESS_REQUEST" else "AWAIT_RETURN_FALLBACK"))
            }
        }
    }

    fun close() {
        detector.close()
    }

    private inline fun diagnose(log: (String) -> Unit, detail: () -> String) {
        try { log(detail()) } catch (_: Exception) { }
    }

    companion object {
        private const val TAG = "MlKitObjectDetector"
        private val DIRECT_EXECUTOR = Executor { it.run() }
    }
}
