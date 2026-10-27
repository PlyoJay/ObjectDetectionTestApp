package com.samin.objectdetection.arcore.yolo

import android.graphics.ImageFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.samin.objectdetection.arcore.ArCameraBackground
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import com.samin.objectdetection.distance.DepthInputMode
import com.samin.objectdetection.distance.DepthMethod

data class ArYoloStatus(val tracking: String = "WAITING", val depth: String = "DEPTH_NOT_READY",
    val camera: String = "CAMERA_WAITING", val depthWidth: Int = 0, val depthHeight: Int = 0,
    val updatedNs: Long = 0, val cameraWidth: Int = 0, val cameraHeight: Int = 0)

/** Session calls, image acquisition/copy/close, and transforms stay on the serialized GL owner. */
class ArYoloRenderer(private val worker: ArFrameConsumer, private val epoch: AtomicLong,
    private val onInvalidated: () -> Unit, private val onError: (Exception) -> Unit,
    private val depthInputMode: DepthInputMode = DepthInputMode.FULL,
    private val drawCameraBackground: Boolean = true) : GLSurfaceView.Renderer {
    private val lock = Any()
    private var session: Session? = null
    private var active = false
    private var background = ArCameraBackground()
    private var texture = -1
    private var width = 0
    private var height = 0
    private val timestamps = ArCameraTimestampSequence()
    private var tracking = "WAITING"
    private var displayRotation = 0
    @Volatile var status = ArYoloStatus()
        private set

    fun resume(value: Session) = synchronized(lock) {
        if (session !== value) timestamps.reset()
        session = value; value.resume(); active = true; texture = -1
        invalidate()
    }
    fun pause() = synchronized(lock) { active = false; invalidate(); session?.pause() }
    fun detach(): Session? = synchronized(lock) { active = false; invalidate(); session.also { session = null } }
    fun invalidate() { epoch.incrementAndGet(); onInvalidated() }
    fun setRotation(rotation: Int) = synchronized(lock) {
        if (displayRotation != rotation) { displayRotation = rotation; invalidate() }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try { background = ArCameraBackground(); background.create(); texture = -1 }
        catch (error: Exception) { onError(error) }
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width; this.height = height
        GLES20.glViewport(0, 0, width, height)
        invalidate()
    }
    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        synchronized(lock) {
            val current = session ?: return
            if (!active || width <= 0 || height <= 0 || background.textureId == 0) return
            try {
                if (texture != background.textureId) { current.setCameraTextureName(background.textureId); texture = background.textureId }
                current.setDisplayGeometry(displayRotation, width, height)
                val frame = current.update()
                if (drawCameraBackground) background.draw(frame)
                val now = SystemClock.elapsedRealtimeNanos()
                val state = frame.camera.trackingState.name
                if (state != tracking) { tracking = state; invalidate() }
                if (!timestamps.accept(frame.timestamp)) return
                status = status.copy(tracking = state, updatedNs = now,
                    depth = if (state != "TRACKING") "TRACKING_PAUSED" else status.depth)
                if (!worker.acquire(now)) return
                try { worker.submit(capture(frame, now, state)) }
                catch (_: NotYetAvailableException) { worker.cancelCapture(); status = status.copy(camera = "CAMERA_IMAGE_NOT_READY") }
                catch (error: Exception) {
                    worker.cancelCapture()
                    status = status.copy(camera = "CAMERA_CAPTURE_ERROR: ${error.message ?: error.javaClass.simpleName}")
                }
            } catch (error: Exception) { active = false; invalidate(); onError(error) }
        }
    }

    private fun capture(frame: Frame, nowNs: Long, tracking: String): ArYoloCapture = frame.acquireCameraImage().use { image ->
        val capturedEpoch = epoch.get()
        require(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3) { "Unsupported ARCore CPU image" }
        val yuv = ArYuvSnapshot(image.width, image.height,
            image.planes[0].let { YuvPlaneBytes(copy(it.buffer), it.rowStride, it.pixelStride) },
            image.planes[1].let { YuvPlaneBytes(copy(it.buffer), it.rowStride, it.pixelStride) },
            image.planes[2].let { YuvPlaneBytes(copy(it.buffer), it.rowStride, it.pixelStride) })
        val corners = floatArrayOf(0f, 0f, image.width.toFloat(), 0f, 0f, image.height.toFloat(), image.width.toFloat(), image.height.toFloat())
        val view = FloatArray(8); val depthUv = FloatArray(8)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, corners, Coordinates2d.VIEW_NORMALIZED, view)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, corners, Coordinates2d.TEXTURE_NORMALIZED, depthUv)
        val mapping = ArImageMapping(image.width, image.height, view, depthUv)
        require(mapping.valid()) { "Invalid ARCore coordinate mapping" }
        var depth: ArDepthPlane? = null
        var raw: ArDepthPlane? = null
        var depthStatus = if (tracking == TrackingState.TRACKING.name) "DEPTH_NOT_READY" else "TRACKING_PAUSED"
        if (tracking == TrackingState.TRACKING.name) {
            try {
                frame.acquireDepthImage16Bits().use { depthImage ->
                    val plane = depthImage.planes[0]
                    val crop = depthImage.cropRect
                    depth = ArDepthPlane(copy(plane.buffer), depthImage.width, depthImage.height, plane.rowStride,
                        plane.pixelStride, depthImage.timestamp, ImageBox(crop.left.toFloat(), crop.top.toFloat(), crop.right.toFloat(), crop.bottom.toFloat()))
                    depthStatus = "DEPTH_AVAILABLE"
                }
            } catch (_: NotYetAvailableException) { /* YOLO still runs without depth. */ }
            catch (_: Exception) { depthStatus = "DEPTH_ACQUISITION_ERROR" }
            if (depthInputMode == DepthInputMode.RAW_WITH_FULL_FALLBACK) {
                try {
                    frame.acquireRawDepthImage16Bits().use { rawImage ->
                        frame.acquireRawDepthConfidenceImage().use { confidenceImage ->
                            val p = rawImage.planes[0]; val c = confidenceImage.planes[0]; val crop = rawImage.cropRect
                            val confidenceCrop = confidenceImage.cropRect
                            if (rawImage.width == confidenceImage.width && rawImage.height == confidenceImage.height &&
                                rawImage.timestamp == confidenceImage.timestamp) {
                                raw = ArDepthPlane(copy(p.buffer), rawImage.width, rawImage.height, p.rowStride, p.pixelStride,
                                    rawImage.timestamp, ImageBox(maxOf(crop.left, confidenceCrop.left).toFloat(), maxOf(crop.top, confidenceCrop.top).toFloat(),
                                        minOf(crop.right, confidenceCrop.right).toFloat(), minOf(crop.bottom, confidenceCrop.bottom).toFloat()),
                                    DepthMethod.RAW, ArDepthConfidence(YuvPlaneBytes(copy(c.buffer), c.rowStride, c.pixelStride),
                                        confidenceImage.width, confidenceImage.height, confidenceImage.timestamp))
                            }
                        }
                    }
                } catch (_: NotYetAvailableException) { /* Same-frame FULL copy remains available. */ }
                catch (_: Exception) { /* Invalid/missing RAW pair is not passed to the worker. */ }
            }
        }
        status = ArYoloStatus(tracking, depthStatus, "CAMERA_IMAGE_READY", depth?.width ?: 0, depth?.height ?: 0, nowNs, image.width, image.height)
        ArYoloCapture(yuv, mapping, depth, depthStatus, frame.timestamp, image.timestamp, nowNs,
            System.currentTimeMillis(), capturedEpoch, tracking, raw)
    }

    companion object {
        private fun copy(buffer: ByteBuffer): ByteArray = buffer.duplicate().let { duplicate ->
            ByteArray(duplicate.remaining()).also { duplicate.get(it) }
        }
    }
}
