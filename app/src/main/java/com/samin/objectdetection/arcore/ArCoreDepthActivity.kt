package com.samin.objectdetection.arcore

import android.Manifest
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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

class ArCoreDepthActivity : ComponentActivity(), DisplayManager.DisplayListener {
    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: ArDepthRenderer
    private lateinit var recorder: DepthCsvRecorder
    private lateinit var support: TextView
    private lateinit var tracking: TextView
    private lateinit var distance: TextView
    private lateinit var statistics: TextView
    private lateinit var recording: TextView
    private lateinit var measureButton: Button
    private lateinit var retry: Button
    private lateinit var truthInput: EditText
    private lateinit var body: LinearLayout
    private lateinit var viewport: FrameLayout
    private lateinit var footerScroll: ScrollView
    private var restoredTruth = ""
    private val handler = Handler(Looper.getMainLooper())
    private val latest = AtomicReference<DepthMeasurement?>()
    private var session: Session? = null
    private var resumed = false
    private var surfaceResumed = false
    private var checking = false
    private var closing = false
    private var permissionRequested = false
    private var userRequestedInstall = true
    private var supportText = "ARCore: 확인 중 · Depth API: 확인 전"
    private var runtimeError: String? = null
    private var latestSaved: File? = null
    private val recordingRequested = AtomicBoolean(false)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        checking = false
        if (granted) ensureSession() else showError("카메라 권한이 거부되었습니다. 권한을 허용한 뒤 다시 확인하세요.")
    }
    private val refresh = object : Runnable {
        override fun run() {
            if (!resumed || closing) return
            showMeasurement()
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        userRequestedInstall = savedInstanceState?.getBoolean("userRequestedInstall", true) ?: true
        restoredTruth = savedInstanceState?.getString("groundTruth").orEmpty()
        recorder = DepthCsvRecorder(File(filesDir, "arcore_depth"))
        latestSaved = savedInstanceState?.getString("latestLog")?.let { name ->
            File(filesDir, "arcore_depth").resolve(File(name).name).takeIf { it.isFile }
        } ?: File(filesDir, "arcore_depth").listFiles()?.filter { it.extension == "csv" }?.maxByOrNull { it.lastModified() }
        renderer = ArDepthRenderer({ value -> latest.set(value); recorder.append(value) }, { error ->
            runOnUiThread {
                if (!closing && !isDestroyed) {
                    pauseSession()
                    stopRecording(false)
                    showError(if (error is CameraNotAvailableException) "카메라 점유 충돌 또는 카메라 연결 실패입니다. 다시 확인하세요."
                        else "ARCore 프레임 처리 실패: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        })
        renderer.stabilize = savedInstanceState?.getBoolean("stabilize", true) ?: true
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }

    private fun buildUi() {
        fun text(value: String, size: Float = 14f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.DKGRAY); setPadding(dp(12), dp(3), dp(12), dp(3))
        }
        fun button(value: String, action: () -> Unit) = Button(this).apply { text = value; textSize = 13f; setOnClickListener { action() } }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        root.addView(text("ARCore 거리 측정", 21f))
        support = text(supportText); root.addView(support)
        tracking = text("Tracking: 대기 · Depth: — · 갱신: 대기"); root.addView(tracking)
        retry = button("ARCore 다시 확인") {
            if (!checking && !closing) {
                runtimeError = null
                userRequestedInstall = true // An explicit retry may offer installation again.
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    permissionRequested = true; permission.launch(Manifest.permission.CAMERA)
                } else ensureSession()
            }
        }
        root.addView(retry)
        body = LinearLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        viewport = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            onPause() // setRenderer starts its thread; keep it paused until a Session is ready.
        }
        viewport.addView(surface, FrameLayout.LayoutParams(-1, -1))
        viewport.addView(text("+", 38f).apply { gravity = Gravity.CENTER; setTextColor(Color.WHITE); setShadowLayer(4f, 1f, 1f, Color.BLACK) },
            FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))
        distance = text("깊이 데이터 준비 중 - 기기를 천천히 움직여 주세요.", 20f).apply {
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
        }
        viewport.addView(distance, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { topMargin = dp(120) })
        body.addView(viewport)
        val footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(8)) }
        statistics = text("현재 원본 Z: — · 최근 평균: — · 유효 샘플: 0"); footer.addView(statistics)
        recording = text("실시간 Z 표시 · 측정 시작 시 CSV 기록 · 실측값 미설정"); footer.addView(recording)
        footer.addView(Switch(this).apply {
            text = "표시 안정화 (최근 5개 이동 중앙값)"; isChecked = renderer.stabilize
            setOnCheckedChangeListener { _, enabled -> renderer.stabilize = enabled; renderer.resetMeasurements(); latest.set(null) }
        })
        val truth = EditText(this).apply {
            hint = "실측 전방 깊이 Z(m), 미설정은 비워 두기"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            contentDescription = "실측 전방 깊이 Z (m)"
        }
        truthInput = truth
        truth.setText(restoredTruth)
        renderer.groundTruthMeters = restoredTruth.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        truth.doAfterTextChanged { text ->
            val entered = text?.toString()?.trim().orEmpty()
            val meters = entered.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
            truth.error = if (entered.isNotEmpty() && meters == null) "0보다 큰 유한한 Z(m)를 입력하세요." else null
            renderer.groundTruthMeters = meters
            renderer.resetMeasurements(); latest.set(null)
        }
        footer.addView(truth)
        footer.addView(LinearLayout(this).apply {
            for (meters in listOf(1, 2, 3, 5)) addView(button("${meters}m") { truth.setText(meters.toString()) }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        footer.addView(text("실측값도 렌즈 중심 기준 광축 방향 Z입니다. 사선 거리·지면 기반 추정과 구분하세요.", 12f))
        measureButton = button("측정 시작") { if (recorder.isRecording()) stopRecording(true) else startRecording() }
        measureButton.isEnabled = false
        footer.addView(LinearLayout(this).apply {
            addView(measureButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("측정 로그 저장") { stopRecording(true) }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        footer.addView(LinearLayout(this).apply {
            addView(button("측정 로그 공유") { shareLog() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("이전 화면으로 돌아가기") { finish() }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        val scroll = object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) {
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) super.onMeasure(w, h)
                else super.onMeasure(w, View.MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels * .48f).toInt(), View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(footer) }
        footerScroll = scroll
        body.addView(scroll)
        arrangeBody()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, bars.bottom); insets
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        renderer.displayRotation = display?.rotation ?: 0
        getSystemService(DisplayManager::class.java).registerDisplayListener(this, handler)
        handler.post(refresh)
        ensureSession()
    }

    private fun ensureSession() {
        if (!resumed || closing || checking) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            showError("ARCore 카메라 권한이 필요합니다.")
            if (!permissionRequested) { permissionRequested = true; permission.launch(Manifest.permission.CAMERA) }
            return
        }
        val existing = session
        if (existing != null) { resumeSession(existing); return }
        checking = true
        support.text = "ARCore: 지원 여부 확인 중 · Depth API: 확인 전"
        ArCoreApk.getInstance().checkAvailabilityAsync(this) { availability -> runOnUiThread {
            checking = false
            if (!resumed || closing || isDestroyed) return@runOnUiThread
            supportText = "ARCore: ${availability.name} · Depth API: 확인 전"
            support.text = supportText
            if (!availability.isSupported) {
                showError(if (availability.isTransient) "ARCore 지원 여부 확인 중입니다. 잠시 후 다시 확인하세요."
                    else "ARCore 미지원 또는 지원 확인 실패입니다. 이전 화면에서 기존 객체 인식을 사용할 수 있습니다.")
                return@runOnUiThread
            }
            var created: Session? = null
            try {
                when (ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { userRequestedInstall = false; showError("Google Play Services for AR 설치/업데이트 후 돌아와 주세요."); return@runOnUiThread }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }
                created = Session(this)
                if (!created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    created.close(); created = null
                    supportText = "ARCore: 지원 · Depth API: 미지원"
                    showError("이 기기의 ARCore Depth API는 지원되지 않습니다.")
                    return@runOnUiThread
                }
                val config = created.config.apply {
                    depthMode = Config.DepthMode.AUTOMATIC
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                }
                created.configure(config)
                session = created
                supportText = "ARCore: 지원 · Depth API: 지원 / AUTOMATIC"
                resumeSession(created)
            } catch (error: Exception) {
                if (created != null && created !== session) runCatching { created.close() }
                showError(if (error is UnavailableUserDeclinedInstallationException) "Google Play Services for AR 설치가 취소되었습니다."
                    else "ARCore 초기화 실패: ${error.message ?: error.javaClass.simpleName}")
            }
        } }
    }

    private fun resumeSession(value: Session) {
        if (surfaceResumed || !resumed || closing) return
        try {
            renderer.resume(value)
            surface.onResume(); surfaceResumed = true
            runtimeError = null
            support.text = supportText
            measureButton.isEnabled = true
        } catch (error: Exception) {
            showError(if (error is CameraNotAvailableException) "카메라를 사용할 수 없습니다. 다른 카메라 화면을 종료한 뒤 다시 확인하세요."
                else "ARCore 재시작 실패: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun showError(message: String) {
        runtimeError = message
        support.text = supportText
        distance.text = message
        measureButton.isEnabled = false
    }

    private fun pauseSession() {
        if (surfaceResumed) { surface.onPause(); surfaceResumed = false }
        runCatching { renderer.pause() }
        latest.set(null)
        distance.text = "전방 깊이: —"
        statistics.text = "현재 원본 Z: — · 최근 평균: — · 유효 샘플: 0"
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(refresh)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(this)
        pauseSession()
        stopRecording(false)
        super.onPause()
    }

    /** Explicit returns wait for close, so MainActivity cannot restart CameraX ahead of ARCore. */
    override fun finish() {
        if (closing) return
        closing = true
        measureButton.isEnabled = false
        retry.isEnabled = false
        handler.removeCallbacks(refresh)
        pauseSession()
        val old = renderer.detach()
        session = null
        recordingRequested.set(false)
        val saved = recorder.stop()
        // Cleanup must survive Activity lifecycle coroutine cancellation.
        CompletableFuture.runAsync {
            try { saved.get() } finally { old?.close() }
        }.whenComplete { _, error -> runOnUiThread {
            if (!isDestroyed) {
                if (error != null) Toast.makeText(this, "ARCore 종료/로그 저장 오류: ${error.cause?.message ?: error.message}", Toast.LENGTH_LONG).show()
                super@ArCoreDepthActivity.finish()
            }
        } }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recordingRequested.set(false)
        recorder.stop()
        val old = renderer.detach()
        session = null
        // Covers OS recreation, which does not go through finish().
        if (old != null) CompletableFuture.runAsync { runCatching { old.close() } }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("userRequestedInstall", userRequestedInstall)
        outState.putString("latestLog", recorder.latestFile?.name ?: latestSaved?.name)
        outState.putString("groundTruth", truthInput.text.toString())
        outState.putBoolean("stabilize", renderer.stabilize)
        super.onSaveInstanceState(outState)
    }

    private fun startRecording() {
        if (!surfaceResumed || recorder.isClosing()) return
        if (!recordingRequested.compareAndSet(false, true)) return
        lifecycleScope.launch {
            measureButton.isEnabled = false
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    check(recordingRequested.get()) { "측정 시작이 취소되었습니다." }
                    recorder.start().also { if (!recordingRequested.get()) recorder.stop() }
                }
            }
            result.onSuccess {
                if (!resumed || closing || !recordingRequested.get()) { recorder.stop(); return@onSuccess }
                renderer.resetMeasurements(); latest.set(null)
                measureButton.text = "측정 종료"
                latestSaved = it
            }.onFailure { recordingRequested.set(false); Toast.makeText(this@ArCoreDepthActivity, "로그 시작 실패: ${it.message}", Toast.LENGTH_LONG).show() }
            if (!closing) measureButton.isEnabled = surfaceResumed
        }
    }

    private fun stopRecording(showSaved: Boolean) {
        recordingRequested.set(false)
        val file = recorder.latestFile ?: latestSaved
        val completion = recorder.stop()
        measureButton.text = "측정 시작"
        completion.whenComplete { _, error -> runOnUiThread {
            if (!isDestroyed && !closing && showSaved) {
                Toast.makeText(this, if (error != null) "CSV 저장 실패: ${error.message}" else
                    file?.let { "저장 완료: ${it.name} (누락 ${recorder.droppedLines()}줄)" } ?: "저장할 측정 로그가 없습니다.", Toast.LENGTH_LONG).show()
            }
        } }
    }

    private fun shareLog() {
        if (recorder.isRecording() || recorder.isClosing()) { Toast.makeText(this, "측정 종료 및 저장 후 공유하세요.", Toast.LENGTH_SHORT).show(); return }
        val file = (recorder.latestFile ?: latestSaved)?.takeIf { it.isFile }
            ?: run { Toast.makeText(this, "저장된 측정 로그가 없습니다.", Toast.LENGTH_SHORT).show(); return }
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "ARCore Depth CSV 공유"))
        }.onFailure { Toast.makeText(this, "공유 실패: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun showMeasurement() {
        val value = latest.get()
        val now = SystemClock.elapsedRealtimeNanos()
        val fresh = value?.isFresh(now) == true && value.sample.trackingState == "TRACKING" && now - value.receivedElapsedNs < DepthMeasurement.STALE_NS
        tracking.text = "Tracking: ${value?.sample?.trackingState ?: "대기"} · Depth: ${value?.sample?.takeIf { it.depthWidth > 0 }?.let { "${it.depthWidth}×${it.depthHeight}" } ?: "—"}\n" +
            "갱신: ${if (value != null && now - value.receivedElapsedNs >= DepthMeasurement.STALE_NS) "STALE_UPDATE" else value?.sample?.depthStatus ?: "대기"}"
        if (runtimeError == null) distance.text = if (fresh && value?.filteredDepthMeters != null)
            "전방 깊이: 약 ${meters(value.filteredDepthMeters)}m\n${if (value.sample.depthStatus == "UPDATED") "ARCore Depth Z" else "이전 유효값 · 새 깊이 대기 중"}"
        else if (value != null && value.sample.trackingState != "TRACKING") "Tracking ${value.sample.trackingState} · 거리 표시 중지"
        else "깊이 데이터 준비 중 - 기기를 천천히 움직여 주세요."
        statistics.text = "현재 원본 Z: ${if (fresh) value?.sample?.rawDepthMeters?.let { "${meters(it)}m" } ?: "—" else "—"} · " +
            "필터 Z: ${if (fresh) value?.filteredDepthMeters?.let { "${meters(it)}m" } ?: "—" else "—"}\n" +
            "최근 평균(최대 3초/30개): ${if (fresh) value?.recentMeanMeters?.let { "${meters(it)}m" } ?: "—" else "—"} · " +
            "유효 픽셀: ${if (fresh) value?.sample?.validSampleCount ?: 0 else 0} · 최근 유효 측정: ${if (fresh) value?.recentValidCount ?: 0 else 0}"
        recording.text = (if (recorder.isRecording()) "CSV 기록 중 (최대 10Hz)" else if (recorder.isClosing()) "CSV 저장 중" else "CSV 기록 중지") +
            " · 누락 ${recorder.droppedLines()}줄\n" + (recorder.error?.let { "저장 오류: $it" } ?: "실측 Z: ${renderer.groundTruthMeters?.let { "${meters(it)}m" } ?: "미설정 · 오차 계산 안 함"}")
    }

    private fun meters(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun arrangeBody() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        body.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        viewport.layoutParams = if (landscape) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f)
        footerScroll.layoutParams = if (landscape) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, -2)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDisplayChanged(displayId: Int) { renderer.displayRotation = display?.rotation ?: 0 }
    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        renderer.displayRotation = display?.rotation ?: 0
        arrangeBody()
        renderer.resetMeasurements(); latest.set(null)
    }
}
