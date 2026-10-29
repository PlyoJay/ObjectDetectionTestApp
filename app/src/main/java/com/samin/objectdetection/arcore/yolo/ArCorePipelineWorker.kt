package com.samin.objectdetection.arcore.yolo

import android.graphics.Bitmap
import android.os.SystemClock
import com.samin.objectdetection.camera.CameraController
import com.samin.objectdetection.camera.CameraFrameTiming
import com.samin.objectdetection.camera.rotate
import com.samin.objectdetection.distance.DepthInputMode
import com.samin.objectdetection.metrics.finishAnalyzerFrame
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Feeds the existing main analyzer callback. Owns no detector or alternative warning pipeline. */
class ArCorePipelineWorker(intervalMs: Long, private val mode: DepthInputMode,
    private val nextFrameId: () -> Long, private val currentEpoch: () -> Long,
    private val listener: CameraController.Listener, private val diagnostics: Boolean = false,
    private val groundTruth: com.samin.objectdetection.distance.DepthGroundTruth? = null) : ArFrameConsumer {
    private val executor = Executors.newSingleThreadExecutor()
    private val gate = ArYoloWorkGate(maxOf(250L, intervalMs) * 1_000_000)
    private val lock = Any()
    private val closed = AtomicBoolean()
    private val stopped = CompletableFuture<Unit>()
    private val sampler = ArObjectSurfaceDepthSampler()
    private val smoother = ArObjectDepthSmoother()
    private var previousEpoch = Long.MIN_VALUE
    @Volatile var status = "YOLO waiting"
        private set

    override fun acquire(nowNs: Long) = !closed.get() && gate.acquire(nowNs)
    override fun cancelCapture() { gate.release() }
    override fun submit(capture: ArYoloCapture) = synchronized(lock) {
        if (closed.get()) { gate.release(); return@synchronized }
        executor.execute {
            var raw: Bitmap? = null
            var upright: Bitmap? = null
            var timing: CameraFrameTiming? = null
            var outcome = "STALE_OR_PAUSED"
            try {
                if (closed.get() || capture.epoch != currentEpoch()) return@execute
                status = "YOLO running"
                if (previousEpoch != capture.epoch) { smoother.reset(); previousEpoch = capture.epoch }
                val wall = System.currentTimeMillis()
                var value = CameraFrameTiming(capture.capturedWallMs, capture.capturedWallMs, wall, wall,
                    frameId = nextFrameId(), cameraTimestampNs = capture.cameraTimestampNs,
                    analyzerReceivedElapsedNs = capture.capturedElapsedNs, captureTimestampReliable = false,
                    cameraBackend = "ARCORE")
                listener.onAnalyzerStarted(value)?.let { value = value.copy(latency = it) }
                timing = value
                listener.onFrameReceived(value.frameTimestampMs, value.analyzerReceivedMs)
                val started = SystemClock.elapsedRealtimeNanos()
                raw = Bitmap.createBitmap(capture.image.toArgb(), capture.image.width, capture.image.height, Bitmap.Config.ARGB_8888)
                val converted = SystemClock.elapsedRealtimeNanos()
                upright = raw.rotate(capture.mapping.rotation.toFloat())
                val rotated = SystemClock.elapsedRealtimeNanos()
                val context = ArCoreFrameDepthContext(capture, mode, sampler, smoother,
                    SystemClock::elapsedRealtimeNanos, currentEpoch, diagnostics, groundTruth)
                value = value.copy(bitmapConversionEndMs = System.currentTimeMillis(),
                    bitmapConversionDurationMs = (rotated - started) / 1_000_000,
                    cameraToBitmapNs = converted - started, rotationNs = rotated - converted,
                    frameWidth = upright.width, frameHeight = upright.height, depthContext = context)
                timing = value
                if (!closed.get() && context.isCurrent()) {
                    listener.onFrame(upright, value, capture.mapping.rotation)
                    outcome = "DELIVERED"
                }
                status = "YOLO waiting"
            } catch (error: Exception) {
                outcome = "ERROR"; status = "YOLO 오류: ${error.message ?: error.javaClass.simpleName}"
                listener.onFrameError(error, timing)
            } finally {
                try {
                    finishAnalyzerFrame(timing?.latency, outcome) {
                        if (upright != null && upright !== raw && !upright.isRecycled) upright.recycle()
                        if (raw != null && !raw.isRecycled) raw.recycle()
                    }
                } finally { gate.release() }
            }
        }
    }
    fun close(): CompletableFuture<Unit> = synchronized(lock) {
        if (closed.compareAndSet(false, true)) {
            gate.close()
            executor.execute { smoother.reset(); stopped.complete(Unit) }
            executor.shutdown()
        }
        stopped
    }
}
