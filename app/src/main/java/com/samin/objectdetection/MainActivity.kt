package com.samin.objectdetection

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.samin.objectdetection.camera.CameraController
import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.ObjectDetector
import com.samin.objectdetection.detector.VisionStyleYoloDetector
import com.samin.objectdetection.evaluation.DetectionFrameSnapshot
import com.samin.objectdetection.evaluation.EvaluationDataRecorder
import com.samin.objectdetection.evaluation.ScreenRecordService
import com.samin.objectdetection.location.UserLocationTracker
import com.samin.objectdetection.metrics.DetectionMetricsCollector
import com.samin.objectdetection.mlkit.MlKitObjectDetector
import com.samin.objectdetection.motion.ObjectMotionTracker
import com.samin.objectdetection.pipeline.DetectionPipeline
import com.samin.objectdetection.policy.OverlayObjectFilter
import com.samin.objectdetection.policy.YoloDefaultPolicyRegistry
import com.samin.objectdetection.ui.BoundingBoxOverlay
import com.samin.objectdetection.ui.MainScreenView
import com.samin.objectdetection.ui.OverlayDebugMode
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
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    private lateinit var screen: MainScreenView
    private lateinit var overlayView: BoundingBoxOverlay
    private val debugTextView get() = screen.debugTextView
    private val warningMessageTextView get() = screen.warningMessageTextView
    private val recordingButton get() = screen.recordingButton

    private lateinit var cameraController: CameraController
    private lateinit var detector: ObjectDetector
    private lateinit var mlKitDetector: MlKitObjectDetector
    private lateinit var evaluationDataRecorder: EvaluationDataRecorder
    private val detectionConfig = DetectionConfig()
    private val warningCooldownManager = WarningCooldownManager()
    private val warningCandidateSelector = WarningCandidateSelector()
    private val metricsCollector = DetectionMetricsCollector()
    private lateinit var detectionPipeline: DetectionPipeline
    private lateinit var userLocationTracker: UserLocationTracker
    private lateinit var vibrationWarningPlayer: VibrationWarningPlayer
    private lateinit var beepWarningPlayer: BeepWarningPlayer
    private lateinit var ttsWarningPlayer: TtsWarningPlayer
    private lateinit var warningOutputController: WarningOutputController

    private val enableActualVibration = true
    private val enableActualBeep = true
    private val enableActualTts = true

    private val isMlKitProcessing = AtomicBoolean(false)
    @Volatile
    private var lastMlKitCount = 0

    @Volatile
    private var lastMlKitTimeMs = 0L
    private var lastFpsTime = 0L
    private var lastMlKitDetectionTime = 0L
    private var frameCount = 0
    private var currentFps = 0
    private val latestSnapshotLock = Any()
    private var latestSnapshot: DetectionFrameSnapshot? = null
    private var activeRecordingVideoFile: File? = null
    private var activeRecordingDetectionsFile: File? = null
    private var isRecording = false

    private val screenRecordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenRecordService.ACTION_RECORDING_STOPPED -> {
                    if (isRecording) {
                        finishRecordingState("화면 녹화 저장: ${activeRecordingVideoFile?.name ?: "mp4"}")
                    }
                }
                ScreenRecordService.ACTION_RECORDING_ERROR -> {
                    val message = intent.getStringExtra(ScreenRecordService.EXTRA_MESSAGE)
                    if (isRecording) {
                        finishRecordingState("화면 녹화 실패: ${message ?: "unknown"}")
                    }
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
            }
        }

    private val screenCapturePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startScreenRecording(result.resultCode, result.data!!)
            } else {
                Toast.makeText(this, "화면 녹화 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val yoloDetector = VisionStyleYoloDetector(this, MODEL_NAME).apply {
            confidenceThreshold = detectionConfig.confidenceThreshold
            enableDebugImageSaving = detectionConfig.enableDetectorDebugImage
        }
        detector = yoloDetector
        mlKitDetector = MlKitObjectDetector()
        evaluationDataRecorder = EvaluationDataRecorder(
            context = this,
            detectionConfig = detectionConfig,
            modelName = MODEL_NAME,
            detectorType = DETECTOR_TYPE
        )
        userLocationTracker = UserLocationTracker(this)
        detectionPipeline = DetectionPipeline(
            detector = detector,
            config = detectionConfig,
            objectMotionTracker = ObjectMotionTracker(),
            userLocationSnapshotProvider = { userLocationTracker.currentSnapshot }
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
            enableActualVibration = { enableActualVibration },
            enableActualBeep = { enableActualBeep },
            enableActualTts = { enableActualTts }
        )
        logWarningPolicyOverlayMismatch()

        setupUi()
        registerScreenRecordingReceiver()
        checkPermissionAndStart()
    }

    private fun setupUi() {
        screen = MainScreenView(
            activity = this,
            debugMode = detectionConfig.overlayDebugMode,
            onCapture = ::captureEvaluationFrame,
            onToggleRecording = ::toggleEvaluationRecording
        )
        overlayView = screen.overlayView
        setContentView(screen.root)
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
        }
        ContextCompat.registerReceiver(
            this,
            screenRecordingReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun startCamera() {
        cameraController = CameraController(
            context = this,
            lifecycleOwner = this,
            previewView = screen.previewView,
            detectIntervalMs = detectionConfig.detectIntervalMs,
            listener = object : CameraController.Listener {
                override fun onFrameReceived(timestampMs: Long, isProcessing: Boolean) {
                    metricsCollector.recordFrameReceived()
                    calculateFps()
                    verboseLog(DETECTION_TIMING_TAG, "frameReceived=$timestampMs isDetecting=$isProcessing")
                }

                override fun onFrameSkipped(reason: CameraController.SkipReason, skippedFrameCount: Long) {
                    metricsCollector.recordFrameSkipped()
                    verboseLog(DETECTION_TIMING_TAG, "skipFrame reason=$reason skipped=$skippedFrameCount")
                }

                override fun onFrame(bitmap: Bitmap, timestampMs: Long) {
                    processBitmap(bitmap, timestampMs)
                }

                override fun onCameraStarted() {
                    debugTextView.text = "카메라 시작됨"
                }

                override fun onCameraError(error: Throwable) {
                    Log.e(TAG, "startCamera error", error)
                    debugTextView.text = "카메라 시작 실패: ${error.message}"
                }

                override fun onFrameError(error: Throwable) {
                    Log.e(TAG, "analyze error", error)
                }
            }
        )
        cameraController.start()
    }

    private fun processBitmap(bitmap: Bitmap, frameReceivedTimeMs: Long) {
        val start = System.currentTimeMillis()
        metricsCollector.recordFrameAnalyzed()
        verboseLog(
            DETECTION_TIMING_TAG,
            "detectionStart=$start frameReceived=$frameReceivedTimeMs"
        )

        // vision-mlkit-lab 방식: 중앙 정방형 crop으로 모델 입력 왜곡을 줄임
        maybeRunMlKitDetection(bitmap, bitmap.width, bitmap.height)

        val pipelineResult = detectionPipeline.process(bitmap, start)
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
        val detectionEndTimeMs = start + inferenceTime
        val topOverlayObject = pipelineResult.topOverlayObject
        metricsCollector.recordYoloDetections(
            beforeFilter = mapped,
            afterSmallBoxFilter = visibleMapped,
            afterPolicyFilter = warningDetections,
            timestampMs = start
        )
        metricsCollector.recordYoloInferenceTime(inferenceTime)
        val warningCandidates = overlayDetections.map { detection ->
            WarningCandidate.fromDetection(
                detection = detection,
                warningKey = warningCooldownManager.buildKey(
                    label = detection.label,
                    priority = detection.objectPriority,
                    proximityLevel = detection.proximityLevel,
                    horizontalPosition = detection.horizontalPosition
                )
            )
        }
        val crowdDecision = WarningPolicy.resolveCrowdDecision(overlayDetections)
        val crowdCandidate = crowdDecision.toWarningCandidate()
        val selectedCandidate = warningCandidateSelector.selectWithCrowd(warningCandidates, crowdCandidate)
        val selectedCooldownPassed = selectedCandidate?.let { candidate ->
            warningCooldownManager.canNotify(candidate.warningKey, start)
        } ?: false
        val selectedFeedback = selectedCandidate?.feedback
        val outputResult = warningOutputController.submit(
            candidate = selectedCandidate,
            cooldownAllowed = selectedCooldownPassed,
            timestampMs = start
        )
        val vibrationExecuted = outputResult.vibrationExecuted
        val beepExecuted = outputResult.beepExecuted
        val ttsExecuted = outputResult.ttsExecuted
        val ttsSkippedReason = outputResult.skippedReason
        Log.d(
            VIBRATION_OUTPUT_TAG,
            "label=${selectedCandidate?.label ?: "none"} vibration=${selectedCandidate?.feedback?.vibrationLevel ?: FeedbackLevel.NONE} " +
                "executed=$vibrationExecuted cooldown=$selectedCooldownPassed enabled=$enableActualVibration"
        )
        Log.d(
            BEEP_OUTPUT_TAG,
            "label=${selectedCandidate?.label ?: "none"} beep=${selectedCandidate?.feedback?.beepLevel ?: FeedbackLevel.NONE} " +
                "executed=$beepExecuted cooldown=$selectedCooldownPassed enabled=$enableActualBeep"
        )
        Log.d(
            TTS_OUTPUT_TAG,
            "label=${selectedCandidate?.label ?: "none"} voice=${selectedCandidate?.feedback?.voiceLevel ?: FeedbackLevel.NONE} " +
                "executed=$ttsExecuted skippedReason=${ttsSkippedReason ?: "none"} cooldown=$selectedCooldownPassed enabled=$enableActualTts"
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
                "enableActualVibration=$enableActualVibration enableActualBeep=$enableActualBeep enableActualTts=$enableActualTts " +
                "shouldNotify=$feedbackShouldNotify key=$feedbackWarningKey"
        )
        logDetectionTiming(
            DETECTION_TIMING_TAG,
                "detectionEnd=$detectionEndTimeMs inference=${inferenceTime}ms " +
                "rawCount=${mapped.size} visibleCount=${visibleMapped.size} " +
                "overlayWhitelistCount=${overlayDetections.size} policyFilteredCount=${warningDetections.size} " +
                "ignoredLabels=${formatIgnoredLabels(ignoredLabels)} " +
                "emptyReason=${buildEmptyOverlayReason(mapped, visibleMapped, warningDetections, overlayDetections)}"
        )
        val evaluationSnapshot = updateLatestEvaluationSnapshot(
            bitmap = bitmap,
            detections = overlayDetections,
            evaluationDetections = mapped,
            frameTimestampMs = start,
            roi = cropRect,
            rawDetectionCount = mapped.size,
            visibleDetectionCount = visibleMapped.size,
            warningDetectionCount = warningDetections.size,
            topDetection = topOverlayObject,
            selectedWarningCandidate = selectedCandidate,
            inferenceTimeMs = inferenceTime,
            fps = currentFps,
            userLocationSnapshot = userLocationSnapshot
        )
        if (isRecording) {
            lifecycleScope.launch(Dispatchers.IO) {
                evaluationDataRecorder.appendRecordingDetections(evaluationSnapshot)
            }
        }

        runOnUiThread {
            val overlayUpdateTimeMs = System.currentTimeMillis()
            val newestDetectionTimestamp = overlayDetections.maxOfOrNull { it.frameTimestampMs } ?: overlayUpdateTimeMs
            val resultAgeMs = overlayUpdateTimeMs - newestDetectionTimestamp
            logDetectionTiming(
                DETECTION_TIMING_TAG,
                    "overlayUpdate=$overlayUpdateTimeMs resultAge=${resultAgeMs}ms " +
                    "rawCount=${mapped.size} visibleCount=${visibleMapped.size} " +
                    "overlayWhitelistCount=${overlayDetections.size} policyFilteredCount=${warningDetections.size} " +
                    "ignoredLabels=${formatIgnoredLabels(ignoredLabels)} " +
                    "emptyReason=${buildEmptyOverlayReason(mapped, visibleMapped, warningDetections, overlayDetections)}"
            )
            overlayView.updateDetections(overlayDetections, width, height, inferenceTime, currentFps)
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
                    appendLine("YOLO inference time: ${inferenceTime}ms / FPS=$currentFps")
                    appendLine("ML Kit detection count: $lastMlKitCount / ${lastMlKitTimeMs}ms")
                    if (topOverlayObject != null) {
                        append("Top overlay: ${topOverlayObject.label} ${String.format("%.2f", topOverlayObject.confidence)}")
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

        verboseLog(
            TAG,
            "frame=${width}x$height, warningDetections=${warningDetections.size}, " +
                "overlayDetections=${overlayDetections.size}, ignoredLabels=${formatIgnoredLabels(ignoredLabels)}, " +
                "time=${inferenceTime}ms"
        )
    }

    private fun updateLatestEvaluationSnapshot(
        bitmap: Bitmap,
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
        userLocationSnapshot: com.samin.objectdetection.location.UserLocationSnapshot
    ): DetectionFrameSnapshot {
        val snapshot = DetectionFrameSnapshot(
            bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
            detections = detections,
            evaluationDetections = evaluationDetections,
            frameTimestampMs = frameTimestampMs,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            roiApplied = true,
            roi = Rect(roi),
            rawDetectionCount = rawDetectionCount,
            visibleDetectionCount = visibleDetectionCount,
            warningDetectionCount = warningDetectionCount,
            topDetection = topDetection,
            selectedWarningCandidate = selectedWarningCandidate,
            inferenceTimeMs = inferenceTimeMs,
            fps = fps,
            userLocationSnapshot = userLocationSnapshot
        )
        synchronized(latestSnapshotLock) {
            latestSnapshot?.bitmap?.recycle()
            latestSnapshot = snapshot
        }
        return snapshot
    }

    private fun captureEvaluationFrame() {
        val snapshot = synchronized(latestSnapshotLock) {
            latestSnapshot?.let { current ->
                current.copy(
                    bitmap = current.bitmap.copy(Bitmap.Config.ARGB_8888, false),
                    detections = current.detections.toList(),
                    evaluationDetections = current.evaluationDetections.toList(),
                    roi = current.roi?.let { Rect(it) }
                )
            }
        }
        if (snapshot == null) {
            Toast.makeText(this, "저장할 프레임이 아직 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val timestamp = snapshot.frameTimestampMs.toString()
                val files = evaluationDataRecorder.saveCapture(snapshot, timestamp)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "캡쳐 저장: ${files.image.name}",
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

    private fun startEvaluationRecording() {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        screenCapturePermissionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startScreenRecording(resultCode: Int, data: Intent) {
        val timestamp = System.currentTimeMillis().toString()
        val videoFile = File(evaluationDataRecorder.recordingsDir, "${timestamp}_screen.mp4")
        val detectionsFile = evaluationDataRecorder.startRecordingLog(timestamp)
        activeRecordingVideoFile = videoFile
        activeRecordingDetectionsFile = detectionsFile

        try {
            val serviceIntent = Intent(this, ScreenRecordService::class.java)
                .setAction(ScreenRecordService.ACTION_START)
                .putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
                .putExtra(ScreenRecordService.EXTRA_OUTPUT_PATH, videoFile.absolutePath)
            ContextCompat.startForegroundService(this, serviceIntent)
            isRecording = true
            recordingButton.text = "녹화 중지"
            Toast.makeText(this, "화면 녹화 시작: ${videoFile.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "start screen record service failed", e)
            evaluationDataRecorder.stopRecordingLog()
            activeRecordingVideoFile = null
            activeRecordingDetectionsFile = null
            Toast.makeText(this, "화면 녹화 시작 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopEvaluationRecording() {
        try {
            val serviceIntent = Intent(this, ScreenRecordService::class.java)
                .setAction(ScreenRecordService.ACTION_STOP)
            startService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "stop screen record service failed", e)
        }
        finishRecordingState(
            "화면 녹화 저장: ${activeRecordingVideoFile?.name ?: "mp4"}"
        )
    }

    private fun finishRecordingState(message: String) {
        evaluationDataRecorder.stopRecordingLog()
        isRecording = false
        recordingButton.text = "녹화 시작"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        activeRecordingVideoFile = null
        activeRecordingDetectionsFile = null
    }

    private fun verboseLog(tag: String, message: String) {
        if (ENABLE_VERBOSE_LOG) {
            Log.d(tag, message)
        }
    }

    private fun logDetectionTiming(tag: String, message: String) {
        Log.d(tag, message)
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

    private fun maybeRunMlKitDetection(bitmap: Bitmap, frameWidth: Int, frameHeight: Int) {
        val now = System.currentTimeMillis()

        if (now - lastMlKitDetectionTime < ML_KIT_DETECT_INTERVAL_MS) return
        if (!isMlKitProcessing.compareAndSet(false, true)) return

        lastMlKitDetectionTime = now

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val mlInputWidth = 640
                val mlInputHeight = 360

                // ML Kit 연산량 줄이기 위해 작은 Bitmap으로 축소
                val mlBitmap = Bitmap.createScaledBitmap(
                    bitmap,
                    mlInputWidth,
                    mlInputHeight,
                    true
                )

                val mlStart = System.currentTimeMillis()

                val mlKitResults = mlKitDetector.detect(mlBitmap)

                lastMlKitTimeMs = System.currentTimeMillis() - mlStart
                lastMlKitCount = mlKitResults.size
                metricsCollector.recordMlKitResult(lastMlKitTimeMs, lastMlKitCount)

                // 작은 Bitmap 기준 bbox를 원본 프레임 기준 좌표로 복원
                val scaleX = frameWidth / mlInputWidth.toFloat()
                val scaleY = frameHeight / mlInputHeight.toFloat()

                val results = mlKitResults.map { detection ->
                    val box = detection.boundingBox

                    DetectionResult(
                        label = "ML Kit",
                        confidence = 1f,
                        left = box.left * scaleX,
                        top = box.top * scaleY,
                        right = box.right * scaleX,
                        bottom = box.bottom * scaleY,
                        frameTimestampMs = mlStart
                    )
                }
                runOnUiThread {
                    overlayView.updateMlKitDetections(
                        results,
                        frameWidth,
                        frameHeight
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "ML Kit detection error", e)
            } finally {
                isMlKitProcessing.set(false)
            }
        }
    }

    private fun calculateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            currentFps = frameCount
            metricsCollector.recordFps(currentFps)
            frameCount = 0
            lastFpsTime = now
        }
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
        super.onDestroy()
        try {
            unregisterReceiver(screenRecordingReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "unregister screen recording receiver failed", e)
        }
        try {
            if (isRecording) {
                startService(
                    Intent(this, ScreenRecordService::class.java)
                        .setAction(ScreenRecordService.ACTION_STOP)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "stop screen record service on destroy failed", e)
        }
        evaluationDataRecorder.close()
        synchronized(latestSnapshotLock) {
            latestSnapshot?.bitmap?.recycle()
            latestSnapshot = null
        }
        userLocationTracker.stop()
        if (::cameraController.isInitialized) {
            cameraController.close()
        }
        mlKitDetector.close()
        detector.close()
        warningOutputController.release()
        vibrationWarningPlayer.release()
        beepWarningPlayer.release()
        ttsWarningPlayer.release()
    }

    companion object {
        private const val ENABLE_VERBOSE_LOG = false
        private const val TAG = "ObjectDetectionVision"
        private const val MODEL_NAME = "best_float32.tflite"
        private const val DETECTOR_TYPE = "VisionStyleYoloDetector"
        private const val DETECTION_TIMING_TAG = "DetectionTiming"
        private const val WARNING_FEEDBACK_TAG = "GotoroWarning"
        private const val WARNING_SELECTED_TAG = "GotoroWarning"
        private const val WARNING_OUTPUT_TAG = "GotoroWarning"
        private const val CROWD_DECISION_TAG = "GotoroCrowd"
        private const val VIBRATION_OUTPUT_TAG = "GotoroVibration"
        private const val BEEP_OUTPUT_TAG = "GotoroBeep"
        private const val TTS_OUTPUT_TAG = "GotoroTts"
        private const val ML_KIT_DETECT_INTERVAL_MS = 1500L
    }
}
