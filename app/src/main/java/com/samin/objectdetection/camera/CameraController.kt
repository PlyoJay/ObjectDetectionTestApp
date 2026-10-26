package com.samin.objectdetection.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.camera2.interop.Camera2Interop
import com.samin.objectdetection.distance.AnalysisCameraGeometry
import com.samin.objectdetection.distance.Camera2DistanceGeometry
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.samin.objectdetection.metrics.FrameLatencyRecorder
import com.samin.objectdetection.metrics.finishAnalyzerFrame
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture

data class CameraFrameTiming(
    val frameTimestampMs: Long,
    val analyzerReceivedMs: Long,
    val bitmapConversionStartMs: Long,
    val bitmapConversionEndMs: Long,
    val frameId: Long = 0L,
    val cameraTimestampNs: Long = 0L,
    val analyzerReceivedElapsedNs: Long = 0L,
    val captureTimestampReliable: Boolean = false,
    val bitmapConversionDurationMs: Long = bitmapConversionEndMs - bitmapConversionStartMs,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val cameraToBitmapNs: Long = -1L,
    val rotationNs: Long = -1L,
    val latency: FrameLatencyRecorder? = null,
    val distanceGeometry: AnalysisCameraGeometry? = null
) {
    val cameraToAnalyzerMs: Long get() = if (captureTimestampReliable) (analyzerReceivedMs - frameTimestampMs).coerceAtLeast(0L) else -1L
    val bitmapConversionMs: Long get() =
        bitmapConversionDurationMs.coerceAtLeast(0L)
}

class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val detectIntervalMs: Long,
    private val enableDiagnostics: Boolean,
    private val targetWidth: Int = TARGET_WIDTH,
    private val targetHeight: Int = TARGET_HEIGHT,
    private val cameraPreviewEnabled: Boolean = true,
    private val listener: Listener,
    private val distanceMetadataEnabled: Boolean = false
) : AutoCloseable {

    interface Listener {
        fun onAnalyzerStarted(timing: CameraFrameTiming): FrameLatencyRecorder? = null
        fun onFrameReceived(frameTimestampMs: Long, analyzerReceivedMs: Long)
        fun onFrameSkipped(reason: SkipReason, skippedFrameCount: Long)
        fun onFrame(bitmap: Bitmap, timing: CameraFrameTiming, rotationDegrees: Int)
        fun onCameraStarted()
        fun onCameraError(error: Throwable)
        fun onFrameError(error: Throwable, timing: CameraFrameTiming? = null)
    }

    enum class SkipReason { INTERVAL, BITMAP_CONVERSION }

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var lastDetectionStartTimeMs = 0L
    private var skippedFrameCount = 0L
    private var loggedCameraShape = false
    private var sequence = 0L
    @Volatile private var closed = false
    @Volatile private var realtimeTimestamps = false
    private val stopped = CompletableFuture<Unit>()
    private val distanceGeometry = Camera2DistanceGeometry()

    fun start() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (closed) return@addListener
            try {
                provider = providerFuture.get().also(::bindUseCases)
                listener.onCameraStarted()
            } catch (error: Exception) {
                listener.onCameraError(error)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun bindUseCases(cameraProvider: ProcessCameraProvider) {
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(targetWidth, targetHeight),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()
        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        if (distanceMetadataEnabled) {
            Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(distanceGeometry.captureCallback)
        }
        val analysis = analysisBuilder.build()

        analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
            val analyzerReceivedMs = System.currentTimeMillis()
            val receivedNs = SystemClock.elapsedRealtimeNanos()
            val cameraNs = imageProxy.imageInfo.timestamp
            val reliable = realtimeTimestamps && cameraNs > 0 && cameraNs <= receivedNs
            val frameTimestampMs = if (reliable) analyzerReceivedMs - (receivedNs - cameraNs) / 1_000_000L else analyzerReceivedMs
            val frameId = ++sequence
            var timing = CameraFrameTiming(frameTimestampMs, analyzerReceivedMs, analyzerReceivedMs,
                analyzerReceivedMs, frameId, cameraNs, receivedNs, reliable,
                frameWidth = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.width else imageProxy.height,
                frameHeight = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.height else imageProxy.width)
            var outcome = "CLOSED"
            try {
                listener.onAnalyzerStarted(timing)?.let { timing = timing.copy(latency = it) }
                if (closed) return@setAnalyzer
                listener.onFrameReceived(frameTimestampMs, analyzerReceivedMs)
                val receivedElapsedMs = receivedNs / 1_000_000L
                if (receivedElapsedMs - lastDetectionStartTimeMs < detectIntervalMs) {
                    outcome = "INTERVAL_SKIPPED"
                    recordSkipped(SkipReason.INTERVAL)
                    return@setAnalyzer
                }
                lastDetectionStartTimeMs = receivedElapsedMs
                if (!loggedCameraShape || enableDiagnostics) {
                    Log.i(TAG, "[CAMERA_SHAPE] requested=${targetWidth}x$targetHeight " +
                        "actual=${imageProxy.width}x${imageProxy.height} format=${imageProxy.format} " +
                        "rotationDegrees=${imageProxy.imageInfo.rotationDegrees} cropRect=${imageProxy.cropRect}")
                    loggedCameraShape = true
                }
                val bitmapConversionStartMs = System.currentTimeMillis()
                val conversionStartNs = SystemClock.elapsedRealtimeNanos()
                var cameraToBitmapNs = -1L
                var rotationNs = -1L
                val bitmap = imageProxy.toBitmapSafe(enableDiagnostics) { conversion, rotation ->
                    cameraToBitmapNs = conversion
                    rotationNs = rotation
                }
                val bitmapConversionEndMs = System.currentTimeMillis()
                timing = timing.copy(bitmapConversionStartMs = bitmapConversionStartMs,
                    bitmapConversionEndMs = bitmapConversionEndMs,
                    bitmapConversionDurationMs = (SystemClock.elapsedRealtimeNanos() - conversionStartNs) / 1_000_000L,
                    cameraToBitmapNs = cameraToBitmapNs, rotationNs = rotationNs,
                    distanceGeometry = distanceGeometry.geometry(imageProxy, distanceMetadataEnabled))
                if (bitmap == null) {
                    outcome = "BITMAP_UNAVAILABLE"
                    recordSkipped(SkipReason.BITMAP_CONVERSION)
                    listener.onFrameError(IllegalStateException("Camera bitmap conversion failed; see ImageProxyExt cause"), timing)
                    return@setAnalyzer
                }
                try {
                    listener.onFrame(
                        bitmap,
                        timing,
                        imageProxy.imageInfo.rotationDegrees
                    )
                    outcome = "DELIVERED"
                } finally {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
            } catch (error: Exception) {
                outcome = "ERROR"
                if (error is BitmapConversionException) recordSkipped(SkipReason.BITMAP_CONVERSION)
                listener.onFrameError(error, timing)
            } finally {
                finishAnalyzerFrame(timing.latency, outcome) { imageProxy.close() }
            }
        }

        cameraProvider.unbindAll()
        // Keep the same analyzer/format/backpressure/resolution policy in both modes.
        // OFF creates no Preview or surface request; ImageAnalysis owns the camera stream.
        val preview = if (cameraPreviewEnabled) {
            Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        } else null
        val camera = if (preview != null) {
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        } else {
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }
        Log.i(TAG, "[CAMERA_USE_CASES] cameraPreviewEnabled=$cameraPreviewEnabled imageAnalysis=true " +
            "requested=${targetWidth}x$targetHeight actual=${analysis.resolutionInfo?.resolution} " +
            "analysisCrop=${analysis.resolutionInfo?.cropRect} previewResolution=${preview?.resolutionInfo?.resolution} " +
            "previewCrop=${preview?.resolutionInfo?.cropRect}")
        realtimeTimestamps = runCatching {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            distanceGeometry.bind(info)
            info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        }.getOrDefault(false)
    }

    private fun recordSkipped(reason: SkipReason) {
        skippedFrameCount++
        listener.onFrameSkipped(reason, skippedFrameCount)
    }

    /** Called on main for unbind; completion is delivered only after the analyzer really exits. */
    fun stopAsync(): CompletableFuture<Unit> {
        if (closed) return stopped
        closed = true
        runCatching { provider?.unbindAll() }.onFailure { Log.e(TAG, "camera unbind failed", it) }
        provider = null
        shutdownAnalyzer(analyzerExecutor).whenComplete { _, error ->
            if (error == null) stopped.complete(Unit) else stopped.completeExceptionally(error)
        }
        return stopped
    }

    override fun close() {
        stopAsync()
    }

    companion object {
        const val TARGET_WIDTH = 1280
        const val TARGET_HEIGHT = 720
        private const val TAG = "CameraController"
    }
}
