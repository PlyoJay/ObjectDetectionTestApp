package com.samin.objectdetection.arcore.yolo

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.samin.objectdetection.camera.rotate
import com.samin.objectdetection.detector.VisionStyleYoloDetector
import com.samin.objectdetection.settings.AppSettings
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class ArYoloCapture(val image: ArYuvSnapshot, val mapping: ArImageMapping, val depth: ArDepthPlane?,
    val depthStatus: String, val frameTimestampNs: Long, val cameraTimestampNs: Long,
    val capturedElapsedNs: Long, val capturedWallMs: Long, val epoch: Long, val tracking: String)

class ArYoloWorker(context: Context, settings: AppSettings, private val currentEpoch: () -> Long,
    private val onResult: (ArYoloFrame) -> Unit) {
    private val executor = Executors.newSingleThreadExecutor()
    private val submissionLock = Any()
    private val gate = ArYoloWorkGate()
    private val closed = AtomicBoolean(false)
    private val stopped = CompletableFuture<Unit>()
    private val smoother = ArObjectDepthSmoother()
    private var previousEpoch = Long.MIN_VALUE
    private var detector: VisionStyleYoloDetector? = null
    @Volatile private var ready = false
    @Volatile var status = "YOLO loading"
        private set

    init {
        val app = context.applicationContext
        executor.execute {
            try {
                detector = VisionStyleYoloDetector(app, settings.yolo.model.assetName,
                    settings.yolo.confidenceThreshold, settings.yolo.nmsThreshold,
                    settings.filters.sizeFilterMode, settings.yolo.resizeMode, settings.yolo.interpreterThreadCount,
                    settings.yolo.maxCandidates, useXnnpack = settings.yolo.useXnnpack)
                ready = !closed.get()
                status = "YOLO waiting · ${settings.yolo.model.classLabels.size} classes"
            } catch (error: Exception) { status = "YOLO 초기화 실패: ${error.message}" }
        }
    }

    fun acquire(nowNs: Long): Boolean = ready && !closed.get() && gate.acquire(nowNs)
    fun cancelCapture() { gate.release() }

    fun submit(capture: ArYoloCapture) = synchronized(submissionLock) {
        if (closed.get()) { gate.release(); return@synchronized }
        executor.execute {
            var raw: Bitmap? = null
            var upright: Bitmap? = null
            try {
                if (capture.epoch != currentEpoch()) return@execute
                status = "YOLO running"
                raw = Bitmap.createBitmap(capture.image.toArgb(), capture.image.width, capture.image.height, Bitmap.Config.ARGB_8888)
                upright = raw.rotate(capture.mapping.rotation.toFloat())
                val started = SystemClock.elapsedRealtimeNanos()
                val detections = requireNotNull(detector).detect(upright)
                val inferenceMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
                if (previousEpoch != capture.epoch) { smoother.reset(); previousEpoch = capture.epoch }
                val now = SystemClock.elapsedRealtimeNanos()
                val lag = capture.depth?.let { capture.frameTimestampNs - it.timestampNs }
                val expires = if (lag != null && lag >= 0) capture.capturedElapsedNs + ArObjectDepthSampler.MAX_DEPTH_AGE_NS - lag else capture.capturedElapsedNs
                val objects = detections.mapNotNull { detection ->
                    val box = capture.mapping.rawBox(ImageBox.of(detection))
                    if (!box.valid(capture.image.width, capture.image.height)) return@mapNotNull null
                    val mapped = detection.copy(left = box.left, top = box.top, right = box.right, bottom = box.bottom,
                        frameTimestampMs = capture.capturedWallMs)
                    val depth = if (capture.depth != null && now >= expires) ArObjectDepth(status = "STALE_DEPTH") else
                        ArObjectDepthSampler.sample(box, capture.mapping, capture.depth, capture.frameTimestampNs, capture.tracking, capture.depthStatus)
                    ArYoloObject(mapped, capture.mapping.viewBox(box), depth)
                }
                val smoothed = smoother.update(objects, capture.depth?.timestampNs, now)
                val result = ArYoloFrame(capture.frameTimestampNs, capture.cameraTimestampNs, capture.depth?.timestampNs,
                    capture.capturedElapsedNs, expires, capture.epoch, capture.tracking,
                    capture.image.width, capture.image.height, capture.depth?.width ?: 0, capture.depth?.height ?: 0, smoothed, inferenceMs)
                if (!closed.get() && result.isFresh(now, currentEpoch())) {
                    onResult(result)
                    status = "YOLO waiting · ${objects.size} objects · ${inferenceMs}ms"
                } else status = "YOLO waiting · 오래된 결과 제외"
            } catch (error: Exception) { status = "YOLO 추론 오류: ${error.message ?: error.javaClass.simpleName}" }
            finally {
                if (upright != null && upright !== raw && !upright.isRecycled) upright.recycle()
                if (raw != null && !raw.isRecycled) raw.recycle()
                gate.release()
            }
        }
    }

    fun close(): CompletableFuture<Unit> = synchronized(submissionLock) {
        if (closed.compareAndSet(false, true)) {
            ready = false; gate.close()
            executor.execute {
                try { detector?.close(); detector = null; smoother.reset(); stopped.complete(Unit) }
                catch (error: Exception) { stopped.completeExceptionally(error) }
            }
            executor.shutdown()
        }
        stopped
    }
}
