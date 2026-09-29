package com.samin.objectdetection.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class CameraFrameTiming(
    val frameTimestampMs: Long,
    val analyzerReceivedMs: Long,
    val bitmapConversionStartMs: Long,
    val bitmapConversionEndMs: Long
) {
    val cameraToAnalyzerMs: Long get() = (analyzerReceivedMs - frameTimestampMs).coerceAtLeast(0L)
    val bitmapConversionMs: Long get() =
        (bitmapConversionEndMs - bitmapConversionStartMs).coerceAtLeast(0L)
}

class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val detectIntervalMs: Long,
    private val enableDiagnostics: Boolean,
    private val targetWidth: Int = TARGET_WIDTH,
    private val targetHeight: Int = TARGET_HEIGHT,
    private val listener: Listener
) : AutoCloseable {

    interface Listener {
        fun onFrameReceived(frameTimestampMs: Long, analyzerReceivedMs: Long)
        fun onFrameSkipped(reason: SkipReason, skippedFrameCount: Long)
        fun onFrame(bitmap: Bitmap, timing: CameraFrameTiming, rotationDegrees: Int)
        fun onCameraStarted()
        fun onCameraError(error: Throwable)
        fun onFrameError(error: Throwable)
    }

    enum class SkipReason { INTERVAL, BITMAP_CONVERSION }

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
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
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(targetWidth, targetHeight),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
            val analyzerReceivedMs = System.currentTimeMillis()
            val frameTimestampMs = estimateCaptureWallTimeMs(imageProxy.imageInfo.timestamp, analyzerReceivedMs)
            listener.onFrameReceived(frameTimestampMs, analyzerReceivedMs)
            try {
                if (analyzerReceivedMs - lastDetectionStartTimeMs < detectIntervalMs) {
                    recordSkipped(SkipReason.INTERVAL)
                    return@setAnalyzer
                }
                lastDetectionStartTimeMs = analyzerReceivedMs
                val bitmapConversionStartMs = System.currentTimeMillis()
                val bitmap = imageProxy.toBitmapSafe(enableDiagnostics)
                val bitmapConversionEndMs = System.currentTimeMillis()
                if (bitmap == null) {
                    recordSkipped(SkipReason.BITMAP_CONVERSION)
                    return@setAnalyzer
                }
                try {
                    listener.onFrame(
                        bitmap,
                        CameraFrameTiming(
                            frameTimestampMs = frameTimestampMs,
                            analyzerReceivedMs = analyzerReceivedMs,
                            bitmapConversionStartMs = bitmapConversionStartMs,
                            bitmapConversionEndMs = bitmapConversionEndMs
                        ),
                        imageProxy.imageInfo.rotationDegrees
                    )
                } finally {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
            } catch (error: Exception) {
                listener.onFrameError(error)
            } finally {
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

    private fun estimateCaptureWallTimeMs(cameraTimestampNs: Long, nowWallMs: Long): Long {
        if (cameraTimestampNs <= 0L) return nowWallMs
        val ageNs = (SystemClock.elapsedRealtimeNanos() - cameraTimestampNs).coerceAtLeast(0L)
        // Guard against a device using a different timestamp source.
        val ageMs = (ageNs / 1_000_000L).coerceAtMost(MAX_REASONABLE_FRAME_AGE_MS)
        return nowWallMs - ageMs
    }

    override fun close() {
        provider?.unbindAll()
        provider = null
        analyzerExecutor.shutdown()
        try {
            if (!analyzerExecutor.awaitTermination(ANALYZER_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Analyzer did not finish before detector shutdown timeout")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val TARGET_WIDTH = 1280
        const val TARGET_HEIGHT = 720
        const val MAX_REASONABLE_FRAME_AGE_MS = 10_000L
        private const val ANALYZER_SHUTDOWN_TIMEOUT_SECONDS = 10L
        private const val TAG = "CameraController"
    }
}
