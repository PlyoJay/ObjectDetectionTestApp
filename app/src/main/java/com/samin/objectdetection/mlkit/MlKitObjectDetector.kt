package com.samin.objectdetection.mlkit

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.tasks.await

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

    suspend fun detect(bitmap: Bitmap): List<MlKitObjectDetection> {
        val start = System.currentTimeMillis()
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            detector.process(image).await().map { detectedObject ->
                MlKitObjectDetection(
                    boundingBox = Rect(detectedObject.boundingBox),
                    trackingId = detectedObject.trackingId
                )
            }.also { results ->
                Log.d(TAG, "detections=${results.size}, time=${System.currentTimeMillis() - start}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "detect error", e)
            emptyList()
        }
    }

    fun close() {
        detector.close()
    }

    companion object {
        private const val TAG = "MlKitObjectDetector"
    }
}
