package com.samin.objectdetection

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.samin.objectdetection.camera.CameraController
import com.samin.objectdetection.camera.CameraFrameTiming
import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.camera.FrameRoi
import com.samin.objectdetection.metrics.PerformanceStopReason
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.ObjectDetector
import com.samin.objectdetection.detector.VisionStyleYoloDetector
import com.samin.objectdetection.evaluation.DetectionFrameSnapshot
import com.samin.objectdetection.evaluation.EvaluationDataRecorder
import com.samin.objectdetection.evaluation.ScreenRecordService
import com.samin.objectdetection.evaluation.RecordingState
import com.samin.objectdetection.detector.InferenceException
import com.samin.objectdetection.metrics.FrameTrace
import com.samin.objectdetection.metrics.FrameLatencyEvent
import com.samin.objectdetection.metrics.FrameLatencyRecorder
import com.samin.objectdetection.metrics.FrameRateMeter
import com.samin.objectdetection.location.UserLocationTracker
import com.samin.objectdetection.metrics.DetectionMetricsCollector
import com.samin.objectdetection.metrics.PerformanceFrameRecord
import com.samin.objectdetection.metrics.PerformanceLogHeader
import com.samin.objectdetection.metrics.PerformanceLogRecorder
import com.samin.objectdetection.mlkit.MlKitObjectDetector
import com.samin.objectdetection.mlkit.MlKitInputBuffer
import com.samin.objectdetection.mlkit.MlKitDiagnostics
import com.samin.objectdetection.mlkit.MlKitDiagnosticLogs
import com.samin.objectdetection.mlkit.MlKitDiagnosticLogRecorder
import com.samin.objectdetection.motion.ObjectMotionTracker
import com.samin.objectdetection.pipeline.DetectionPipeline
import com.samin.objectdetection.distance.DistanceOpticsMode
import com.samin.objectdetection.policy.OverlayObjectFilter
import com.samin.objectdetection.policy.YoloDefaultPolicyRegistry
import com.samin.objectdetection.ui.BoundingBoxOverlay
import com.samin.objectdetection.ui.LatestUiUpdateDispatcher
import com.samin.objectdetection.ui.MainScreenView
import com.samin.objectdetection.ui.OverlayDebugMode
import com.samin.objectdetection.settings.AppSettings
import com.samin.objectdetection.settings.AppSettingsStore
import com.samin.objectdetection.settings.SettingsActivity
import com.samin.objectdetection.settings.SettingsPresets
import com.samin.objectdetection.settings.AppSettingsCodec
import com.samin.objectdetection.settings.LogShareManager
import com.samin.objectdetection.warning.CrowdDecision
import com.samin.objectdetection.warning.FeedbackLevel
import com.samin.objectdetection.warning.RiskLevel
import com.samin.objectdetection.warning.WarningCandidate
import com.samin.objectdetection.warning.WarningCandidateSelector
import com.samin.objectdetection.warning.WarningCooldownManager
import com.samin.objectdetection.warning.WarningPolicy
import com.samin.objectdetection.warning.output.BeepWarningPlayer
import com.samin.objectdetection.warning.output.TtsWarningPlayer
import com.samin.objectdetection.warning.output.VibrationWarningPlayer
import com.samin.objectdetection.warning.output.WarningOutputController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import java.util.concurrent.TimeUnit
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CompletableFuture
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var screen: MainScreenView
    private lateinit var overlayView: BoundingBoxOverlay
    private val debugTextView get() = screen.debugTextView
    private val warningMessageTextView get() = screen.warningMessageTextView
    private val recordingButton get() = screen.recordingButton
    private val performanceLogButton get() = screen.performanceLogButton
    private val performanceRecordingTextView get() = screen.performanceRecordingTextView

    private lateinit var cameraController: CameraController
    private lateinit var detector: ObjectDetector
    private lateinit var mlKitDetector: MlKitObjectDetector
    private lateinit var evaluationDataRecorder: EvaluationDataRecorder
    private lateinit var appSettings: AppSettings
    private lateinit var detectionConfig: DetectionConfig
    private lateinit var settingsStore: AppSettingsStore
    private lateinit var detectionDebugRecorder: com.samin.objectdetection.detector.DetectionDebugRecorder
    private lateinit var warningCooldownManager: WarningCooldownManager
    private val warningCandidateSelector = WarningCandidateSelector()
    private val metricsCollector = DetectionMetricsCollector()
    private lateinit var performanceLogRecorder: PerformanceLogRecorder
    private lateinit var detectionPipeline: DetectionPipeline
    private lateinit var userLocationTracker: UserLocationTracker
    private lateinit var vibrationWarningPlayer: VibrationWarningPlayer
    private lateinit var beepWarningPlayer: BeepWarningPlayer
    private lateinit var ttsWarningPlayer: TtsWarningPlayer
    private lateinit var warningOutputController: WarningOutputController

    private val isMlKitProcessing = AtomicBoolean(false)
    private val mlKitInputBuffer = MlKitInputBuffer()
    @Volatile private var mlKitDiagnostics: MlKitDiagnostics? = null
    private lateinit var sessionMlKitDiagnostics: MlKitDiagnostics
    private lateinit var mlKitLogRecorder: MlKitDiagnosticLogRecorder
    private var mlKitLogJob: Job? = null
    private var mlKitHasSavedLog = false
    private var mlKitObservedCompletion: MlKitDiagnosticLogRecorder.Status? = null
    @Volatile
    private var lastMlKitCount = 0

    @Volatile
    private var lastMlKitTimeMs = 0L
    private val sessionId = UUID.randomUUID().toString()
    private val frameRates = FrameRateMeter()
    private val recordingState = RecordingState()
    private val recordingWork = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val captureWork = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var destroying = false
    private var recordingFinished = CompletableFuture.completedFuture(Unit)
    private var performanceJob: Job? = null
    private var receiverRegistered = false
    private var lastMlKitDetectionTime = 0L
    private val captureRequested = AtomicBoolean(false)
    private val detectionUiUpdates = LatestUiUpdateDispatcher { update -> runOnUiThread { update() } }
    private val mlKitUiUpdates = LatestUiUpdateDispatcher { update -> runOnUiThread { update() } }
    private var activeRecordingVideoFile: File? = null
    private var activeRecordingDetectionsFile: File? = null
    private val isRecording get() = recordingState.busy
    @Volatile private var actualAnalysisWidth = 0
    @Volatile private var actualAnalysisHeight = 0
    @Volatile private var previewOffStatus = "카메라 시작 대기 중"
    @Volatile private var lastFrameCompletionNs = 0L

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.getBooleanExtra(SettingsActivity.EXTRA_APPLIED, false) == true) {
            restartForSettings()
        }
    }

    private val screenRecordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val id = intent.getStringExtra(ScreenRecordService.EXTRA_SESSION_ID) ?: return
            when (intent.action) {
                ScreenRecordService.ACTION_RECORDING_STARTED -> {
                    evaluationDataRecorder.markRecordingStarted(id,
                        intent.getLongExtra(ScreenRecordService.EXTRA_STARTED_AT_MS, 0),
                        intent.getLongExtra(ScreenRecordService.EXTRA_STARTED_ELAPSED_NS, 0))
                    if (recordingState.started(id) && !destroying) {
                        recordingButton.text = "녹화 중지"
                        recordingButton.isEnabled = true
                        Toast.makeText(this@MainActivity, "화면 녹화 시작: ${activeRecordingVideoFile?.name}", Toast.LENGTH_SHORT).show()
                    }
                }
                ScreenRecordService.ACTION_RECORDING_STOPPED -> {
                    finishRecordingState(id, "FINALIZED", "화면 녹화 저장: ${activeRecordingVideoFile?.name ?: "mp4"}", intent)
                }
                ScreenRecordService.ACTION_RECORDING_ERROR -> {
                    val message = intent.getStringExtra(ScreenRecordService.EXTRA_MESSAGE)
                    finishRecordingState(id, "ERROR", "화면 녹화 실패: ${message ?: "unknown"}", intent)
                }
                ScreenRecordService.ACTION_RECORDING_PUBLISH_ERROR -> {
                    if (!destroying) Toast.makeText(this@MainActivity, intent.getStringExtra(ScreenRecordService.EXTRA_MESSAGE), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasCameraPermission()) {
                if (hasLocationPermission()) {
                    userLocationTracker.start()
                }
                startCamera()
            } else {
                debugTextView.text = "카메라 권한이 필요합니다."
                previewOffStatus = "카메라 권한이 필요합니다."
                screen.updatePreviewStatus(previewOffStatus)
            }
        }

    private val screenCapturePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startScreenRecording(result.resultCode, result.data!!)
            } else {
                recordingButton.isEnabled = true
                Toast.makeText(this, "화면 녹화 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsStore = AppSettingsStore(this)
        appSettings = settingsStore.loadApplied()
        val yoloDetector = createYoloDetectorWithRecovery() ?: return
        detector = yoloDetector
        // Recovery can restore different applied settings, including the diagnostics opt-in.
        mlKitLogRecorder = MlKitDiagnosticLogs.get(applicationContext)
        mlKitDetector = MlKitObjectDetector(appSettings.mlKit.mode)
        mlKitLogRecorder.runtimeStarted(sessionId, appSettings.debug.enableMlKitDiagnostics,
            AppSettingsCodec.snapshotLines(appSettings), mlKitDetector.mode.name)
        sessionMlKitDiagnostics = MlKitDiagnostics(sessionId, SystemClock::elapsedRealtimeNanos) {
                Log.i("MlKitDiagnostics", it)
                mlKitLogRecorder.record(sessionId, it)
        }.apply {
            // Old SDK callbacks retain this gate: a stopped temporary session cannot keep
            // diagnostics enabled, even before the next main-thread status refresh.
            enabled = { appSettings.debug.enableMlKitDiagnostics || mlKitLogRecorder.isRecordingFor(sessionId) }
        }
        updateMlKitDiagnosticActivation()
        logMlKitConfiguration()
        val modelIdentity = yoloDetector.modelIdentity()
        detectionConfig = detectionConfig.copy(inputSize = modelIdentity.inputWidth, inputHeight = modelIdentity.inputHeight)
        warningCooldownManager = WarningCooldownManager(appSettings.warning.cooldownMs)
        evaluationDataRecorder = EvaluationDataRecorder(
            context = this,
            detectionConfig = detectionConfig,
            modelName = appSettings.yolo.model.assetName,
            detectorType = DETECTOR_TYPE,
            modelIdentity = detector.modelIdentity(),
            appSettings = appSettings,
            onFailure = { id, error -> onLogFailure(id, error) },
            appSessionId = sessionId
        )
        performanceLogRecorder = PerformanceLogRecorder(this) { error ->
            runOnUiThread {
                if (!destroying) {
                    Toast.makeText(this, "성능 로그 저장 실패: ${error.message}", Toast.LENGTH_LONG).show()
                    val pendingPerformance = performanceJob
                    recordingWork.launch {
                        pendingPerformance?.join()
                        if (!destroying && performanceLogRecorder.isRecording) stopPerformanceLogging(PerformanceStopReason.ERROR)
                    }
                }
            }
        }
        userLocationTracker = UserLocationTracker(this)
        detectionPipeline = DetectionPipeline(
            detector = detector,
            config = detectionConfig,
            objectMotionTracker = ObjectMotionTracker(
                maxHistorySize = detectionConfig.motionMaxHistorySize,
                minHistorySize = detectionConfig.motionMinHistorySize,
                minAbsoluteAreaChange = detectionConfig.motionMinAbsoluteAreaChange,
                minRelativeAreaChangeRatio = detectionConfig.motionMinRelativeAreaChangeRatio,
                minAbsoluteHeightChange = detectionConfig.motionMinAbsoluteHeightChange,
                minRelativeHeightChangeRatio = detectionConfig.motionMinRelativeHeightChangeRatio,
                maxMatchDistanceRatio = detectionConfig.motionMaxMatchDistanceRatio,
                minSampleIntervalMs = detectionConfig.motionMinSampleIntervalMs,
                staleTrackTimeoutMs = detectionConfig.motionStaleTrackTimeoutMs
            ),
            userLocationSnapshotProvider = { userLocationTracker.currentSnapshot },
            debugRecorder = detectionDebugRecorder,
            distanceSettings = appSettings.distance
        )
        vibrationWarningPlayer = VibrationWarningPlayer(this)
        beepWarningPlayer = BeepWarningPlayer()
        ttsWarningPlayer = TtsWarningPlayer(this)
        warningOutputController = WarningOutputController(
            scope = lifecycleScope,
            vibrationWarningPlayer = vibrationWarningPlayer,
            beepWarningPlayer = beepWarningPlayer,
            ttsWarningPlayer = ttsWarningPlayer,
            warningCooldownManager = warningCooldownManager,
            enableActualVibration = { appSettings.warning.enableActualVibration },
            enableActualBeep = { appSettings.warning.enableActualBeep },
            enableActualTts = { appSettings.warning.enableActualTts }
        )
        logWarningPolicyOverlayMismatch()

        setupUi()
        settingsStore.markAppliedHealthy()
        registerScreenRecordingReceiver()
        checkPermissionAndStart()
    }

    private fun createYoloDetectorWithRecovery(): VisionStyleYoloDetector? {
        fun create(): VisionStyleYoloDetector {
            detectionConfig = appSettings.toDetectionConfig()
            if (::detectionDebugRecorder.isInitialized) detectionDebugRecorder.close()
            detectionDebugRecorder = com.samin.objectdetection.detector.DetectionDebugRecorder(this, detectionConfig, sessionId)
            return VisionStyleYoloDetector(
                context = this, modelName = appSettings.yolo.model.assetName,
                confidenceThreshold = detectionConfig.confidenceThreshold, nmsThreshold = detectionConfig.nmsThreshold,
                sizeFilterMode = detectionConfig.sizeFilterMode, resizeMode = detectionConfig.yoloResizeMode,
                interpreterThreadCount = detectionConfig.interpreterThreadCount, maxCandidates = detectionConfig.maxCandidates,
                debugRecorder = detectionDebugRecorder, useXnnpack = detectionConfig.useXnnpack
            ).apply { enableDiagnostics = detectionConfig.enableDetectorDiagnostics }
        }
        return try { create() } catch (error: Exception) {
            val failedModel = appSettings.yolo.model.assetName
            Log.e(TAG, "Model initialization failed: $failedModel", error)
            val previous = settingsStore.restorePreviousApplied()
            if (previous != null) {
                appSettings = previous
                try {
                    val recovered = create()
                    Toast.makeText(this, "$failedModel 로딩 실패: ${error.message}\n이전 설정(${previous.yolo.model.assetName})을 복구했습니다.", Toast.LENGTH_LONG).show()
                    return recovered
                } catch (recoveryError: Exception) { error.addSuppressed(recoveryError) }
            }
            AlertDialog.Builder(this).setTitle("모델 로딩 실패")
                .setMessage("$failedModel\n${error.message}\n" +
                    (if (previous == null) "복구할 이전 설정이 없습니다." else "이전 설정의 모델도 로딩하지 못했습니다: ${error.suppressed.lastOrNull()?.message}"))
                .setCancelable(false).setPositiveButton("닫기") { _, _ -> finish() }.show()
            null
        }
    }

    private fun setupUi() {
        screen = MainScreenView(
            activity = this,
            debugMode = detectionConfig.overlayDebugMode,
            presetName = appSettings.presetName,
            modelDescription = detector.modelIdentity()?.let { "Model: ${it.assetName}\nSHA256: ${it.sha256Prefix}" } ?: "Model: ${appSettings.yolo.model.assetName}",
            overlayInitiallyEnabled = appSettings.overlay.enabled,
            outputTestMode = !appSettings.warning.enableActualVibration ||
                !appSettings.warning.enableActualBeep || !appSettings.warning.enableActualTts,
            onCapture = ::captureEvaluationFrame,
            onToggleRecording = ::toggleEvaluationRecording,
            onTogglePerformanceLogging = ::togglePerformanceLogging,
            onOpenSettings = ::openSettings,
            onOpenQuickSettings = ::openQuickSettings,
            cameraPreviewEnabled = appSettings.camera.previewEnabled,
            onToggleMlKitLogging = ::toggleMlKitLogging,
            onShareMlKitLog = ::shareMlKitLog
        )
        overlayView = screen.overlayView
        overlayView.onFrameDrawn = { trace, wallMs, elapsedNs, enabled ->
            val age = trace.captureAgeMsAt(elapsedNs)
            if (trace.status == "SUCCESS" && enabled) metricsCollector.recordOverlayAge(age)
            performanceLogRecorder.recordOverlay(trace, wallMs, elapsedNs, age, enabled)
            evaluationDataRecorder.recordOverlay(trace, wallMs, elapsedNs, enabled)
        }
        recordingWork.launch {
            while (!destroying) {
                delay(1000)
                if (destroying) break
                val nowNs = SystemClock.elapsedRealtimeNanos()
                val rates = frameRates.snapshot(nowNs)
                if (appSettings.camera.previewEnabled) {
                    overlayView.updateFrameRates(rates.analyzerFps, rates.inferenceFps)
                } else {
                    screen.updatePreviewStatus(if (lastFrameCompletionNs > 0L &&
                        nowNs - lastFrameCompletionNs > TimeUnit.SECONDS.toNanos(5))
                        "프레임 처리 대기 중" else previewOffStatus)
                }
                metricsCollector.recordFps(rates.inferenceFps.roundToInt(), nowNs)
                updatePerformanceCountdown()
                updateMlKitLogUi()
            }
        }
        overlayView.configure(
            enabled = appSettings.overlay.enabled,
            showYoloBoxes = appSettings.overlay.showYoloBoxes,
            showMlKitBoxes = appSettings.overlay.showMlKitBoxes,
            showConfidence = appSettings.overlay.showConfidence,
            showFps = appSettings.overlay.showFps,
            staleTimeoutMs = appSettings.overlay.staleTimeoutMs,
            showDistance = appSettings.overlay.showDistance
        )
        if (detectionConfig.debugDetectionLogging) overlayView.diagnosticLog = detectionDebugRecorder::record
        overlayView.mlKitDiagnostics = mlKitDiagnostics
        setContentView(screen.root)
        refreshMlKitLogFiles()
        updateMlKitLogUi()
    }

    private fun updateMlKitDiagnosticActivation() {
        mlKitDiagnostics = sessionMlKitDiagnostics.takeIf { it.enabled() }
        if (::overlayView.isInitialized) overlayView.mlKitDiagnostics = mlKitDiagnostics
    }

    private fun logMlKitConfiguration() {
        mlKitDiagnostics?.event(-1L, "CONFIG") {
            "sdkMode=${mlKitDetector.mode} multipleObjects=true enabled=${appSettings.mlKit.enabled} " +
                "intervalMs=${appSettings.mlKit.detectionIntervalMs} preview=${appSettings.camera.previewEnabled} " +
                "overlay=${appSettings.overlay.enabled} boxes=${appSettings.overlay.showMlKitBoxes} " +
                "staleMs=${appSettings.overlay.staleTimeoutMs} roi=${appSettings.camera.recognitionRoi} " +
                "appliedDiagnostics=${appSettings.debug.enableMlKitDiagnostics}"
        }
    }

    private fun refreshMlKitLogFiles() {
        recordingWork.launch {
            runCatching { withContext(Dispatchers.IO) { mlKitLogRecorder.files().get() } }
                .onSuccess { mlKitHasSavedLog = it.isNotEmpty() }
            if (!destroying) updateMlKitLogUi()
        }
    }

    private fun mlKitFileSessionActive(): Boolean = mlKitLogRecorder.status().phase in listOf(
        MlKitDiagnosticLogRecorder.Phase.STARTING, MlKitDiagnosticLogRecorder.Phase.RECORDING,
        MlKitDiagnosticLogRecorder.Phase.SAVING) || mlKitLogJob?.isActive == true

    private fun toggleMlKitLogging() {
        if (destroying || mlKitLogJob?.isActive == true) return
        if (performanceLogRecorder.isRecording || performanceJob?.isActive == true) {
            Toast.makeText(this, "성능 로그 종료 후 ML Kit 로그를 시작하세요. 동시 기록은 성능 비교에 영향을 줍니다.", Toast.LENGTH_LONG).show()
            return
        }
        val phase = mlKitLogRecorder.status().phase
        if (phase in listOf(MlKitDiagnosticLogRecorder.Phase.STARTING, MlKitDiagnosticLogRecorder.Phase.SAVING)) return
        val stopping = phase == MlKitDiagnosticLogRecorder.Phase.RECORDING
        mlKitLogJob = recordingWork.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (stopping) mlKitLogRecorder.stop().get()
                    else mlKitLogRecorder.start(AppSettingsCodec.snapshotLines(appSettings),
                        appSettings.debug.enableMlKitDiagnostics, temporaryEnable = !appSettings.debug.enableMlKitDiagnostics).get()
                }
                if (!destroying) {
                    updateMlKitDiagnosticActivation()
                    if (!stopping) {
                        logMlKitConfiguration()
                        Toast.makeText(this@MainActivity, "ML Kit 로그 시작 · 성능 로그와 동시 기록하지 않습니다.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (error: Exception) {
                if (!destroying) Toast.makeText(this@MainActivity, "ML Kit 로그 실패: ${error.cause?.message ?: error.message}", Toast.LENGTH_LONG).show()
            } finally {
                mlKitLogJob = null
                if (!destroying) { updateMlKitLogUi(); refreshMlKitLogFiles() }
            }
        }
        updateMlKitLogUi()
    }

    private fun shareMlKitLog() {
        if (destroying || mlKitLogJob?.isActive == true) return
        if (performanceLogRecorder.isRecording || performanceJob?.isActive == true) {
            Toast.makeText(this, "성능 로그 종료 후 공유하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        mlKitLogJob = recordingWork.launch {
            try {
                val file = withContext(Dispatchers.IO) { mlKitLogRecorder.prepareShare().get() }
                if (!destroying) LogShareManager(this@MainActivity).shareMlKit(file)
            } catch (error: Exception) {
                if (!destroying) Toast.makeText(this@MainActivity, "ML Kit 로그 공유 실패: ${error.cause?.message ?: error.message}", Toast.LENGTH_LONG).show()
            } finally {
                mlKitLogJob = null
                if (!destroying) { updateMlKitLogUi(); refreshMlKitLogFiles() }
            }
        }
        updateMlKitLogUi()
    }

    private fun updateMlKitLogUi() {
        updateMlKitDiagnosticActivation()
        val state = mlKitLogRecorder.status()
        val active = state.phase == MlKitDiagnosticLogRecorder.Phase.RECORDING
        val busy = mlKitLogJob?.isActive == true || state.phase in listOf(
            MlKitDiagnosticLogRecorder.Phase.STARTING, MlKitDiagnosticLogRecorder.Phase.SAVING)
        val performanceBusy = performanceLogRecorder.isRecording || performanceJob?.isActive == true
        val seconds = ((SystemClock.elapsedRealtimeNanos() - state.startedElapsedNs) / 1_000_000_000L).coerceAtLeast(0)
        screen.mlKitLogButton.text = if (busy) "ML Kit 저장 중…" else if (active) "ML Kit 로그 종료" else "ML Kit 로그 시작"
        screen.mlKitLogButton.isEnabled = !busy && !performanceBusy
        screen.mlKitShareButton.isEnabled = !busy && !performanceBusy && (active || mlKitHasSavedLog)
        screen.mlKitRecordingTextView.setTextColor(if (active) android.graphics.Color.RED else android.graphics.Color.DKGRAY)
        screen.mlKitRecordingTextView.text = when {
            busy -> "ML Kit 로그 처리 중…"
            active -> String.format(Locale.US, "ML KIT REC %02d:%02d · 누락 %d줄", seconds / 60, seconds % 60, state.droppedLines)
            state.phase == MlKitDiagnosticLogRecorder.Phase.ERROR -> "ML Kit 저장 실패 · TXT 확인 필요: ${state.error}"
            state.phase == MlKitDiagnosticLogRecorder.Phase.SAVED -> "ML Kit 저장 완료 · 누락 ${state.droppedLines}줄"
            performanceBusy -> "성능 로그 중에는 ML Kit 파일 기록을 시작할 수 없습니다."
            appSettings.debug.enableMlKitDiagnostics -> "ML Kit 로그 중지 · Diagnostics 적용 ON"
            else -> "ML Kit 로그 중지 · 시작 시 이번 기록에서만 진단 활성화"
        }
        if (state.phase in listOf(MlKitDiagnosticLogRecorder.Phase.SAVED, MlKitDiagnosticLogRecorder.Phase.ERROR) && state != mlKitObservedCompletion) {
            mlKitObservedCompletion = state
            Toast.makeText(this, if (state.phase == MlKitDiagnosticLogRecorder.Phase.ERROR)
                "ML Kit 로그 저장 실패: ${state.error}. 남은 TXT를 확인하세요."
                else "ML Kit 로그 저장 완료: ${state.fileName} · 누락 ${state.droppedLines}줄", Toast.LENGTH_LONG).show()
            refreshMlKitLogFiles()
        }
        if (!performanceBusy) performanceLogButton.isEnabled = !busy && !active
    }

    private fun openSettings() {
        if (destroying) return
        if (isRecording || performanceLogRecorder.isRecording || performanceJob?.isActive == true) {
            Toast.makeText(this, "녹화 또는 성능 로그 세션을 종료한 뒤 설정을 변경하세요.", Toast.LENGTH_LONG).show()
            return
        }
        val identity = detector.modelIdentity()
        settingsLauncher.launch(Intent(this, SettingsActivity::class.java).apply {
            identity?.let {
                putExtra(SettingsActivity.EXTRA_MODEL_DESCRIPTION,
                    "Model: ${it.assetName}\n입력 크기: ${it.inputWidth}×${it.inputHeight}\nInput: ${it.inputShape} (${it.inputType})\n" +
                        "Output: ${it.outputShape} (${it.outputType})\nSHA256: ${it.sha256}")
            }
        })
    }

    private fun openQuickSettings() {
        if (destroying) return
        if (isRecording || performanceLogRecorder.isRecording || performanceJob?.isActive == true) {
            Toast.makeText(this, "진행 중인 측정 세션을 종료한 뒤 설정을 변경하세요.", Toast.LENGTH_LONG).show()
            return
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 12, 40, 0)
        }
        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                com.samin.objectdetection.camera.YoloResizeMode.entries.map { it.name })
            setSelection(com.samin.objectdetection.camera.YoloResizeMode.entries.indexOf(appSettings.yolo.resizeMode))
            isEnabled = !appSettings.camera.recognitionRoi.enabled
        }
        val confidence = EditText(this).apply {
            hint = "Confidence (0..1)"
            setText(appSettings.yolo.confidenceThreshold.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val geometry = Switch(this).apply { text = "Geometry Filter"; isChecked = appSettings.filters.geometryFilterEnabled }
        val mlKit = Switch(this).apply { text = "ML Kit"; isChecked = appSettings.mlKit.enabled }
        content.addView(android.widget.TextView(this).apply { text = "현재 프리셋: ${appSettings.presetName}" })
        content.addView(mode); content.addView(confidence); content.addView(geometry); content.addView(mlKit)
        content.addView(android.widget.TextView(this).apply { text = "적용을 누르기 전에는 실행 중 설정이 바뀌지 않습니다." })
        AlertDialog.Builder(this)
            .setTitle("빠른 설정")
            .setView(content)
            .setNegativeButton("취소", null)
            .setPositiveButton("적용") { _, _ ->
                val threshold = confidence.text.toString().toFloatOrNull()
                if (threshold == null || threshold !in 0f..1f) {
                    Toast.makeText(this, "Confidence는 0~1이어야 합니다.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val updated = appSettings.copy(
                    presetName = SettingsPresets.CUSTOM,
                    yolo = appSettings.yolo.copy(
                        resizeMode = com.samin.objectdetection.camera.YoloResizeMode.valueOf(mode.selectedItem.toString()),
                        confidenceThreshold = threshold
                    ),
                    filters = appSettings.filters.copy(geometryFilterEnabled = geometry.isChecked),
                    mlKit = appSettings.mlKit.copy(enabled = mlKit.isChecked)
                )
                settingsStore.saveApplied(updated)
                restartForSettings()
            }
            .show()
    }

    /** Stop old frames and release their Interpreter before a new Activity can create another. */
    private fun restartForSettings() {
        if (destroying) return // Also prevents double apply/restart.
        destroying = true
        detectionUiUpdates.close()
        mlKitUiUpdates.close()
        overlayView.clearDetections()
        debugTextView.text = "설정 적용 중…"
        previewOffStatus = "설정 적용 중…"
        screen.updatePreviewStatus(previewOffStatus)
        val stopped = if (::cameraController.isInitialized) cameraController.stopAsync()
            else CompletableFuture.completedFuture(Unit)
        recordingWork.launch {
            val drained = runCatching { withContext(Dispatchers.IO) { stopped.get() } }
                .onFailure { Log.e(TAG, "settings apply: analyzer shutdown failed; interpreter retained", it) }.isSuccess
            if (!drained) {
                if (!isDestroyed) {
                    debugTextView.text = "분석 종료 실패: 앱을 다시 시작하세요."
                    previewOffStatus = debugTextView.text.toString()
                    screen.updatePreviewStatus(previewOffStatus)
                }
                return@launch
            }
            val resourcesClosed = runCatching {
                closeMlKitAfterCaptureDrain()
                withContext(Dispatchers.IO) { mlKitLogRecorder.sourceEnded(sessionId).get() }
                withContext(Dispatchers.IO) { detector.close() }
            }.onFailure { Log.e(TAG, "settings apply: resource shutdown failed", it) }.isSuccess
            if (!resourcesClosed) {
                if (!isDestroyed) {
                    previewOffStatus = "종료 실패: 앱을 다시 시작하세요."
                    debugTextView.text = previewOffStatus
                    screen.updatePreviewStatus(previewOffStatus)
                    Toast.makeText(this@MainActivity, previewOffStatus, Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            Log.i(TAG, "[SETTINGS_RESTART] analyzerStopped=true oldInterpreterClosed=true oldMlKitClosed=true oldMode=${mlKitDetector.mode}")
            if (!isFinishing && !isDestroyed) recreate()
        }
    }

    /** No new frames after analyzer drain. Existing SDK Tasks retain their leases until terminal. */
    private suspend fun closeMlKitAfterCaptureDrain() {
        captureWork.coroutineContext[Job]?.children?.toList()?.joinAll()
        // Also covers a late child if analyzer shutdown failed during Activity destruction.
        captureWork.coroutineContext[Job]?.cancelAndJoin()
        withContext(Dispatchers.IO) {
            mlKitInputBuffer.close()
            if (::mlKitDetector.isInitialized) mlKitDetector.close()
        }
    }

    private fun checkPermissionAndStart() {
        val permissions = mutableListOf<String>()
        if (!hasCameraPermission()) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (!hasLocationPermission()) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isEmpty()) {
            userLocationTracker.start()
            startCamera()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun registerScreenRecordingReceiver() {
        val filter = IntentFilter().apply {
            addAction(ScreenRecordService.ACTION_RECORDING_STOPPED)
            addAction(ScreenRecordService.ACTION_RECORDING_ERROR)
            addAction(ScreenRecordService.ACTION_RECORDING_STARTED)
            addAction(ScreenRecordService.ACTION_RECORDING_PUBLISH_ERROR)
        }
        ContextCompat.registerReceiver(
            applicationContext,
            screenRecordingReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun startCamera() {
        frameRates.start(SystemClock.elapsedRealtimeNanos())
        cameraController = CameraController(
            context = this,
            lifecycleOwner = this,
            previewView = screen.previewView,
            detectIntervalMs = detectionConfig.detectIntervalMs,
            enableDiagnostics = detectionConfig.enableDetectorDiagnostics,
            targetWidth = appSettings.camera.requestedWidth,
            targetHeight = appSettings.camera.requestedHeight,
            cameraPreviewEnabled = appSettings.camera.previewEnabled,
            distanceMetadataEnabled = appSettings.distance.cameraHeightCm != null &&
                appSettings.distance.downwardPitchDegrees != null &&
                appSettings.distance.opticsMode == DistanceOpticsMode.AUTO,
            listener = object : CameraController.Listener {
                override fun onAnalyzerStarted(timing: CameraFrameTiming): FrameLatencyRecorder? =
                    if (performanceLogRecorder.isRecording) performanceLogRecorder.beginFrameLatency(frameTrace(timing)) else null

                override fun onFrameReceived(frameTimestampMs: Long, analyzerReceivedMs: Long) {
                    metricsCollector.recordFrameReceived(SystemClock.elapsedRealtimeNanos())
                    frameRates.received(SystemClock.elapsedRealtimeNanos())
                    verboseLog(DETECTION_TIMING_TAG) {
                        "frameTimestamp=$frameTimestampMs analyzerReceived=$analyzerReceivedMs " +
                            "cameraToAnalyzerMs=${(analyzerReceivedMs - frameTimestampMs).coerceAtLeast(0L)}"
                    }
                }

                override fun onFrameSkipped(reason: CameraController.SkipReason, skippedFrameCount: Long) {
                    metricsCollector.recordFrameSkipped()
                    verboseLog(DETECTION_TIMING_TAG) { "skipFrame reason=$reason skipped=$skippedFrameCount" }
                }

                override fun onFrame(bitmap: Bitmap, timing: CameraFrameTiming, rotationDegrees: Int) {
                    processBitmap(bitmap, timing, rotationDegrees)
                }

                override fun onCameraStarted() {
                    debugTextView.text = "카메라 시작됨"
                    if (lastFrameCompletionNs == 0L) previewOffStatus = "카메라 준비됨 — 첫 추론 대기 중"
                    screen.updatePreviewStatus(previewOffStatus)
                }

                override fun onCameraError(error: Throwable) {
                    Log.e(TAG, "startCamera error", error)
                    debugTextView.text = "카메라 시작 실패: ${error.message}"
                    previewOffStatus = debugTextView.text.toString()
                    screen.updatePreviewStatus(previewOffStatus)
                }

                override fun onFrameError(error: Throwable, timing: CameraFrameTiming?) {
                    if (timing != null) recordFrameFailure(error, timing)
                    else Log.e(TAG, "analyze error", error)
                }
            }
        )
        cameraController.start()
    }

    private fun processBitmap(bitmap: Bitmap, frameTiming: CameraFrameTiming, rotationDegrees: Int) {
        if (destroying) return
        actualAnalysisWidth = bitmap.width
        actualAnalysisHeight = bitmap.height
        val start = System.currentTimeMillis()
        val frameReceivedTimeMs = frameTiming.frameTimestampMs
        metricsCollector.recordFrameAnalyzed()
        verboseLog(DETECTION_TIMING_TAG) {
            "detectionStart=$start frameReceived=$frameReceivedTimeMs"
        }

        // Both detectors receive the same ROI in the already rotated full-frame coordinate space.
        val mlKitPrepareStartNs = SystemClock.elapsedRealtimeNanos()
        maybeRunMlKitDetection(bitmap, bitmap.width, bitmap.height, frameTrace(frameTiming), frameTiming.latency)
        val mlKitPrepareNs = SystemClock.elapsedRealtimeNanos() - mlKitPrepareStartNs
        val mlKitInFlight = isMlKitProcessing.get()

        val pipelineResult = detectionPipeline.process(bitmap, frameReceivedTimeMs, rotationDegrees, frameTiming.frameId,
            frameTiming.distanceGeometry)
        val completedNs = SystemClock.elapsedRealtimeNanos()
        val completedAtMs = System.currentTimeMillis()
        previewOffStatus = "객체 인식 실행 중"
        lastFrameCompletionNs = completedNs
        metricsCollector.recordInferenceSucceeded(completedNs)
        frameRates.succeeded(completedNs)
        val rates = frameRates.snapshot(completedNs)
        val currentFps = rates.inferenceFps.roundToInt()
        metricsCollector.recordFps(currentFps, completedNs)
        val width = pipelineResult.frameWidth
        val height = pipelineResult.frameHeight
        val cropRect = pipelineResult.cropRect
        val mapped = pipelineResult.mappedDetections
        val visibleMapped = pipelineResult.visibleDetections
        val overlayDetections = pipelineResult.overlayDetections
        val warningDetections = pipelineResult.warningDetections
        val ignoredLabels = pipelineResult.ignoredLabels
        val userLocationSnapshot = pipelineResult.userLocationSnapshot
        val inferenceTime = pipelineResult.inferenceTimeMs
        val detectorDiagnostics = detector.frameDiagnostics()
        val trace = frameTrace(frameTiming).copy(
            inferenceStartedAtMs = detectorDiagnostics?.inferenceStartedAtMs,
            inferenceStartedElapsedNs = detectorDiagnostics?.inferenceStartedElapsedNs,
            inferenceEndedElapsedNs = detectorDiagnostics?.inferenceEndedElapsedNs,
            completedAtMs = completedAtMs, completedElapsedNs = completedNs)
        val topOverlayObject = pipelineResult.topOverlayObject
        metricsCollector.recordYoloDetections(
            beforeFilter = mapped,
            afterSmallBoxFilter = visibleMapped,
            afterPolicyFilter = warningDetections,
            timestampMs = start
        )
        metricsCollector.recordYoloInferenceTime(inferenceTime)
        val warningCandidates = warningCandidateSelector.eligibleCandidates(warningDetections,
            detectionConfig.ignoreTopRatioForGuide, detectionConfig.maxGuideObjectCount) { detection ->
            warningCooldownManager.buildKey(
                    label = detection.label,
                    priority = detection.objectPriority,
                    proximityLevel = detection.proximityLevel,
                    horizontalPosition = detection.horizontalPosition
                )
        }
        val crowdDecision = WarningPolicy.resolveCrowdDecision(warningDetections)
        val crowdCandidate = crowdDecision.toWarningCandidate()
        val selectedCandidate = warningCandidateSelector.selectWithCrowd(warningCandidates, crowdCandidate)
        val candidateFinalizedNs = frameTiming.latency?.nowNs() ?: -1L
        val selectedCooldownPassed = selectedCandidate?.let { candidate ->
            warningCooldownManager.canNotify(candidate.warningKey, start)
        } ?: false
        val selectedFeedback = selectedCandidate?.feedback
        val warningSubmittedNs = frameTiming.latency?.nowNs() ?: -1L
        val outputResult = warningOutputController.submit(
            candidate = selectedCandidate,
            cooldownAllowed = selectedCooldownPassed,
            timestampMs = start,
            latency = frameTiming.latency
        )
        frameTiming.latency?.let { latency ->
            latency.record(FrameLatencyEvent.Warning(candidateFinalizedNs, warningSubmittedNs, latency.nowNs(),
                selectedCandidate != null, outputResult.accepted, outputResult.skippedReason))
        }
        val vibrationExecuted = outputResult.vibrationExecuted
        val beepExecuted = outputResult.beepExecuted
        val ttsExecuted = outputResult.ttsExecuted
        val ttsSkippedReason = outputResult.skippedReason
        Log.d(
            VIBRATION_OUTPUT_TAG,
            "label=${selectedCandidate?.label ?: "none"} vibration=${selectedCandidate?.feedback?.vibrationLevel ?: FeedbackLevel.NONE} " +
                "executed=$vibrationExecuted cooldown=$selectedCooldownPassed enabled=${appSettings.warning.enableActualVibration}"
        )
        Log.d(
            BEEP_OUTPUT_TAG,
            "label=${selectedCandidate?.label ?: "none"} beep=${selectedCandidate?.feedback?.beepLevel ?: FeedbackLevel.NONE} " +
                "executed=$beepExecuted cooldown=$selectedCooldownPassed enabled=${appSettings.warning.enableActualBeep}"
        )
        Log.d(
            TTS_OUTPUT_TAG,
            "label=${selectedCandidate?.label ?: "none"} voice=${selectedCandidate?.feedback?.voiceLevel ?: FeedbackLevel.NONE} " +
                "executed=$ttsExecuted skippedReason=${ttsSkippedReason ?: "none"} cooldown=$selectedCooldownPassed enabled=${appSettings.warning.enableActualTts}"
        )
        val crowdCooldownPassed = selectedCandidate?.warningKey == crowdDecision.warningKey && selectedCooldownPassed
        logCrowdDecision(crowdDecision, crowdCooldownPassed)
        logSelectedWarningCandidate(selectedCandidate, selectedCooldownPassed, vibrationExecuted, beepExecuted, ttsExecuted)
        logWarningOutput(selectedCandidate, selectedCooldownPassed, vibrationExecuted, beepExecuted, ttsExecuted, ttsSkippedReason)
        val feedbackRiskLevel = selectedFeedback?.riskLevel ?: RiskLevel.NONE
        val feedbackBeepLevel = selectedFeedback?.beepLevel ?: FeedbackLevel.NONE
        val feedbackVoiceLevel = selectedFeedback?.voiceLevel ?: FeedbackLevel.NONE
        val feedbackVibrationLevel = selectedFeedback?.vibrationLevel ?: FeedbackLevel.NONE
        val feedbackMessage = selectedFeedback?.message
        val feedbackShouldNotify = selectedFeedback?.shouldNotify ?: false
        val feedbackPriority = selectedCandidate?.priority
        val feedbackWarningKey = selectedCandidate?.warningKey
        val feedbackLabel = selectedCandidate?.label ?: "none"
        val feedbackProximityLevel = selectedCandidate?.proximityLevel
        val warningMotionDirection = selectedCandidate?.let { candidate ->
            overlayDetections.firstOrNull { it.label == candidate.label }?.motionDirection
        }
        val warningApproachSpeedLevel = selectedCandidate?.let { candidate ->
            overlayDetections.firstOrNull { it.label == candidate.label }?.approachSpeedLevel
        }
        val warningObjectMovementState = selectedCandidate?.let { candidate ->
            overlayDetections.firstOrNull { it.label == candidate.label }?.objectMovementState
        }
        val warningUserObjectRelation = selectedCandidate?.let { candidate ->
            overlayDetections.firstOrNull { it.label == candidate.label }?.userObjectRelation
        }
        val warningCategory = selectedCandidate?.let { candidate ->
            overlayDetections.firstOrNull { it.label == candidate.label }?.riskObjectCategory
        }
        metricsCollector.recordWarning(feedbackRiskLevel)
        Log.d(
            WARNING_FEEDBACK_TAG,
                "selectedCandidate label=$feedbackLabel priority=$feedbackPriority proximityLevel=$feedbackProximityLevel " +
                "riskLevel=$feedbackRiskLevel message=$feedbackMessage beepLevel=$feedbackBeepLevel " +
                "vibrationLevel=$feedbackVibrationLevel voiceLevel=$feedbackVoiceLevel " +
                "cooldownPassed=$selectedCooldownPassed vibrationExecuted=$vibrationExecuted " +
                "beepExecuted=$beepExecuted ttsExecuted=$ttsExecuted " +
                "enableActualVibration=${appSettings.warning.enableActualVibration} enableActualBeep=${appSettings.warning.enableActualBeep} enableActualTts=${appSettings.warning.enableActualTts} " +
                "shouldNotify=$feedbackShouldNotify key=$feedbackWarningKey"
        )
        logDetectionTiming(DETECTION_TIMING_TAG) {
                "[DetectionTiming] frameTimestamp=${frameTiming.frameTimestampMs} " +
                "analyzerReceived=${frameTiming.analyzerReceivedMs} " +
                "cameraToAnalyzerMs=${frameTiming.cameraToAnalyzerMs} " +
                "bitmapConvertMs=${frameTiming.bitmapConversionMs} " +
                "cropMs=${pipelineResult.timing.cropTimeMs} resizeMs=${detectorDiagnostics?.resizeTimeMs ?: -1} " +
                "inputBufferMs=${detectorDiagnostics?.inputBufferTimeMs ?: -1} " +
                "getPixelsNs=${detectorDiagnostics?.getPixelsTimeNs ?: -1} rgbWriteNs=${detectorDiagnostics?.rgbWriteTimeNs ?: -1} " +
                "inputDiagnosticsNs=${detectorDiagnostics?.inputDiagnosticsTimeNs ?: -1} " +
                "cameraToBitmapNs=${frameTiming.cameraToBitmapNs} rotationNs=${frameTiming.rotationNs} " +
                "mlKitPrepareNs=$mlKitPrepareNs mlKitInFlight=$mlKitInFlight " +
                "inferenceMs=$inferenceTime outputCopyMs=${detectorDiagnostics?.outputCopyTimeMs ?: -1} " +
                "candidateScanMs=${detectorDiagnostics?.candidateScanTimeMs ?: -1} " +
                "nmsMs=${detectorDiagnostics?.nmsTimeMs ?: -1} " +
                "postprocessMs=${pipelineResult.timing.postprocessTimeMs} " +
                "pipelineMs=${pipelineResult.timing.pipelineTimeMs} " +
                "frameAgeAtInferenceStartMs=${trace.frameAgeAtInferenceMs} " +
                "rawCount=${mapped.size} visibleCount=${visibleMapped.size} " +
                "overlayWhitelistCount=${overlayDetections.size} policyFilteredCount=${warningDetections.size} " +
                "ignoredLabels=${formatIgnoredLabels(ignoredLabels)} " +
                "emptyReason=${buildEmptyOverlayReason(mapped, visibleMapped, warningDetections, overlayDetections)}"
        }
        val needsCapture = captureRequested.getAndSet(false)
        if (isRecording || needsCapture) {
            val evaluationSnapshot = buildEvaluationSnapshot(
                bitmap = bitmap,
                copyBitmap = needsCapture,
                detections = overlayDetections,
                evaluationDetections = mapped,
                frameTimestampMs = frameReceivedTimeMs,
                roi = cropRect,
                rawDetectionCount = mapped.size,
                visibleDetectionCount = visibleMapped.size,
                warningDetectionCount = warningDetections.size,
                topDetection = topOverlayObject,
                selectedWarningCandidate = selectedCandidate,
                inferenceTimeMs = inferenceTime,
                fps = currentFps,
                userLocationSnapshot = userLocationSnapshot,
                stageCounts = pipelineResult.stageCounts,
                rawCoordinateMin = detectorDiagnostics?.rawCoordinateMin,
                rawCoordinateMax = detectorDiagnostics?.rawCoordinateMax,
                trace = trace, rates = rates
            )
            if (needsCapture) saveEvaluationSnapshot(evaluationSnapshot)
            if (isRecording) {
                evaluationDataRecorder.appendRecordingDetections(evaluationSnapshot)
            }
        }

        // Every successful pipeline completion is measured, even if its UI callback is replaced.
        val performanceSummary = metricsCollector.recordPerformance(
            preprocessMs = detectorDiagnostics?.preprocessTimeMs ?: -1L,
            inferenceMs = inferenceTime,
            postprocessMs = (detectorDiagnostics?.postprocessTimeMs ?: 0L) +
                pipelineResult.timing.postprocessTimeMs,
            pipelineMs = pipelineResult.timing.pipelineTimeMs,
            frameAgeAtOverlayMs = -1L,
            fps = currentFps
        )
        performanceSummary?.let { summary -> Log.i(DETECTION_TIMING_TAG, summary) }
        if (performanceLogRecorder.isRecording) {
            performanceLogRecorder.recordFrame(
                PerformanceFrameRecord(
                    timestampMs = completedAtMs,
                    bitmapConversionMs = frameTiming.bitmapConversionMs,
                    preprocessMs = detectorDiagnostics?.preprocessTimeMs ?: -1L,
                    cropMs = pipelineResult.timing.cropTimeMs,
                    resizeMs = detectorDiagnostics?.resizeTimeMs ?: -1L,
                    inferenceMs = inferenceTime,
                    outputParsingMs = (detectorDiagnostics?.outputCopyTimeMs ?: 0L) +
                        (detectorDiagnostics?.candidateScanTimeMs ?: 0L),
                    nmsMs = detectorDiagnostics?.nmsTimeMs ?: -1L,
                    postprocessMs = (detectorDiagnostics?.postprocessTimeMs ?: 0L) +
                        pipelineResult.timing.postprocessTimeMs,
                    pipelineMs = pipelineResult.timing.pipelineTimeMs,
                    frameAgeAtInferenceStartMs = trace.frameAgeAtInferenceMs,
                    frameAgeAtOverlayMs = -1L,
                    detectionCount = mapped.size,
                    visibleDetectionCount = visibleMapped.size,
                    warningDetectionCount = warningDetections.size,
                    fps = currentFps, analyzerFps = rates.analyzerFps,
                    inferenceFps = rates.inferenceFps, trace = trace,
                    execution = detector.executionInfo(),
                    cameraToBitmapNs = frameTiming.cameraToBitmapNs,
                    rotationNs = frameTiming.rotationNs,
                    inputBufferMs = detectorDiagnostics?.inputBufferTimeMs ?: -1L,
                    getPixelsNs = detectorDiagnostics?.getPixelsTimeNs ?: -1L,
                    rgbWriteNs = detectorDiagnostics?.rgbWriteTimeNs ?: -1L,
                    inputDiagnosticsNs = detectorDiagnostics?.inputDiagnosticsTimeNs ?: -1L,
                    outputDiagnosticsNs = detectorDiagnostics?.outputDiagnosticsTimeNs ?: -1L,
                    nativeInferenceNs = detectorDiagnostics?.nativeInferenceTimeNs ?: -1L,
                    mlKitPrepareNs = mlKitPrepareNs,
                    mlKitInFlight = mlKitInFlight,
                    roiDescription = "frame=${width}x$height roi=[${cropRect.left},${cropRect.top},${cropRect.right},${cropRect.bottom}]",
                    rotationDegrees = rotationDegrees,
                    totalProcessingMs = if (frameTiming.analyzerReceivedElapsedNs > 0L)
                        (completedNs - frameTiming.analyzerReceivedElapsedNs).coerceAtLeast(0L) / 1_000_000L else -1L
                )
            )
            performanceSummary?.let(performanceLogRecorder::recordSummary)
        }
        if (appSettings.camera.previewEnabled) postLatestDetectionUiUpdate {
            overlayView.updateRecognitionRegion(width, height,
                FrameRoi(cropRect.left, cropRect.top, cropRect.right, cropRect.bottom),
                detectionConfig.recognitionRoi.showRegion)
            logDetectionTiming(DETECTION_TIMING_TAG) {
                val overlayUpdateTimeMs = System.currentTimeMillis()
                val resultAgeMs = trace.captureAgeMsAt(SystemClock.elapsedRealtimeNanos())
                    "overlayUpdate=$overlayUpdateTimeMs frameAgeAtOverlayMs=${resultAgeMs} " +
                    "rawCount=${mapped.size} visibleCount=${visibleMapped.size} " +
                    "overlayWhitelistCount=${overlayDetections.size} policyFilteredCount=${warningDetections.size} " +
                    "ignoredLabels=${formatIgnoredLabels(ignoredLabels)} " +
                    "emptyReason=${buildEmptyOverlayReason(mapped, visibleMapped, warningDetections, overlayDetections)}"
            }
            overlayView.updateDetections(overlayDetections, width, height, inferenceTime, currentFps,
                trace, rates.analyzerFps, rates.inferenceFps)
            if (
                detectionConfig.overlayDebugMode == OverlayDebugMode.NONE ||
                feedbackMessage == null ||
                !feedbackShouldNotify
            ) {
                warningMessageTextView.text = ""
                warningMessageTextView.visibility = View.GONE
            } else {
                warningMessageTextView.text = feedbackMessage
                warningMessageTextView.visibility = View.VISIBLE
            }
            if (detectionConfig.overlayDebugMode == OverlayDebugMode.FULL) {
                debugTextView.visibility = View.VISIBLE
                debugTextView.text = buildString {
                    appendLine("Frame: ${width}x$height / crop: ${cropRect.width()}x${cropRect.height()}")
                    appendLine("YOLO raw detection count: ${mapped.size}")
                    appendLine("small box filter count: ${visibleMapped.size}")
                    appendLine("overlay whitelist count: ${overlayDetections.size}")
                    appendLine("warning policy count: ${warningDetections.size}")
                    appendLine("Ignored: ${formatIgnoredLabels(ignoredLabels)}")
                    appendLine("selected warning object label: $feedbackLabel")
                    appendLine("proximity: $feedbackProximityLevel")
                    appendLine("object motion state: $warningObjectMovementState")
                    appendLine("user-object relation: $warningUserObjectRelation")
                    appendLine("user motion state: ${userLocationSnapshot.motionState}")
                    appendLine("GPS speed: ${formatSpeed(userLocationSnapshot.speedMps)}")
                    appendLine("YOLO inference time: ${inferenceTime}ms / FPS=${rates.inferenceFps} / Analyzer FPS=${rates.analyzerFps}")
                    appendLine("ML Kit detection count: $lastMlKitCount / ${lastMlKitTimeMs}ms")
                    if (topOverlayObject != null) {
                        append("Top overlay: ${topOverlayObject.label} ${String.format(Locale.US, "%.2f", topOverlayObject.confidence)}")
                    } else {
                        append("Top overlay: none")
                    }
                    appendLine()
                    append("Guide: ${feedbackMessage ?: "none"}")
                    appendLine()
                    appendLine("Risk: $feedbackRiskLevel")
                    appendLine("Feedback: beep=$feedbackBeepLevel / voice=$feedbackVoiceLevel / vibrate=$feedbackVibrationLevel")
                    appendLine("PolicyFeedback: label=$feedbackLabel / priority=$feedbackPriority / proximity=$feedbackProximityLevel / risk=$feedbackRiskLevel / message=${feedbackMessage ?: "none"} / beep=$feedbackBeepLevel / voice=$feedbackVoiceLevel / vibrate=$feedbackVibrationLevel / cooldown=$selectedCooldownPassed / vibrationExecuted=$vibrationExecuted / beepExecuted=$beepExecuted / ttsExecuted=$ttsExecuted / notify=$feedbackShouldNotify / key=$feedbackWarningKey")
                    appendLine("PolicyMessage: ${feedbackMessage ?: "none"}")
                    appendLine("Crowd: total=${crowdDecision.totalPersonCount} / center=${crowdDecision.centerPersonCount} / near=${crowdDecision.nearPersonCount} / level=${crowdDecision.crowdLevel} / message=${crowdDecision.message ?: "none"} / cooldown=$crowdCooldownPassed")
                    appendLine("Motion: direction=$warningMotionDirection / approachSpeed=$warningApproachSpeedLevel")
                    appendLine("GPS accuracy: ${formatAccuracy(userLocationSnapshot.accuracyMeters)}")
                    append("Policy: category=$warningCategory / proximity=$feedbackProximityLevel")
                    appendLine()
                    append(metricsCollector.buildSummary())
                }
            } else {
                debugTextView.text = ""
                debugTextView.visibility = View.GONE
            }
        }

        verboseLog(TAG) {
            "frame=${width}x$height, warningDetections=${warningDetections.size}, " +
                "overlayDetections=${overlayDetections.size}, ignoredLabels=${formatIgnoredLabels(ignoredLabels)}, " +
                "time=${inferenceTime}ms"
        }
    }

    private fun buildEvaluationSnapshot(
        bitmap: Bitmap,
        copyBitmap: Boolean,
        detections: List<DetectionResult>,
        evaluationDetections: List<DetectionResult>,
        frameTimestampMs: Long,
        roi: Rect,
        rawDetectionCount: Int,
        visibleDetectionCount: Int,
        warningDetectionCount: Int,
        topDetection: DetectionResult?,
        selectedWarningCandidate: WarningCandidate?,
        inferenceTimeMs: Long,
        fps: Int,
        userLocationSnapshot: com.samin.objectdetection.location.UserLocationSnapshot,
        stageCounts: com.samin.objectdetection.detector.DetectionStageCounts?,
        rawCoordinateMin: Float?,
        rawCoordinateMax: Float?,
        trace: FrameTrace,
        rates: FrameRateMeter.Rates
    ): DetectionFrameSnapshot {
        return DetectionFrameSnapshot(
            bitmap = if (copyBitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap,
            detections = detections,
            evaluationDetections = evaluationDetections,
            frameTimestampMs = frameTimestampMs,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            roiApplied = roi.left != 0 || roi.top != 0 || roi.right != bitmap.width || roi.bottom != bitmap.height,
            roi = Rect(roi),
            rawDetectionCount = rawDetectionCount,
            visibleDetectionCount = visibleDetectionCount,
            warningDetectionCount = warningDetectionCount,
            topDetection = topDetection,
            selectedWarningCandidate = selectedWarningCandidate,
            inferenceTimeMs = inferenceTimeMs,
            fps = fps,
            userLocationSnapshot = userLocationSnapshot,
            stageCounts = stageCounts,
            rawCoordinateMin = rawCoordinateMin,
            rawCoordinateMax = rawCoordinateMax,
            trace = trace, analyzerFps = rates.analyzerFps, inferenceFps = rates.inferenceFps
        )
    }

    private fun captureEvaluationFrame() {
        captureRequested.set(true)
        Toast.makeText(this, "다음 분석 프레임을 저장합니다.", Toast.LENGTH_SHORT).show()
    }

    private fun saveEvaluationSnapshot(snapshot: DetectionFrameSnapshot) {
        captureWork.launch {
            try {
                val timestamp = snapshot.frameTimestampMs.toString()
                val files = evaluationDataRecorder.saveCapture(snapshot, timestamp)
                runOnUiThread {
                    val gallerySaved = files.galleryOriginal != null || files.galleryOverlay != null
                    Toast.makeText(
                        this@MainActivity,
                        if (gallerySaved) {
                            "캡쳐 저장: Pictures/GOTORO"
                        } else {
                            "캡쳐 저장: ${files.image.name} (앱 전용 폴더)"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture evaluation frame failed", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "캡쳐 저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                snapshot.bitmap.recycle()
            }
        }
    }

    private fun toggleEvaluationRecording() {
        if (isRecording) {
            stopEvaluationRecording()
        } else {
            startEvaluationRecording()
        }
    }

    private fun togglePerformanceLogging() {
        if (destroying || performanceJob?.isActive == true) return
        if (performanceLogRecorder.isRecording) stopPerformanceLogging() else startPerformanceLogging()
    }

    private fun startPerformanceLogging() {
        if (mlKitFileSessionActive()) {
            Toast.makeText(this, "ML Kit 로그 종료 후 성능 측정을 시작하세요. 동시 기록은 성능 비교에 영향을 줍니다.", Toast.LENGTH_LONG).show()
            return
        }
        val startedAtMs = System.currentTimeMillis()
        val startedElapsedNs = SystemClock.elapsedRealtimeNanos()
        val model = checkNotNull(detector.modelIdentity()) { "Loaded YOLO tensor metadata is required for performance logging" }
        val header = PerformanceLogHeader(
            startedAtMs = startedAtMs,
            modelName = model.assetName,
            detectorName = DETECTOR_TYPE,
            inputWidth = model.inputWidth,
            inputHeight = model.inputHeight,
            cameraWidth = actualAnalysisWidth.takeIf { it > 0 } ?: appSettings.camera.requestedWidth,
            cameraHeight = actualAnalysisHeight.takeIf { it > 0 } ?: appSettings.camera.requestedHeight,
            resizeMode = detectionConfig.yoloResizeMode,
            yoloThreads = detectionConfig.interpreterThreadCount,
            mlKitEnabled = detectionConfig.enableMlKitDetection,
            geometryFilterEnabled = detectionConfig.bollardGeometryFilterEnabled,
            adaptiveTemporalEnabled = detectionConfig.adaptiveTemporalEnabled,
            confidenceThreshold = detectionConfig.confidenceThreshold,
            nmsThreshold = detectionConfig.nmsThreshold,
            presetName = appSettings.presetName,
            settingsSchemaVersion = appSettings.schemaVersion,
            modelSha256 = model.sha256,
            requestedCameraWidth = appSettings.camera.requestedWidth,
            requestedCameraHeight = appSettings.camera.requestedHeight,
            appVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "unknown",
            settingsSnapshot = AppSettingsCodec.snapshotLines(appSettings),
            sessionId = sessionId, startedElapsedNs = startedElapsedNs,
            systemDiagnosticsEnabled = appSettings.performanceLog.systemDiagnosticsEnabled,
            batteryDiagnosticsEnabled = appSettings.performanceLog.batteryDiagnosticsEnabled,
            systemSampleIntervalMs = appSettings.performanceLog.sampleIntervalMs,
            cameraPreviewEnabled = appSettings.camera.previewEnabled,
            durationMinutes = appSettings.performanceLog.durationMinutes,
            roiDescription = detectionConfig.recognitionRoi.description(detectionConfig.useCenterSquareCrop),
            execution = detector.executionInfo(),
            inputTensorShape = model.inputShape, inputTensorType = model.inputType,
            outputTensorShape = model.outputShape, outputTensorType = model.outputType
        )
        performanceLogButton.isEnabled = false
        screen.setMeasurementLocked(true)
        performanceJob = recordingWork.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    performanceLogRecorder.start(header,
                        onStarted = { actual -> metricsCollector.startPerformanceSession(actual.startedAtMs, actual.startedElapsedNs) },
                        snapshotOnAutoStop = { metricsCollector.stopPerformanceSessionSnapshot(stoppedElapsedNs = SystemClock.elapsedRealtimeNanos()) },
                        onAutoStopped = { result -> runOnUiThread {
                            if (!destroying) {
                                screen.setMeasurementLocked(false)
                                performanceLogButton.text = "성능 로그 시작"
                                performanceRecordingTextView.visibility = View.GONE
                                result.fold(
                                    { savedLog -> if (savedLog != null) Toast.makeText(this@MainActivity, "시간 종료 · 자동 저장 완료: ${savedLog.displayName}", Toast.LENGTH_LONG).show() },
                                    { error -> Toast.makeText(this@MainActivity, "성능 로그 저장 실패: ${error.message}", Toast.LENGTH_LONG).show() })
                            }
                        } })
                }
                if (destroying) return@launch
                performanceLogButton.text = "성능 로그 종료"
                performanceRecordingTextView.visibility = View.VISIBLE
                updatePerformanceCountdown()
                Toast.makeText(this@MainActivity, "성능 로그 시작: ${saved.displayName}", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                metricsCollector.stopPerformanceSessionSnapshot(stoppedElapsedNs = SystemClock.elapsedRealtimeNanos())
                Log.e(TAG, "start performance logging failed", error)
                Toast.makeText(this@MainActivity, "성능 로그 시작 실패: ${error.message}", Toast.LENGTH_SHORT).show()
            } finally {
                performanceLogButton.isEnabled = true
                if (!performanceLogRecorder.isRecording) screen.setMeasurementLocked(false)
            }
        }
    }

    private fun updatePerformanceCountdown() {
        val remaining = performanceLogRecorder.remainingMs ?: return
        val seconds = (remaining + 999) / 1000
        performanceRecordingTextView.text = if (seconds == 0L) "PERF 저장 중…" else
            String.format(Locale.US, "PERF %02d:%02d", seconds / 60, seconds % 60)
    }

    private fun stopPerformanceLogging(reason: PerformanceStopReason = PerformanceStopReason.MANUAL) {
        if (performanceJob?.isActive == true) return
        performanceLogButton.isEnabled = false
        performanceRecordingTextView.visibility = View.GONE
        val snapshot = metricsCollector.stopPerformanceSessionSnapshot(stoppedElapsedNs = SystemClock.elapsedRealtimeNanos())
        performanceJob = recordingWork.launch {
            try {
                val saved = performanceLogRecorder.stop(snapshot, reason)
                if (destroying) return@launch
                performanceLogButton.text = "성능 로그 시작"
                Toast.makeText(
                    this@MainActivity,
                    if (saved != null) {
                        "성능 로그 저장 완료: ${saved.displayPath}/${saved.displayName}"
                    } else {
                        "진행 중인 성능 로그가 없습니다."
                    },
                    Toast.LENGTH_LONG
                ).show()
            } catch (error: Exception) {
                Log.e(TAG, "stop performance logging failed", error)
                performanceLogButton.text = "성능 로그 시작"
                Toast.makeText(this@MainActivity, "성능 로그 저장 실패: ${error.message}", Toast.LENGTH_LONG).show()
            } finally {
                performanceLogButton.isEnabled = true
                screen.setMeasurementLocked(false)
            }
        }
    }

    private fun startEvaluationRecording() {
        recordingButton.isEnabled = false
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        screenCapturePermissionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startScreenRecording(resultCode: Int, data: Intent) {
        val timestamp = System.currentTimeMillis().toString()
        val id = UUID.randomUUID().toString()
        if (!recordingState.begin(id)) return
        recordingFinished = CompletableFuture()
        recordingButton.text = "녹화 시작 중…"
        recordingButton.isEnabled = false
        recordingWork.launch {
          try {
            val files = withContext(Dispatchers.IO) {
                val video = File(evaluationDataRecorder.recordingsDir, "${timestamp}_screen.mp4")
                video to evaluationDataRecorder.startRecordingLog(timestamp, id)
            }
            activeRecordingVideoFile = files.first
            activeRecordingDetectionsFile = files.second
            if (destroying || recordingState.phase != RecordingState.Phase.STARTING) {
                finishRecordingState(id, "ERROR", "앱 종료로 녹화를 시작하지 못했습니다.")
                return@launch
            }
            val serviceIntent = Intent(this@MainActivity, ScreenRecordService::class.java)
                .setAction(ScreenRecordService.ACTION_START)
                .putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
                .putExtra(ScreenRecordService.EXTRA_OUTPUT_PATH, files.first.absolutePath)
                .putExtra(ScreenRecordService.EXTRA_SESSION_ID, id)
                .putExtra(ScreenRecordService.EXTRA_APP_SESSION_ID, sessionId)
            ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
            awaitRecordingAcknowledgement(id)
          } catch (e: Exception) {
            Log.e(TAG, "start screen record service failed", e)
            finishRecordingState(id, "ERROR", "화면 녹화 시작 실패: ${e.message}")
          }
        }
    }

    private fun stopEvaluationRecording() {
        val wasStarting = recordingState.phase == RecordingState.Phase.STARTING
        if (!recordingState.requestStop()) return
        val id = recordingState.sessionId ?: return
        if (!destroying) { recordingButton.text = "녹화 저장 중…"; recordingButton.isEnabled = false }
        // If log preparation is still running, that job aborts before starting the service.
        if (wasStarting && activeRecordingVideoFile == null) return
        try {
            val serviceIntent = Intent(this, ScreenRecordService::class.java)
                .setAction(ScreenRecordService.ACTION_STOP)
                .putExtra(ScreenRecordService.EXTRA_SESSION_ID, id)
            startService(serviceIntent)
            awaitRecordingAcknowledgement(id)
        } catch (e: Exception) {
            Log.e(TAG, "stop screen record service failed", e)
            finishRecordingState(id, "ERROR", "녹화 종료 요청 실패: ${e.message}")
        }
    }

    private fun awaitRecordingAcknowledgement(id: String, expectedPhase: RecordingState.Phase = recordingState.phase) {
        recordingWork.launch {
            delay(15_000L)
            if (recordingState.isAwaiting(id, expectedPhase)) {
                runCatching { startService(Intent(this@MainActivity, ScreenRecordService::class.java)
                    .setAction(ScreenRecordService.ACTION_STOP).putExtra(ScreenRecordService.EXTRA_SESSION_ID, id)) }
                finishRecordingState(id, "ERROR", "녹화 서비스 응답 시간 초과; MP4 저장 여부를 확인하세요.")
            }
        }
    }

    private fun finishRecordingState(id: String, status: String, message: String, event: Intent? = null) {
        if (!recordingState.finalizing(id)) return
        val completion = recordingFinished
        val pending = evaluationDataRecorder.stopRecordingLog(status,
            event?.getLongExtra(ScreenRecordService.EXTRA_STOPPED_AT_MS, System.currentTimeMillis()) ?: System.currentTimeMillis(),
            event?.getLongExtra(ScreenRecordService.EXTRA_STOPPED_ELAPSED_NS, SystemClock.elapsedRealtimeNanos()) ?: SystemClock.elapsedRealtimeNanos())
        recordingWork.launch {
            var resultMessage = message
            try { withContext(Dispatchers.IO) { pending.get() } }
            catch (error: Exception) { Log.e(TAG, "drain detection log failed", error); resultMessage = "$message / 탐지 로그 저장 실패: ${error.cause?.message ?: error.message}" }
            finally {
                recordingState.finished(id)
                activeRecordingVideoFile = null; activeRecordingDetectionsFile = null
                if (!destroying) {
                    recordingButton.text = "녹화 시작"; recordingButton.isEnabled = true
                    Toast.makeText(this@MainActivity, resultMessage, Toast.LENGTH_LONG).show()
                }
                completion.complete(Unit)
            }
        }
    }

    private fun onLogFailure(id: String?, error: Throwable) {
        Log.e(TAG, "log write failed session=$id", error)
        runOnUiThread {
            if (!destroying) Toast.makeText(this, "로그 저장 실패: ${error.message}", Toast.LENGTH_LONG).show()
            if (id != null && recordingState.sessionId == id) stopEvaluationRecording()
        }
    }

    private fun frameTrace(timing: CameraFrameTiming) = FrameTrace(sessionId, timing.frameId,
        timing.frameTimestampMs, timing.cameraTimestampNs, timing.captureTimestampReliable,
        timing.analyzerReceivedMs, timing.analyzerReceivedElapsedNs)

    private fun recordFrameFailure(error: Throwable, timing: CameraFrameTiming) {
        Log.e(TAG, "frame failed session=$sessionId frame=${timing.frameId}", error)
        metricsCollector.recordFrameFailed()
        val inferenceError = error as? InferenceException
        previewOffStatus = "추론 오류: ${inferenceError?.stage ?: "프레임 처리"}"
        lastFrameCompletionNs = SystemClock.elapsedRealtimeNanos()
        val trace = frameTrace(timing).copy(status = "ERROR",
            errorStage = inferenceError?.stage ?: if (error is com.samin.objectdetection.camera.BitmapConversionException) "BITMAP_CONVERSION" else "PIPELINE",
            error = error.stackTraceToString(), inferenceStartedAtMs = inferenceError?.inferenceStartedAtMs,
            inferenceStartedElapsedNs = inferenceError?.inferenceStartedElapsedNs,
            inferenceEndedElapsedNs = inferenceError?.inferenceEndedElapsedNs,
            completedAtMs = System.currentTimeMillis(), completedElapsedNs = SystemClock.elapsedRealtimeNanos())
        evaluationDataRecorder.recordError(trace, timing.frameWidth.takeIf { it > 0 } ?: actualAnalysisWidth,
            timing.frameHeight.takeIf { it > 0 } ?: actualAnalysisHeight)
        performanceLogRecorder.recordEvent(trace, detector.executionInfo())
        val rates = frameRates.snapshot(checkNotNull(trace.completedElapsedNs))
        if (appSettings.camera.previewEnabled) postLatestDetectionUiUpdate {
            overlayView.updateDetections(emptyList(), actualAnalysisWidth, actualAnalysisHeight, -1,
                rates.inferenceFps.roundToInt(), trace = trace, analyzerFps = rates.analyzerFps, inferenceFps = rates.inferenceFps)
            warningMessageTextView.text = "추론 오류: ${inferenceError?.stage ?: "프레임 처리"}"
            warningMessageTextView.visibility = View.VISIBLE
        }
    }

    private inline fun verboseLog(tag: String, message: () -> String) {
        if (ENABLE_VERBOSE_LOG) {
            Log.d(tag, message())
        }
    }

    /** Coalesces queued UI work so an old bbox update cannot build a main-thread backlog. */
    private fun postLatestDetectionUiUpdate(update: () -> Unit) {
        if (destroying) return
        detectionUiUpdates.submit { if (!destroying) update() }
    }

    private inline fun logDetectionTiming(tag: String, message: () -> String) {
        if (detectionConfig.enableDetectorDiagnostics) Log.d(tag, message())
    }

    private fun logSelectedWarningCandidate(
        candidate: WarningCandidate?,
        cooldownPassed: Boolean,
        vibrationExecuted: Boolean,
        beepExecuted: Boolean,
        ttsExecuted: Boolean
    ) {
        if (candidate == null) {
            Log.d(WARNING_SELECTED_TAG, "[WarningSelected] none")
            return
        }

        Log.d(
            WARNING_SELECTED_TAG,
            "[WarningSelected]\n" +
                "label=${candidate.label}\n" +
                "category=${candidate.category}\n" +
                "priority=${candidate.priority}\n" +
                "proximityLevel=${candidate.proximityLevel}\n" +
                "horizontalPosition=${candidate.horizontalPosition}\n" +
                "riskLevel=${candidate.riskLevel}\n" +
                "motionDirection=${candidate.motionDirection}\n" +
                "warningScenario=${candidate.warningScenario}\n" +
                "message=${candidate.feedback.message}\n" +
                "beepLevel=${candidate.feedback.beepLevel}\n" +
                "vibrationLevel=${candidate.feedback.vibrationLevel}\n" +
                "voiceLevel=${candidate.feedback.voiceLevel}\n" +
                "cooldownPassed=$cooldownPassed\n" +
                "vibrationExecuted=$vibrationExecuted\n" +
                "beepExecuted=$beepExecuted\n" +
                "ttsExecuted=$ttsExecuted\n" +
                "shouldNotify=${candidate.feedback.shouldNotify}"
        )
    }

    private fun logWarningOutput(
        candidate: WarningCandidate?,
        cooldownPassed: Boolean,
        vibrationExecuted: Boolean,
        beepExecuted: Boolean,
        ttsExecuted: Boolean,
        ttsSkippedReason: String?
    ) {
        if (candidate == null) {
            Log.d(WARNING_OUTPUT_TAG, "[WarningOutput] none")
            return
        }

        Log.d(
            WARNING_OUTPUT_TAG,
            "[WarningOutput]\n" +
                "label=${candidate.label}\n" +
                "category=${candidate.category}\n" +
                "priority=${candidate.priority}\n" +
                "proximityLevel=${candidate.proximityLevel}\n" +
                "horizontalPosition=${candidate.horizontalPosition}\n" +
                "riskLevel=${candidate.riskLevel}\n" +
                "motionDirection=${candidate.motionDirection}\n" +
                "warningScenario=${candidate.warningScenario}\n" +
                "message=${candidate.feedback.message}\n" +
                "vibrationLevel=${candidate.feedback.vibrationLevel}\n" +
                "vibrationExecuted=$vibrationExecuted\n" +
                "beepLevel=${candidate.feedback.beepLevel}\n" +
                "beepExecuted=$beepExecuted\n" +
                "voiceLevel=${candidate.feedback.voiceLevel}\n" +
                "ttsExecuted=$ttsExecuted\n" +
                "ttsSkippedReason=${ttsSkippedReason ?: "none"}\n" +
                "cooldown=$cooldownPassed"
        )
    }

    private fun logCrowdDecision(
        crowdDecision: CrowdDecision,
        cooldownPassed: Boolean
    ) {
        Log.d(
            CROWD_DECISION_TAG,
            "[CrowdDecision] " +
                "totalPersonCount=${crowdDecision.totalPersonCount}, " +
                "centerPersonCount=${crowdDecision.centerPersonCount}, " +
                "nearPersonCount=${crowdDecision.nearPersonCount}, " +
                "crowdLevel=${crowdDecision.crowdLevel}, " +
                "message=${crowdDecision.message}, " +
                "cooldownPassed=$cooldownPassed"
        )
    }

    private fun logWarningPolicyOverlayMismatch() {
        val policyLabelsMissingFromOverlay = YoloDefaultPolicyRegistry.getAll()
            .map { policy -> OverlayObjectFilter.normalize(policy.label) }
            .filterNot { label -> OverlayObjectFilter.isAllowed(label) }
            .distinct()
            .sorted()

        if (policyLabelsMissingFromOverlay.isNotEmpty()) {
            Log.w(
                TAG,
                "Warning policy labels not reachable from overlayDetections: " +
                    policyLabelsMissingFromOverlay.joinToString(", ")
            )
        }
    }

    private fun buildEmptyOverlayReason(
        rawDetections: List<DetectionResult>,
        visibleDetections: List<DetectionResult>,
        policyFilteredDetections: List<DetectionResult>,
        overlayDetections: List<DetectionResult>
    ): String {
        return when {
            overlayDetections.isNotEmpty() -> "drawing"
            rawDetections.isEmpty() -> "raw detection none"
            visibleDetections.isEmpty() -> "removed by small box filter"
            policyFilteredDetections.isEmpty() -> "removed by overlay whitelist"
            else -> "overlay stale or disabled"
        }
    }

    private fun formatIgnoredLabels(labels: List<String>): String {
        return if (labels.isEmpty()) {
            "none"
        } else {
            labels.joinToString(", ")
        }
    }

    private fun maybeRunMlKitDetection(bitmap: Bitmap, frameWidth: Int, frameHeight: Int, sourceTrace: FrameTrace,
        latency: FrameLatencyRecorder? = null) {
        if (!detectionConfig.enableMlKitDetection) {
            mlKitDiagnostics?.skipped(sourceTrace.frameId, "DISABLED", isMlKitProcessing.get())
            return
        }
        val now = System.currentTimeMillis()

        if (now - lastMlKitDetectionTime < detectionConfig.mlKitDetectionIntervalMs) {
            mlKitDiagnostics?.skipped(sourceTrace.frameId, "INTERVAL", isMlKitProcessing.get())
            return
        }
        if (!isMlKitProcessing.compareAndSet(false, true)) {
            mlKitDiagnostics?.skipped(sourceTrace.frameId, "IN_FLIGHT", true)
            return
        }

        lastMlKitDetectionTime = now

        val mlInputWidth = MlKitInputBuffer.WIDTH
        val mlInputHeight = MlKitInputBuffer.HEIGHT
        val roi: FrameRoi
        val input = try {
            roi = FrameRoi.calculate(frameWidth, frameHeight, detectionConfig.recognitionRoi, detectionConfig.useCenterSquareCrop)
            mlKitInputBuffer.acquire(bitmap, roi)
        } catch (error: Throwable) {
            isMlKitProcessing.set(false)
            mlKitDiagnostics?.event(sourceTrace.frameId, "INPUT_ERROR") { "inFlight=false error=${error.javaClass.simpleName}" }
            throw error
        }
        mlKitDiagnostics?.acquired(sourceTrace.frameId)
        mlKitDiagnostics?.event(sourceTrace.frameId, "INPUT") {
            "source=${frameWidth}x$frameHeight roi=[${roi.left},${roi.top},${roi.right},${roi.bottom}] " +
                "input=${input.bitmap.width}x${input.bitmap.height} config=${input.bitmap.config} " +
                "generation=${input.bitmap.generationId} recycled=${input.bitmap.isRecycled} ${mlKitInputSamples(input.bitmap)}"
        }
        val sdkDiagnosticLog: ((String) -> Unit)? = mlKitDiagnostics?.let { diagnostics ->
            { message -> diagnostics.event(sourceTrace.frameId, "SDK") { message } }
        }

        val mlKitDispatchedNs = latency?.nowNs() ?: -1L
        val job = try {
            captureWork.launch {
                try {
                    val mlStart = System.currentTimeMillis()
                    val mlStartNs = SystemClock.elapsedRealtimeNanos()

                    val mlKitResults = mlKitDetector.detect(input.bitmap, latency, mlKitDispatchedNs, sdkDiagnosticLog)
                    val mlTrace = sourceTrace.copy(inferenceStartedAtMs = mlStart, inferenceStartedElapsedNs = mlStartNs,
                        completedAtMs = System.currentTimeMillis(), completedElapsedNs = SystemClock.elapsedRealtimeNanos())

                    lastMlKitTimeMs = System.currentTimeMillis() - mlStart
                    lastMlKitCount = mlKitResults.size
                    metricsCollector.recordMlKitResult(lastMlKitTimeMs, lastMlKitCount)

                    // 작은 Bitmap 기준 bbox를 원본 프레임 기준 좌표로 복원

                    val results = mlKitResults.map { detection ->
                        val box = detection.boundingBox

                        DetectionResult(
                            label = "ML Kit",
                            confidence = 1f,
                            left = roi.sourceX(box.left.toFloat(), mlInputWidth),
                            top = roi.sourceY(box.top.toFloat(), mlInputHeight),
                            right = roi.sourceX(box.right.toFloat(), mlInputWidth),
                            bottom = roi.sourceY(box.bottom.toFloat(), mlInputHeight),
                            frameTimestampMs = sourceTrace.frameTimestampMs
                        )
                    }
                    mlKitDiagnostics?.event(sourceTrace.frameId, "MAPPED") {
                        "returnedCount=${mlKitResults.size} mappedCount=${results.size} " +
                            "boxes=${results.take(5).joinToString { "[${it.left},${it.top},${it.right},${it.bottom}]" }}"
                    }
                    mlKitDiagnostics?.event(sourceTrace.frameId, "UI_SUBMIT") {
                        "overlayCount=${results.size} preview=${appSettings.camera.previewEnabled} " +
                            "reason=${if (appSettings.camera.previewEnabled) "QUEUED" else "PREVIEW_OFF"}"
                    }
                    if (appSettings.camera.previewEnabled) mlKitUiUpdates.submit {
                        if (destroying) {
                            mlKitDiagnostics?.event(sourceTrace.frameId, "UI_SKIP") { "reason=DESTROYING" }
                            return@submit
                        }
                        mlKitDiagnostics?.event(sourceTrace.frameId, "UI_APPLY") { "overlayCount=${results.size}" }
                        overlayView.updateMlKitDetections(
                            results,
                            frameWidth,
                            frameHeight,
                            mlTrace
                        )
                    }

                } catch (e: Exception) {
                    mlKitDiagnostics?.event(sourceTrace.frameId, "RESULT_ERROR") { "error=${e.javaClass.simpleName}" }
                    Log.e(TAG, "ML Kit detection error", e)
                }
            }
        } catch (error: Throwable) {
            input.close()
            isMlKitProcessing.set(false)
            mlKitDiagnostics?.released(sourceTrace.frameId)
            throw error
        }
        // Also runs if launch is cancelled before entering its body. detect drains the SDK Task
        // before this Job can complete, so neither reuse nor recycle can race ML Kit's reader.
        job.invokeOnCompletion {
            input.close()
            isMlKitProcessing.set(false)
            mlKitDiagnostics?.released(sourceTrace.frameId)
        }
    }

    /** Only called inside an opt-in diagnostic lambda; no images or full pixel arrays are saved. */
    private fun mlKitInputSamples(bitmap: Bitmap): String {
        var minimum = 255
        var maximum = 0
        var fingerprint = 1
        for (row in 0..3) for (column in 0..7) {
            val pixel = bitmap.getPixel((bitmap.width - 1) * column / 7, (bitmap.height - 1) * row / 3)
            val brightness = (((pixel ushr 16) and 255) + ((pixel ushr 8) and 255) + (pixel and 255)) / 3
            minimum = minOf(minimum, brightness)
            maximum = maxOf(maximum, brightness)
            fingerprint = 31 * fingerprint + pixel
        }
        return "sampleBrightness=[$minimum,$maximum] sampleFingerprint=${Integer.toHexString(fingerprint)}"
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun formatSpeed(speedMps: Float?): String {
        return if (speedMps == null) {
            "unknown"
        } else {
            "${String.format(Locale.US, "%.2f", speedMps)}m/s"
        }
    }

    private fun formatAccuracy(accuracyMeters: Float?): String {
        return if (accuracyMeters == null) {
            "unknown"
        } else {
            "${String.format(Locale.US, "%.1f", accuracyMeters)}m"
        }
    }

    override fun onDestroy() {
        destroying = true
        detectionUiUpdates.close()
        mlKitUiUpdates.close()
        if (::overlayView.isInitialized) {
            overlayView.clearDetections()
            overlayView.onFrameDrawn = null
            overlayView.diagnosticLog = null
            overlayView.mlKitDiagnostics = null
        }
        super.onDestroy()
        if (isRecording) stopEvaluationRecording()
        val pendingPerformance = performanceJob
        // Stop the performance clock promptly; camera/ML Kit shutdown can take much longer.
        val performanceStopped = recordingWork.launch {
            pendingPerformance?.join()
            if (::performanceLogRecorder.isInitialized && performanceLogRecorder.isRecording) {
                runCatching { performanceLogRecorder.stop(metricsCollector.stopPerformanceSessionSnapshot(stoppedElapsedNs = SystemClock.elapsedRealtimeNanos()), PerformanceStopReason.ACTIVITY_DESTROYED) }
                    .onFailure { Log.e(TAG, "performance log shutdown failed", it) }
            }
        }
        val stopped = if (::cameraController.isInitialized) cameraController.stopAsync() else CompletableFuture.completedFuture(Unit)
        // This scope survives Activity lifecycle cancellation. Waiting/draining never blocks main.
        recordingWork.launch {
            val analyzerStopped = runCatching { withContext(Dispatchers.IO) { stopped.get() } }
                .onFailure { Log.e(TAG, "analyzer shutdown failed; interpreter retained", it) }.isSuccess
            closeMlKitAfterCaptureDrain()
            MlKitDiagnosticLogs.peek()?.sourceEnded(sessionId)
            if (analyzerStopped && ::detector.isInitialized) withContext(Dispatchers.IO) { detector.close() }
            if (::userLocationTracker.isInitialized) userLocationTracker.stop()
            if (::warningOutputController.isInitialized) warningOutputController.release()
            if (::vibrationWarningPlayer.isInitialized) vibrationWarningPlayer.release()
            if (::beepWarningPlayer.isInitialized) beepWarningPlayer.release()
            if (::ttsWarningPlayer.isInitialized) ttsWarningPlayer.release()
            performanceStopped.join()
            if (isRecording) {
                val acknowledged = runCatching { withContext(Dispatchers.IO) { recordingFinished.get(20, TimeUnit.SECONDS) } }.isSuccess
                if (!acknowledged) {
                    recordingState.sessionId?.let { finishRecordingState(it, "ERROR", "앱 종료 중 녹화 응답 없음") }
                    runCatching { withContext(Dispatchers.IO) { recordingFinished.get() } }
                }
            }
            if (::evaluationDataRecorder.isInitialized) runCatching { withContext(Dispatchers.IO) { evaluationDataRecorder.close().get() } }
                .onFailure { Log.e(TAG, "evaluation log shutdown failed", it) }
            if (::detectionDebugRecorder.isInitialized) detectionDebugRecorder.close()
            if (receiverRegistered) runCatching { applicationContext.unregisterReceiver(screenRecordingReceiver) }
            recordingWork.cancel()
        }
    }

    companion object {
        private const val ENABLE_VERBOSE_LOG = false
        private const val TAG = "ObjectDetectionVision"
        private const val DETECTOR_TYPE = "VisionStyleYoloDetector"
        private const val DETECTION_TIMING_TAG = "DetectionTiming"
        private const val WARNING_FEEDBACK_TAG = "GotoroWarning"
        private const val WARNING_SELECTED_TAG = "GotoroWarning"
        private const val WARNING_OUTPUT_TAG = "GotoroWarning"
        private const val CROWD_DECISION_TAG = "GotoroCrowd"
        private const val VIBRATION_OUTPUT_TAG = "GotoroVibration"
        private const val BEEP_OUTPUT_TAG = "GotoroBeep"
        private const val TTS_OUTPUT_TAG = "GotoroTts"
    }
}
