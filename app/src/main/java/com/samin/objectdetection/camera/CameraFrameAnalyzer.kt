package com.samin.objectdetection.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.samin.objectdetection.detector.ObjectDetector
import com.samin.objectdetection.detector.mapToOriginalFrame
import com.samin.objectdetection.dto.DetectionEvent
import com.samin.objectdetection.dto.RoiInfo
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class CameraFrameAnalyzer(
    private val context: Context,
    private val config: DetectionConfig,
    private val detector: ObjectDetector
) : ImageAnalysis.Analyzer {

    private var lastSaveTime = 0L
    private val isDetecting = AtomicBoolean(false)
    private var skippedFrameCount = 0L

    init {
        clearDebugRoiDirectory()
    }

    private fun clearDebugRoiDirectory() {
        val dir = File(context.getExternalFilesDir(null), "debug_roi")

        if (!dir.exists()) return

        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.delete()
            }
        }

        Log.d(TAG, "debug_roi directory cleared")
    }

    override fun analyze(imageProxy: ImageProxy) {
        Log.d(TAG, "analyze called: ${imageProxy.width} x ${imageProxy.height}")
        val frameReceivedTimeMs = System.currentTimeMillis()
        var detectionStarted = false

        try {
            val now = frameReceivedTimeMs
            Log.d(
                DETECTION_TIMING_TAG,
                "frameReceived=$frameReceivedTimeMs isDetecting=${isDetecting.get()}"
            )

            if (now - lastSaveTime < config.detectIntervalMs) {
                skippedFrameCount++
                Log.d(
                    DETECTION_TIMING_TAG,
                    "skipFrameByInterval skipped=$skippedFrameCount intervalMs=${config.detectIntervalMs}"
                )
                return
            }

            if (!isDetecting.compareAndSet(false, true)) {
                skippedFrameCount++
                Log.d(
                    DETECTION_TIMING_TAG,
                    "skipFrameByDetecting skipped=$skippedFrameCount isDetecting=${isDetecting.get()}"
                )
                return
            }
            detectionStarted = true

            lastSaveTime = now
            val detectionStartTimeMs = System.currentTimeMillis()
            Log.d(
                DETECTION_TIMING_TAG,
                "detectionStart=$detectionStartTimeMs frameReceived=$frameReceivedTimeMs isDetecting=${isDetecting.get()}"
            )

            val bitmap = imageProxy.toBitmapSafe()
            if (bitmap == null) {
                Log.e(TAG, "bitmap is null")
                return
            }

            Log.d(TAG, "bitmap created: ${bitmap.width} x ${bitmap.height}")

            val roi: Rect = RoiCalculator.calculate(
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                config = config
            )

            Log.d(TAG, "roi: left=${roi.left}, top=${roi.top}, width=${roi.width()}, height=${roi.height()}")

            val cropped = Bitmap.createBitmap(
                bitmap,
                roi.left,
                roi.top,
                roi.width(),
                roi.height()
            )

            Log.d(TAG, "cropped created: ${cropped.width} x ${cropped.height}")

            val resized = Bitmap.createScaledBitmap(
                cropped,
                config.inputSize,
                config.inputSize,
                true
            )

            Log.d(TAG, "resized created: ${resized.width} x ${resized.height}")

            if (config.saveDebugImage) {
                saveBitmap(resized)
            }

            val results = detector.detect(resized)
            val detectionEndTimeMs = System.currentTimeMillis()
            Log.d(
                DETECTION_TIMING_TAG,
                "detectionEnd=$detectionEndTimeMs inference=${detectionEndTimeMs - detectionStartTimeMs}ms skipped=$skippedFrameCount"
            )

            results.forEach {
                val mapped = it.mapToOriginalFrame(roi, modelInputSize = config.inputSize)

                Log.d(
                    "Detection",
                    "model=${it.label}, conf=${it.confidence}, modelBox=${it.left},${it.top},${it.right},${it.bottom}"
                )

                Log.d(
                    "Detection",
                    "original=${mapped.label}, conf=${mapped.confidence}, frameBox=${mapped.left},${mapped.top},${mapped.right},${mapped.bottom}"
                )
            }

            val mappedResults = results.map { it.mapToOriginalFrame(roi, modelInputSize = config.inputSize) }

            val event = DetectionEvent(
                deviceId = "GOTORO-001",
                timestamp = System.currentTimeMillis(),
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                roi = RoiInfo(
                    left = roi.left,
                    top = roi.top,
                    width = roi.width(),
                    height = roi.height()
                ),
                detections = mappedResults
            )

            Log.d("DetectionEvent", event.toString())

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (detectionStarted) {
                isDetecting.set(false)
            }
            imageProxy.close()
        }
    }

    private fun saveBitmap(bitmap: Bitmap) {
        try {
            val dir = File(context.getExternalFilesDir(null), "debug_roi")
            if (!dir.exists()) {
                val created = dir.mkdirs()
                Log.d(TAG, "dir created: $created, path=${dir.absolutePath}")
            }

            val file = File(dir, "roi_${System.currentTimeMillis()}.jpg")

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            Log.d(TAG, "saved: ${file.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "saveBitmap error", e)
        }
    }

    companion object {
        private const val TAG = "CameraFrameAnalyzer"
        private const val DETECTION_TIMING_TAG = "DetectionTiming"
    }
}
