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
import com.samin.objectdetection.metrics.FrameLatencyEvent
import com.samin.objectdetection.metrics.FrameLatencyRecorder
import java.util.concurrent.Executor

data class MlKitObjectDetection(
    val boundingBox: Rect,
    val trackingId: Int?
)

class MlKitObjectDetector {

    private val detector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .build()
    )

    suspend fun detect(bitmap: Bitmap, latency: FrameLatencyRecorder? = null,
        dispatchedElapsedNs: Long = -1L): List<MlKitObjectDetection> {
        val start = System.currentTimeMillis()
        var requestedNs = -1L
        var completionRegistered = false
        var outcome = "ERROR"
        return try {
            val inputStartNs = SystemClock.elapsedRealtimeNanos()
            val image = trace("MLKit.InputImage") { InputImage.fromBitmap(bitmap, 0) }
            val inputImageNs = SystemClock.elapsedRealtimeNanos() - inputStartNs
            requestedNs = latency?.nowNs() ?: -1L
            val task = detector.process(image)
            if (latency != null) {
                val requestForCallbackNs = requestedNs
                // Observe completion before await's dispatcher resumes. Callback work is bounded enqueue only.
                completionRegistered = runCatching {
                    task.addOnCompleteListener(DIRECT_EXECUTOR) { completed ->
                        latency.record(FrameLatencyEvent.MlKit(dispatchedElapsedNs, requestForCallbackNs, latency.nowNs(),
                            if (completed.isSuccessful) "SUCCESS" else if (completed.isCanceled) "CANCELLED" else "ERROR",
                            "TASK_COMPLETION_LISTENER"))
                    }
                    true
                }.getOrDefault(false)
            }
            task.await().map { detectedObject ->
                MlKitObjectDetection(
                    boundingBox = Rect(detectedObject.boundingBox),
                    trackingId = detectedObject.trackingId
                )
            }.also { results ->
                outcome = "SUCCESS"
                Log.d(TAG, "detections=${results.size}, time=${System.currentTimeMillis() - start}ms inputImageNs=$inputImageNs")
            }
        } catch (e: Exception) {
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

    companion object {
        private const val TAG = "MlKitObjectDetector"
        private val DIRECT_EXECUTOR = Executor { it.run() }
    }
}
