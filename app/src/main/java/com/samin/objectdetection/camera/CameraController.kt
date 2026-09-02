package com.samin.objectdetection.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val detectIntervalMs: Long,
    private val enableDiagnostics: Boolean,
    private val listener: Listener
) : AutoCloseable {

    interface Listener {
        fun onFrameReceived(timestampMs: Long, isProcessing: Boolean)
        fun onFrameSkipped(reason: SkipReason, skippedFrameCount: Long)
        fun onFrame(bitmap: Bitmap, timestampMs: Long, rotationDegrees: Int)
        fun onCameraStarted()
        fun onCameraError(error: Throwable)
        fun onFrameError(error: Throwable)
    }

    enum class SkipReason { INTERVAL, BUSY, BITMAP_CONVERSION }

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val isProcessing = AtomicBoolean(false)
    private var provider: ProcessCameraProvider? = null
    private var lastDetectionStartTimeMs = 0L
    private var skippedFrameCount = 0L

    fun start() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                provider = providerFuture.get().also(::bindUseCases)
                listener.onCameraStarted()
            } catch (error: Exception) {
                listener.onCameraError(error)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindUseCases(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder()
            .setTargetResolution(TARGET_RESOLUTION)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(TARGET_RESOLUTION)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
            val timestampMs = System.currentTimeMillis()
            var detectionStarted = false
            listener.onFrameReceived(timestampMs, isProcessing.get())
            try {
                if (timestampMs - lastDetectionStartTimeMs < detectIntervalMs) {
                    recordSkipped(SkipReason.INTERVAL)
                    return@setAnalyzer
                }
                if (!isProcessing.compareAndSet(false, true)) {
                    recordSkipped(SkipReason.BUSY)
                    return@setAnalyzer
                }
                detectionStarted = true
                lastDetectionStartTimeMs = timestampMs
                val bitmap = imageProxy.toBitmapSafe(enableDiagnostics)
                if (bitmap == null) {
                    recordSkipped(SkipReason.BITMAP_CONVERSION)
                    return@setAnalyzer
                }
                try {
                    listener.onFrame(bitmap, timestampMs, imageProxy.imageInfo.rotationDegrees)
                } finally {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
            } catch (error: Exception) {
                listener.onFrameError(error)
            } finally {
                if (detectionStarted) {
                    isProcessing.set(false)
                }
                imageProxy.close()
            }
        }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis
        )
    }

    private fun recordSkipped(reason: SkipReason) {
        skippedFrameCount++
        listener.onFrameSkipped(reason, skippedFrameCount)
    }

    override fun close() {
        provider?.unbindAll()
        provider = null
        analyzerExecutor.shutdown()
    }

    private companion object {
        val TARGET_RESOLUTION = Size(1280, 720)
    }
}
