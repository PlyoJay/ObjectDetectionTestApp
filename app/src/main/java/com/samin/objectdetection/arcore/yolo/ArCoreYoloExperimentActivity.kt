package com.samin.objectdetection.arcore.yolo

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.samin.objectdetection.settings.AppSettings
import com.samin.objectdetection.settings.AppSettingsCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ArCoreYoloExperimentActivity : ComponentActivity(), DisplayManager.DisplayListener {
    private lateinit var worker: ArYoloWorker
    private lateinit var renderer: ArYoloRenderer
    private lateinit var surface: GLSurfaceView
    private lateinit var overlay: ArCoreYoloOverlay
    private lateinit var support: TextView
    private lateinit var status: TextView
    private lateinit var logStatus: TextView
    private lateinit var logButton: Button
    private lateinit var logger: ArYoloJsonLog
    private lateinit var body: LinearLayout
    private lateinit var viewport: FrameLayout
    private lateinit var footerScroll: ScrollView
    private val epoch = AtomicLong()
    private val latest = AtomicReference<ArYoloFrame?>()
    private val loggingRequested = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var resumed = false
    private var surfaceResumed = false
    private var closing = false
    private var checking = false
    private var requestedPermission = false
    private var userRequestedInstall = true
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) ensureSession() else showError("카메라 권한이 필요합니다. 권한 허용 후 다시 확인하세요.")
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed || closing) return
            val state = renderer.status
            val frame = latest.get()?.takeIf { it.isFresh(SystemClock.elapsedRealtimeNanos(), epoch.get(), overlay.staleTimeoutNs) }
            overlay.update(frame)
            val stale = state.updatedNs > 0 && SystemClock.elapsedRealtimeNanos() - state.updatedNs >= 1_000_000_000
            status.text = "Tracking: ${state.tracking} · Depth: ${if (stale) "STALE_UPDATE" else state.depth} ${state.depthWidth}×${state.depthHeight}\n" +
                "${state.camera}\n${worker.status}"
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        userRequestedInstall = savedInstanceState?.getBoolean("install", true) ?: true
        val settings = runCatching { intent.getStringExtra(EXTRA_SETTINGS)?.let(AppSettingsCodec::decode) }.getOrNull() ?: AppSettings()
        logger = ArYoloJsonLog(File(filesDir, "arcore_yolo"))
        worker = ArYoloWorker(applicationContext, settings, epoch::get) { result -> latest.set(result); logger.append(result) }
        renderer = ArYoloRenderer(worker, epoch, {
            latest.set(null)
            if (::overlay.isInitialized) overlay.postInvalidateOnAnimation()
        }, { error -> runOnUiThread {
            if (!closing && !isDestroyed) { pauseSession(); showError("ARCore 카메라 오류: ${error.message ?: error.javaClass.simpleName}") }
        } })
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        fun text(value: String, size: Float = 14f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.DKGRAY); setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        fun button(value: String, action: () -> Unit) = Button(this).apply { text = value; textSize = 13f; setOnClickListener { action() } }
        root.addView(text("Experimental: YOLO + ARCore Depth", 19f))
        support = text("ARCore / Depth 지원 확인 대기"); root.addView(support)
        status = text("YOLO loading"); root.addView(status)
        body = LinearLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        viewport = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2); preserveEGLContextOnPause = true
            setRenderer(renderer); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY; onPause()
        }
        overlay = ArCoreYoloOverlay(this).apply {
            currentEpoch = epoch::get; showConfidence = settings.overlay.showConfidence
            staleTimeoutNs = minOf(1_000L, settings.overlay.staleTimeoutMs) * 1_000_000
        }
        viewport.addView(surface, FrameLayout.LayoutParams(-1, -1))
        viewport.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        body.addView(viewport)
        val footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        footer.addView(button("ARCore 다시 확인") { userRequestedInstall = true; ensureSession(forcePermission = true) })
        footer.addView(text("실험용 · ARCore 전방 Z · 경고/지면 추정과 별개\n${settings.yolo.model.displayName} · 전체 CPU 프레임 · 입력 최대 4Hz", 12f))
        logStatus = text("JSONL 기록 OFF · 원하는 경우에만 기록하세요.", 12f); footer.addView(logStatus)
        logButton = button("실험 로그 기록 시작") { toggleLog() }
        footer.addView(LinearLayout(this).apply {
            addView(logButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("실험 로그 공유") { shareLog() }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        footer.addView(button("이전 화면") { finish() })
        footerScroll = object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) {
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) super.onMeasure(w, h)
                else super.onMeasure(w, android.view.View.MeasureSpec.makeMeasureSpec(
                    (resources.displayMetrics.heightPixels * .36f).toInt(), android.view.View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(footer) }
        body.addView(footerScroll)
        arrangeBody()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { finish() } })
    }

    override fun onResume() {
        super.onResume(); resumed = true
        renderer.setRotation(display?.rotation ?: 0)
        getSystemService(DisplayManager::class.java).registerDisplayListener(this, handler)
        handler.post(ticker); ensureSession()
    }
    private fun ensureSession(forcePermission: Boolean = false) {
        if (!resumed || closing || checking) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            showError("카메라 권한이 필요합니다.")
            if (!requestedPermission || forcePermission) { requestedPermission = true; permission.launch(Manifest.permission.CAMERA) }
            return
        }
        session?.let { resumeSession(it); return }
        checking = true
        support.text = "ARCore 지원 확인 중…"
        ArCoreApk.getInstance().checkAvailabilityAsync(this) { availability -> runOnUiThread {
            checking = false
            if (!resumed || closing || isDestroyed) return@runOnUiThread
            if (!availability.isSupported) { showError("ARCore 미지원/확인 대기: ${availability.name} · 이전 화면으로 돌아갈 수 있습니다."); return@runOnUiThread }
            var created: Session? = null
            try {
                if (ArCoreApk.getInstance().requestInstall(this, userRequestedInstall) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    userRequestedInstall = false; showError("Google Play Services for AR 설치/업데이트 후 돌아와 주세요."); return@runOnUiThread
                }
                created = Session(this)
                if (!created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    created.close(); created = null
                    showError("Depth API 미지원 · 기존 메인 앱은 이전 화면에서 사용할 수 있습니다."); return@runOnUiThread
                }
                created.configure(created.config.apply {
                    depthMode = Config.DepthMode.AUTOMATIC; planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.DISABLED; updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                })
                session = created
                resumeSession(created)
            } catch (error: Exception) {
                if (created != null && created !== session) runCatching { created.close() }
                showError("ARCore 초기화/설치 실패: ${error.message ?: error.javaClass.simpleName}")
            }
        } }
    }
    private fun resumeSession(value: Session) {
        if (surfaceResumed || !resumed || closing) return
        try {
            renderer.resume(value); surface.onResume(); surfaceResumed = true
            support.text = "ARCore 지원 · Depth AUTOMATIC · 실험 모드"
        } catch (error: Exception) { showError("카메라 점유/재시작 실패: ${error.message ?: error.javaClass.simpleName}") }
    }
    private fun showError(message: String) { support.text = message }
    private fun pauseSession() {
        if (surfaceResumed) { surface.onPause(); surfaceResumed = false }
        runCatching { renderer.pause() }
        latest.set(null); overlay.update(null)
    }
    override fun onPause() {
        resumed = false; handler.removeCallbacks(ticker)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(this)
        pauseSession(); stopLog(); super.onPause()
    }
    override fun finish() {
        if (closing) return
        closing = true; handler.removeCallbacks(ticker); pauseSession(); loggingRequested.set(false)
        val nativeSession = renderer.detach()
        session = null
        val stopped = worker.close(); val saved = logger.stop()
        CompletableFuture.runAsync {
            try { stopped.get(); saved.get() } finally { nativeSession?.close() }
        }.whenComplete { _, error -> runOnUiThread {
            if (!isDestroyed) {
                if (error != null) Toast.makeText(this, "실험 종료 오류: ${error.cause?.message ?: error.message}", Toast.LENGTH_LONG).show()
                super@ArCoreYoloExperimentActivity.finish()
            }
        } }
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); loggingRequested.set(false); logger.stop()
        worker.close()
        val old = renderer.detach(); session = null
        if (old != null) CompletableFuture.runAsync { runCatching { old.close() } }
        super.onDestroy()
    }
    private fun toggleLog() {
        if (logger.recording()) { stopLog(); return }
        if (closing || !resumed || logger.saving() || !loggingRequested.compareAndSet(false, true)) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                check(loggingRequested.get()); logger.start(); if (!loggingRequested.get()) logger.stop()
            } }
            result.onSuccess {
                if (!resumed || closing || !loggingRequested.get()) { logger.stop(); return@onSuccess }
                logButton.text = "실험 로그 기록 종료"
                logStatus.text = "기록 중: ${logger.latestFile?.name}"
            }.onFailure { loggingRequested.set(false); logStatus.text = "로그 시작 실패: ${it.message}" }
        }
    }
    private fun stopLog() {
        loggingRequested.set(false); logButton.text = "실험 로그 기록 시작"
        logger.stop().whenComplete { _, error -> runOnUiThread {
            if (!isDestroyed) logStatus.text = if (error != null) "로그 저장 실패: ${error.message}" else
                logger.latestFile?.let { "저장 완료: ${it.name} (내부 arcore_yolo/)" } ?: "JSONL 기록 OFF"
        } }
    }
    private fun shareLog() {
        if (logger.recording() || logger.saving() || logger.failed()) { Toast.makeText(this, "실험 로그 기록을 정상 종료한 뒤 공유하세요.", Toast.LENGTH_SHORT).show(); return }
        val file = logger.latestFile ?: run { Toast.makeText(this, "이번 화면에서 저장한 로그가 없습니다.", Toast.LENGTH_SHORT).show(); return }
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).setType("application/x-ndjson").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("ARCore YOLO JSONL", uri) }
            startActivity(Intent.createChooser(send, "실험 로그 공유"))
        }.onFailure { Toast.makeText(this, "공유 실패: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("install", userRequestedInstall); super.onSaveInstanceState(outState) }
    override fun onDisplayChanged(displayId: Int) { renderer.setRotation(display?.rotation ?: 0) }
    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
    private fun arrangeBody() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        body.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        viewport.layoutParams = if (landscape) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f)
        footerScroll.layoutParams = if (landscape) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, -2)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        arrangeBody(); renderer.setRotation(display?.rotation ?: 0); renderer.invalidate()
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    companion object { const val EXTRA_SETTINGS = "arcoreYoloSettings" }
}
