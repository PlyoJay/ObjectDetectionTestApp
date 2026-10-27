package com.samin.objectdetection.arcore

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.google.ar.core.Session
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** All calls on a published Session (including update/pause/resume) use the same lock. */
class ArDepthRenderer(
    private val onMeasurement: (DepthMeasurement) -> Unit,
    private val onError: (Exception) -> Unit
) : GLSurfaceView.Renderer {
    private val lock = Any()
    private var session: Session? = null
    private var active = false
    private var background = ArCameraBackground()
    private val source = ArCoreDepthSource()
    private val filter = DepthMeasurementFilter()
    private var width = 0
    private var height = 0
    private var boundTexture = -1
    private var previousSampleNs = 0L
    private var previousFrameTimestamp = -1L
    private val reset = AtomicBoolean(false)
    @Volatile var displayRotation = 0
    @Volatile var stabilize = true
    @Volatile var groundTruthMeters: Double? = null
    @Volatile var framesDrawn = 0L
        private set

    fun resetMeasurements() { reset.set(true) }

    fun resume(value: Session) = synchronized(lock) {
        session = value
        value.resume()
        active = true
        boundTexture = -1
        previousFrameTimestamp = -1
        previousSampleNs = 0
        filter.reset()
    }

    /** Caller pauses GLSurfaceView first so update cannot race Session.pause(). */
    fun pause() = synchronized(lock) {
        active = false
        filter.reset()
        session?.pause()
    }

    fun detach(): Session? = synchronized(lock) {
        active = false
        val old = session
        session = null
        filter.reset()
        old
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            background = ArCameraBackground()
            background.create()
            boundTexture = -1
        } catch (error: Exception) { fail(error) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width; this.height = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        synchronized(lock) {
            val current = session ?: return
            if (!active || width <= 0 || height <= 0 || background.textureId == 0) return
            try {
                if (boundTexture != background.textureId) {
                    current.setCameraTextureName(background.textureId)
                    boundTexture = background.textureId
                }
                current.setDisplayGeometry(displayRotation, width, height)
                val frame = current.update()
                background.draw(frame)
                if (frame.timestamp > 0) framesDrawn++
                val now = SystemClock.elapsedRealtimeNanos()
                if (reset.getAndSet(false)) { filter.reset(); previousSampleNs = 0 }
                if (now - previousSampleNs < SAMPLE_INTERVAL_NS) return
                previousSampleNs = now
                if (frame.timestamp <= 0L) return
                val sample = if (frame.timestamp == previousFrameTimestamp) DepthSample(frame.timestamp,
                    frame.camera.trackingState.name, depthStatus = "DUPLICATE_FRAME") else source.measure(frame)
                previousFrameTimestamp = frame.timestamp
                onMeasurement(filter.update(sample, now, stabilize, groundTruthMeters))
            } catch (error: Exception) { fail(error) }
        }
    }

    private fun fail(error: Exception) {
        synchronized(lock) { active = false; filter.reset() }
        onError(error)
    }

    companion object { const val SAMPLE_INTERVAL_NS = 100_000_000L }
}
