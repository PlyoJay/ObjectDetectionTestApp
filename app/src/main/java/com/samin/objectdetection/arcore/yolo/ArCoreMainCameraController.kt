package com.samin.objectdetection.arcore.yolo

import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.samin.objectdetection.camera.CameraBackend
import com.samin.objectdetection.camera.CameraController
import com.samin.objectdetection.settings.AppSettings
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/** Main-screen ARCore camera owner; failures release fully before CameraX fallback is requested. */
class ArCoreMainCameraController(private val activity: ComponentActivity, settings: AppSettings,
    nextFrameId: () -> Long, private val listener: CameraController.Listener,
    private val attachSurface: (GLSurfaceView) -> Unit,
    private val onInvalidated: () -> Unit, private val onStatus: (String) -> Unit,
    private val onUnavailable: (String) -> Unit) : CameraBackend, DefaultLifecycleObserver, DisplayManager.DisplayListener {
    private val epoch = AtomicLong()
    private val worker = ArCorePipelineWorker(settings.camera.detectIntervalMs, settings.overlay.depthInputMode, nextFrameId, epoch::get, listener,
        settings.debug.depthAnalysisEnabled, settings.debug.depthGroundTruthMeters?.let {
            com.samin.objectdetection.distance.DepthGroundTruth(it, settings.debug.depthGroundTruthLabel.trim()) })
    private val handler = Handler(Looper.getMainLooper())
    private val renderer = ArYoloRenderer(worker, epoch, onInvalidated, { error ->
        activity.runOnUiThread { fail("ARCore 카메라 오류: ${error.message ?: error.javaClass.simpleName}") }
    }, settings.overlay.depthInputMode, settings.camera.previewEnabled, settings.debug.depthAnalysisEnabled)
    val surface = GLSurfaceView(activity).apply {
        setEGLContextClientVersion(2); preserveEGLContextOnPause = true
        setRenderer(renderer); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY; onPause()
    }
    private var session: Session? = null
    private var surfaceResumed = false
    private var checking = false
    private var installRequested = false
    private var closed = false
    private var failed = false
    private val stopped = CompletableFuture<Unit>()
    private var lastStatus = ""
    private val refresh = object : Runnable {
        override fun run() {
            if (closed || failed || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
            val s = renderer.status
            val depth = if (s.updatedNs > 0 && SystemClock.elapsedRealtimeNanos() - s.updatedNs >= 1_000_000_000) "STALE_UPDATE" else s.depth
            val text = "ARCore Depth · 전방 Z(m) · 최대 4Hz · SDK 입력 ${s.cameraWidth}×${s.cameraHeight}\nTracking: ${s.tracking} · $depth ${s.depthWidth}×${s.depthHeight} · ${s.camera} · ${worker.status}"
            if (lastStatus != text) { lastStatus = text; onStatus(text) }
            handler.postDelayed(this, 500)
        }
    }
    override fun start() {
        attachSurface(surface)
        onStatus("ARCore 지원/Depth 확인 중…")
        activity.lifecycle.addObserver(this)
    }
    override fun onResume(owner: LifecycleOwner) {
        if (closed || failed) return
        renderer.setRotation(activity.display?.rotation ?: 0)
        activity.getSystemService(DisplayManager::class.java).registerDisplayListener(this, handler)
        handler.removeCallbacks(refresh); handler.post(refresh)
        try { ensureSession() } catch (error: Exception) { checking = false; fail("ARCore 지원 확인 실패: ${error.message}") }
    }
    private fun ensureSession() {
        if (closed || failed || checking || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        session?.let { resume(it); return }
        checking = true
        ArCoreApk.getInstance().checkAvailabilityAsync(activity) { availability -> activity.runOnUiThread {
            checking = false
            if (closed || failed || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@runOnUiThread
            if (!availability.isSupported) { fail("ARCore 사용 불가: ${availability.name}"); return@runOnUiThread }
            var created: Session? = null
            try {
                if (ArCoreApk.getInstance().requestInstall(activity, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true; onStatus("Google Play Services for AR 설치/업데이트 대기 중"); return@runOnUiThread
                }
                created = Session(activity)
                if (!created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    created.close(); created = null; fail("ARCore Depth API 미지원"); return@runOnUiThread
                }
                created.configure(created.config.apply {
                    depthMode = Config.DepthMode.AUTOMATIC; planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.DISABLED; updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                })
                session = created; resume(created)
            } catch (error: Exception) {
                if (created != null && created !== session) runCatching { created.close() }
                fail("ARCore 초기화/설치 실패: ${error.message ?: error.javaClass.simpleName}")
            }
        } }
    }
    private fun resume(value: Session) {
        if (closed || failed || surfaceResumed) return
        try {
            renderer.resume(value); surface.onResume(); surfaceResumed = true
            listener.onCameraStarted()
        } catch (error: Exception) { fail("ARCore 카메라 점유/재시작 실패: ${error.message ?: error.javaClass.simpleName}") }
    }
    override fun onPause(owner: LifecycleOwner) { pause() }
    private fun pause() {
        handler.removeCallbacks(refresh)
        activity.getSystemService(DisplayManager::class.java).unregisterDisplayListener(this)
        if (surfaceResumed) { surface.onPause(); surfaceResumed = false }
        runCatching { renderer.pause() }
    }
    private fun fail(reason: String) {
        if (closed || failed) return
        failed = true; onStatus("$reason · CameraX 인식 복구 중…")
        onUnavailable(reason)
    }
    override fun stopAsync(): CompletableFuture<Unit> {
        if (closed) return stopped
        closed = true; activity.lifecycle.removeObserver(this); pause()
        val native = renderer.detach(); session = null
        worker.close().whenComplete { _, workError ->
            CompletableFuture.runAsync { native?.close() }.whenComplete { _, nativeError ->
                val error = workError ?: nativeError
                if (error == null) stopped.complete(Unit) else stopped.completeExceptionally(error)
            }
        }
        return stopped
    }
    override fun onDestroy(owner: LifecycleOwner) { stopAsync() }
    override fun onDisplayChanged(displayId: Int) { renderer.setRotation(activity.display?.rotation ?: 0) }
    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
}
