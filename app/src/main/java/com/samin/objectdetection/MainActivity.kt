package com.samin.objectdetection

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.camera.toBitmapSafe
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.ObjectDetector
import com.samin.objectdetection.detector.VisionStyleYoloDetector
import com.samin.objectdetection.location.UserLocationTracker
import com.samin.objectdetection.metrics.DetectionMetricsCollector
import com.samin.objectdetection.mlkit.MlKitObjectDetector
import com.samin.objectdetection.model.DetectedObject
import com.samin.objectdetection.model.DetectionSource
import com.samin.objectdetection.model.toDetectedObject
import com.samin.objectdetection.motion.ObjectMotionTracker
import com.samin.objectdetection.policy.OverlayObjectFilter
import com.samin.objectdetection.policy.YoloDefaultPolicyRegistry
import com.samin.objectdetection.ui.BoundingBoxOverlay
import com.samin.objectdetection.warning.BeepWarningPlayer
import com.samin.objectdetection.warning.CompositeWarningPlayer
import com.samin.objectdetection.warning.CrowdDecision
import com.samin.objectdetection.warning.CrowdDecisionEvaluator
import com.samin.objectdetection.warning.FeedbackLevel
import com.samin.objectdetection.warning.ForwardObstacleSelector
import com.samin.objectdetection.warning.GotoroVisionRiskPolicy
import com.samin.objectdetection.warning.RiskLevel
import com.samin.objectdetection.warning.TtsWarningPlayer
import com.samin.objectdetection.warning.VibrationWarningPlayer
import com.samin.objectdetection.warning.WarningCandidate
import com.samin.objectdetection.warning.WarningCandidateSelector
import com.samin.objectdetection.warning.WarningDecisionMaker
import com.samin.objectdetection.warning.WarningCooldownManager
import com.samin.objectdetection.warning.WarningFeedbackPolicy
import com.samin.objectdetection.warning.WarningPlayer
import com.samin.objectdetection.warning.WarningSelector
import com.samin.objectdetection.warning.WarningStabilizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: BoundingBoxOverlay
    private lateinit var debugTextView: TextView
    private lateinit var warningMessageTextView: TextView
    private lateinit var toggleButton: Button

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var detector: ObjectDetector
    private lateinit var mlKitDetector: MlKitObjectDetector
    private val detectionConfig = DetectionConfig()
    private val forwardObstacleSelector = ForwardObstacleSelector()
    private val warningDecisionMaker = WarningDecisionMaker()
    private val warningSelector = WarningSelector(warningDecisionMaker)
    private val warningCooldownManager = WarningCooldownManager()
    private val warningCandidateSelector = WarningCandidateSelector()
    private val warningStabilizer = WarningStabilizer()
    private val objectMotionTracker = ObjectMotionTracker()
    private val metricsCollector = DetectionMetricsCollector()
    private lateinit var userLocationTracker: UserLocationTracker
    private lateinit var warningPlayer: WarningPlayer

    @Volatile
    private var isProcessing = AtomicBoolean(false)
    private val isMlKitProcessing = AtomicBoolean(false)
    @Volatile
    private var lastMlKitCount = 0

    @Volatile
    private var lastMlKitTimeMs = 0L
    @Volatile
    private var lastMlKitDetectedObjects: List<DetectedObject> = emptyList()

    private var lastFpsTime = 0L
    private var lastMlKitDetectionTime = 0L
    private var frameCount = 0
    private var currentFps = 0
    private var overlayEnabled = true
    private var skippedFrameCount = 0L
    private var lastDetectionStartTimeMs = 0L

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val yoloDetector = VisionStyleYoloDetector(this, "yolo11n_float32.tflite").apply {
            confidenceThreshold = detectionConfig.confidenceThreshold
            enableDebugImageSaving = detectionConfig.enableDetectorDebugImage
        }
        detector = yoloDetector
        mlKitDetector = MlKitObjectDetector()
        userLocationTracker = UserLocationTracker(this)
        warningPlayer = CompositeWarningPlayer(
            listOf(
                BeepWarningPlayer(),
                VibrationWarningPlayer(this),
                TtsWarningPlayer(this)
            )
        )
        logWarningPolicyOverlayMismatch()

        setupUi()
        checkPermissionAndStart()
    }

    private fun setupUi() {
        val root = FrameLayout(this)

        previewView = PreviewView(this).apply {
            // 테스트 중에는 FIT_CENTER가 bbox 위치 확인에 유리함
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }

        overlayView = BoundingBoxOverlay(this)

        debugTextView = TextView(this).apply {
            text = "대기 중"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.argb(170, 0, 0, 0))
            setPadding(24, 24, 24, 24)
        }

        warningMessageTextView = TextView(this).apply {
            id = View.generateViewId()
            visibility = View.GONE
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.argb(190, 0, 0, 0))
            setPadding(32, 20, 32, 20)
        }

        toggleButton = Button(this).apply {
            text = "Overlay ON"
            setOnClickListener {
                overlayEnabled = !overlayEnabled
                overlayView.setDrawingEnabled(overlayEnabled)
                text = if (overlayEnabled) "Overlay ON" else "Overlay OFF"
            }
        }

        root.addView(previewView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(overlayView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        root.addView(
            debugTextView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                setMargins(20, 40, 20, 0)
            }
        )

        root.addView(
            toggleButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(20, 20, 20, 60)
            }
        )

        root.addView(
            warningMessageTextView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(20, 20, 20, 150)
            }
        )

        overlayView.bringToFront()
        debugTextView.bringToFront()
        warningMessageTextView.bringToFront()
        toggleButton.bringToFront()

        setContentView(root)
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

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()

                val preview = Preview.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    val frameReceivedTimeMs = System.currentTimeMillis()
                    metricsCollector.recordFrameReceived()
                    verboseLog(
                        DETECTION_TIMING_TAG,
                        "frameReceived=$frameReceivedTimeMs isDetecting=${isProcessing.get()}"
                    )
                    calculateFps()

                    if (frameReceivedTimeMs - lastDetectionStartTimeMs < detectionConfig.detectIntervalMs) {
                        skippedFrameCount++
                        metricsCollector.recordFrameSkipped()
                        verboseLog(
                            DETECTION_TIMING_TAG,
                            "skipFrameByInterval skipped=$skippedFrameCount intervalMs=${detectionConfig.detectIntervalMs}"
                        )
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    if (!isProcessing.compareAndSet(false, true)) {
                        skippedFrameCount++
                        metricsCollector.recordFrameSkipped()
                        verboseLog(
                            DETECTION_TIMING_TAG,
                            "skipFrame skipped=$skippedFrameCount isDetecting=${isProcessing.get()}"
                        )
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    lastDetectionStartTimeMs = frameReceivedTimeMs

                    try {
                        val bitmap = imageProxy.toBitmapSafe()
                        if (bitmap == null) {
                            metricsCollector.recordFrameSkipped()
                            imageProxy.close()
                            isProcessing.set(false)
                            return@setAnalyzer
                        }

                        processBitmap(bitmap, frameReceivedTimeMs)
                    } catch (e: Exception) {
                        Log.e(TAG, "analyze error", e)
                    } finally {
                        imageProxy.close()
                        isProcessing.set(false)
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)

                debugTextView.text = "카메라 시작됨"
            } catch (e: Exception) {
                Log.e(TAG, "startCamera error", e)
                debugTextView.text = "카메라 시작 실패: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processBitmap(bitmap: Bitmap, frameReceivedTimeMs: Long) {
        val start = System.currentTimeMillis()
        metricsCollector.recordFrameAnalyzed()
        verboseLog(
            DETECTION_TIMING_TAG,
            "detectionStart=$start frameReceived=$frameReceivedTimeMs isDetecting=${isProcessing.get()}"
        )

        // vision-mlkit-lab 방식: 중앙 정방형 crop으로 모델 입력 왜곡을 줄임
        val width = bitmap.width
        val height = bitmap.height
        val size = minOf(width, height)
        val left = (width - size) / 2
        val top = (height - size) / 2
        val cropRect = Rect(left, top, left + size, top + size)

        maybeRunMlKitDetection(bitmap, width, height)

        val cropped = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
        val croppedResults = detector.detect(cropped)
        val detectionEndTimeMs = System.currentTimeMillis()
        val mapped = croppedResults.map { res ->
            val detection = DetectionResult(
                label = res.label,
                confidence = res.confidence,
                left = res.left + cropRect.left,
                top = res.top + cropRect.top,
                right = res.right + cropRect.left,
                bottom = res.bottom + cropRect.top,
                frameTimestampMs = start
            )
            val evaluated = GotoroVisionRiskPolicy.evaluate(
                detection = detection,
                frameWidth = width,
                frameHeight = height
            )
            val feedback = WarningFeedbackPolicy.evaluate(evaluated)
            evaluated.copy(warningFeedback = feedback).also { feedbackDetection ->
                GotoroVisionRiskPolicy.logDebug(feedbackDetection)
            }
        }
        val visibleMapped = filterSmallBoxes(
            detections = mapped,
            frameWidth = width,
            frameHeight = height,
            config = detectionConfig
        )
        val overlayCandidates = visibleMapped.filter { detection ->
            OverlayObjectFilter.isAllowed(detection.label)
        }
        val ignoredLabels = visibleMapped
            .filterNot { detection -> OverlayObjectFilter.isAllowed(detection.label) }
            .map { detection -> OverlayObjectFilter.normalize(detection.label) }
            .distinct()
            .sorted()
        val userLocationSnapshot = userLocationTracker.currentSnapshot
        val overlayDetections = objectMotionTracker.update(
            detections = overlayCandidates,
            frameWidth = width,
            frameHeight = height,
            timestampMs = start,
            userMotionState = userLocationSnapshot.motionState
        )

        val warningDetections = overlayDetections.filter { detection ->
            val policy = YoloDefaultPolicyRegistry.get(detection.label)
            !detection.isIgnored && policy != null && detection.confidence >= policy.minConfidence
        }
        metricsCollector.recordYoloDetections(
            beforeFilter = mapped,
            afterSmallBoxFilter = visibleMapped,
            afterPolicyFilter = warningDetections,
            timestampMs = start
        )
        val yoloDetectedObjects = warningDetections.map { it.toDetectedObject(DetectionSource.YOLO) }
        val freshMlKitDetectedObjects = lastMlKitDetectedObjects.filter {
            start - it.timestampMs <= ML_KIT_WARNING_MAX_AGE_MS
        }
        val selectedWarningObject = warningSelector.select(yoloDetectedObjects + freshMlKitDetectedObjects)

        val forwardObstacles = forwardObstacleSelector.select(
            detections = warningDetections,
            frameWidth = width,
            frameHeight = height,
            config = detectionConfig
        )
        val warningDecision = warningDecisionMaker.decide(forwardObstacles)
        val stabilizedDecision = warningStabilizer.stabilize(warningDecision)

        val inferenceTime = detectionEndTimeMs - start
        metricsCollector.recordYoloInferenceTime(inferenceTime)
        val topOverlayObject = overlayDetections.maxByOrNull { it.confidence }
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
        val crowdDecision = CrowdDecisionEvaluator.evaluate(overlayDetections)
        val crowdCandidate = crowdDecision.toWarningCandidate()
        val selectedCandidate = warningCandidateSelector.selectWithCrowd(warningCandidates, crowdCandidate)
        val selectedCandidateAfterCooldown = selectedCandidate?.let { candidate ->
            WarningFeedbackPolicy.applyCooldown(
                candidate = candidate,
                cooldownManager = warningCooldownManager,
                nowMs = start
            )
        }
        val crowdCooldownPassed = selectedCandidateAfterCooldown?.warningKey == crowdDecision.warningKey &&
            selectedCandidateAfterCooldown?.feedback?.shouldNotify == true
        logCrowdDecision(crowdDecision, crowdCooldownPassed)
        logSelectedWarningCandidate(selectedCandidateAfterCooldown)
        val selectedFeedback = selectedCandidateAfterCooldown?.feedback
        val selectedWarningLabel = stabilizedDecision.obstacle?.detection?.label
            ?: selectedWarningObject?.label
            ?: selectedCandidateAfterCooldown?.label
            ?: "none"
        val warningMessage = stabilizedDecision.message
        val riskLevel = stabilizedDecision.riskLevel
        val beepLevel = stabilizedDecision.beepLevel
        val voiceLevel = stabilizedDecision.voiceLevel
        val vibrationLevel = stabilizedDecision.vibrationLevel
        val feedbackRiskLevel = selectedFeedback?.riskLevel ?: RiskLevel.NONE
        val feedbackBeepLevel = selectedFeedback?.beepLevel ?: FeedbackLevel.NONE
        val feedbackVoiceLevel = selectedFeedback?.voiceLevel ?: FeedbackLevel.NONE
        val feedbackVibrationLevel = selectedFeedback?.vibrationLevel ?: FeedbackLevel.NONE
        val feedbackMessage = selectedFeedback?.message
        val feedbackShouldNotify = selectedFeedback?.shouldNotify ?: false
        val feedbackPriority = selectedCandidateAfterCooldown?.priority
        val feedbackWarningKey = selectedCandidateAfterCooldown?.warningKey
        val warningMotionDirection = stabilizedDecision.obstacle?.detection?.motionDirection
        val warningApproachSpeedLevel = stabilizedDecision.obstacle?.detection?.approachSpeedLevel
        val warningObjectMovementState = stabilizedDecision.obstacle?.detection?.objectMovementState
        val warningUserObjectRelation = stabilizedDecision.obstacle?.detection?.userObjectRelation
        val warningCategory = stabilizedDecision.obstacle?.category
        val warningProximityLevel = stabilizedDecision.obstacle?.proximityLevel
        metricsCollector.recordWarning(riskLevel)
        Log.d(
            WARNING_FEEDBACK_TAG,
            "actualOutputSuppressed label=${selectedCandidateAfterCooldown?.label ?: selectedWarningLabel} " +
                "priority=$feedbackPriority risk=$feedbackRiskLevel beep=$feedbackBeepLevel voice=$feedbackVoiceLevel " +
                "vibration=$feedbackVibrationLevel message=$feedbackMessage shouldNotify=$feedbackShouldNotify key=$feedbackWarningKey"
        )
        logDetectionTiming(
            DETECTION_TIMING_TAG,
                "detectionEnd=$detectionEndTimeMs inference=${inferenceTime}ms skipped=$skippedFrameCount " +
                "rawCount=${mapped.size} visibleCount=${visibleMapped.size} " +
                "overlayWhitelistCount=${overlayDetections.size} policyFilteredCount=${warningDetections.size} " +
                "ignoredLabels=${formatIgnoredLabels(ignoredLabels)} " +
                "emptyReason=${buildEmptyOverlayReason(mapped, visibleMapped, warningDetections, overlayDetections)}"
        )

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
            if (feedbackMessage == null || !feedbackShouldNotify) {
                warningMessageTextView.text = ""
                warningMessageTextView.visibility = View.GONE
            } else {
                warningMessageTextView.text = feedbackMessage
                warningMessageTextView.visibility = View.VISIBLE
            }
            debugTextView.text = if (SHOW_DEBUG_INFO) {
                buildString {
                    appendLine("Frame: ${width}x$height / crop: ${cropRect.width()}x${cropRect.height()}")
                    appendLine("YOLO raw detection count: ${mapped.size}")
                    appendLine("small box filter count: ${visibleMapped.size}")
                    appendLine("overlay whitelist count: ${overlayDetections.size}")
                    appendLine("warning policy count: ${warningDetections.size}")
                    appendLine("Ignored: ${formatIgnoredLabels(ignoredLabels)}")
                    appendLine("selected warning object label: $selectedWarningLabel")
                    appendLine("proximity: $warningProximityLevel")
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
                    append("Guide: $warningMessage")
                    appendLine()
                    appendLine("Risk: $riskLevel")
                    appendLine("Feedback: beep=$beepLevel / voice=$voiceLevel / vibrate=$vibrationLevel")
                    appendLine("PolicyFeedback: priority=$feedbackPriority / risk=$feedbackRiskLevel / beep=$feedbackBeepLevel / voice=$feedbackVoiceLevel / vibrate=$feedbackVibrationLevel / notify=$feedbackShouldNotify / key=$feedbackWarningKey")
                    appendLine("PolicyMessage: ${feedbackMessage ?: "none"}")
                    appendLine("Crowd: total=${crowdDecision.totalPersonCount} / center=${crowdDecision.centerPersonCount} / near=${crowdDecision.nearPersonCount} / level=${crowdDecision.crowdLevel} / message=${crowdDecision.message ?: "none"} / cooldown=$crowdCooldownPassed")
                    appendLine("Motion: direction=$warningMotionDirection / approachSpeed=$warningApproachSpeedLevel")
                    appendLine(
                        "GPS accuracy: ${formatAccuracy(userLocationSnapshot.accuracyMeters)}"
                    )
                    append("Policy: category=$warningCategory / proximity=$warningProximityLevel")
                    appendLine()
                    append(metricsCollector.buildSummary())
                }
            } else {
                buildString {
                    appendLine("YOLO raw detection count: ${mapped.size}")
                    appendLine("small box filter count: ${visibleMapped.size}")
                    appendLine("overlay whitelist count: ${overlayDetections.size}")
                    appendLine("warning policy count: ${warningDetections.size}")
                    appendLine("Ignored: ${formatIgnoredLabels(ignoredLabels)}")
                    appendLine("selected warning object label: $selectedWarningLabel")
                    appendLine("proximity: $warningProximityLevel")
                    appendLine("object motion state: $warningObjectMovementState")
                    appendLine("user-object relation: $warningUserObjectRelation")
                    appendLine("user motion state: ${userLocationSnapshot.motionState}")
                    appendLine("GPS speed: ${formatSpeed(userLocationSnapshot.speedMps)}")
                    appendLine("YOLO inference time: ${inferenceTime}ms")
                    appendLine("ML Kit detection count: $lastMlKitCount")
                    if (topOverlayObject != null) {
                        append("Top overlay: ${topOverlayObject.label} ${String.format("%.2f", topOverlayObject.confidence)}")
                    } else {
                        append("Top overlay: none")
                    }
                    appendLine()
                    appendLine("Risk: $riskLevel")
                    appendLine("Guide: $warningMessage")
                    appendLine("PolicyFeedback: priority=$feedbackPriority / risk=$feedbackRiskLevel / beep=$feedbackBeepLevel / voice=$feedbackVoiceLevel / vibrate=$feedbackVibrationLevel / notify=$feedbackShouldNotify / key=$feedbackWarningKey")
                    appendLine("Crowd: total=${crowdDecision.totalPersonCount} / center=${crowdDecision.centerPersonCount} / near=${crowdDecision.nearPersonCount} / level=${crowdDecision.crowdLevel} / message=${crowdDecision.message ?: "none"} / cooldown=$crowdCooldownPassed")
                    append("PolicyMessage: ${feedbackMessage ?: "none"}")
                }
            }
        }

        verboseLog(
            TAG,
            "frame=${width}x$height, warningDetections=${warningDetections.size}, " +
                "overlayDetections=${overlayDetections.size}, ignoredLabels=${formatIgnoredLabels(ignoredLabels)}, " +
                "time=${inferenceTime}ms"
        )
    }

    private fun verboseLog(tag: String, message: String) {
        if (ENABLE_VERBOSE_LOG) {
            Log.d(tag, message)
        }
    }

    private fun logDetectionTiming(tag: String, message: String) {
        Log.d(tag, message)
    }

    private fun logSelectedWarningCandidate(candidate: WarningCandidate?) {
        if (candidate == null) {
            Log.d(WARNING_SELECTED_TAG, "[WarningSelected] none")
            return
        }

        Log.d(
            WARNING_SELECTED_TAG,
            "[WarningSelected]\n" +
                "label=${candidate.label}\n" +
                "priority=${candidate.priority}\n" +
                "proximity=${candidate.proximityLevel}\n" +
                "risk=${candidate.riskLevel}\n" +
                "message=${candidate.feedback.message}\n" +
                "beep=${candidate.feedback.beepLevel}\n" +
                "vibration=${candidate.feedback.vibrationLevel}\n" +
                "voice=${candidate.feedback.voiceLevel}\n" +
                "cooldown=${candidate.feedback.shouldNotify}"
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

    private fun filterSmallBoxes(
        detections: List<DetectionResult>,
        frameWidth: Int,
        frameHeight: Int,
        config: DetectionConfig
    ): List<DetectionResult> {
        val kept = mutableListOf<DetectionResult>()

        detections.forEach { detection ->
            val boxWidth = (detection.right - detection.left).coerceAtLeast(0f)
            val boxHeight = (detection.bottom - detection.top).coerceAtLeast(0f)
            val areaRatio = getBoxAreaRatio(boxWidth, boxHeight, frameWidth, frameHeight)
            val widthRatio = boxWidth / frameWidth.coerceAtLeast(1).toFloat()
            val heightRatio = boxHeight / frameHeight.coerceAtLeast(1).toFloat()
            val keep = areaRatio >= config.minBoxAreaRatio &&
                widthRatio >= config.minBoxWidthRatio &&
                heightRatio >= config.minBoxHeightRatio

            if (keep) {
                kept.add(detection)
            } else {
                verboseLog(
                    DETECTION_FILTER_TAG,
                    "skip small box label=${detection.label}, conf=${detection.confidence}, " +
                        "areaRatio=$areaRatio, widthRatio=$widthRatio, heightRatio=$heightRatio, " +
                        "box=${formatBox(detection)}"
                )
            }
        }

        verboseLog(DETECTION_FILTER_TAG, "before=${detections.size}, after=${kept.size}")
        return kept
    }

    private fun getBoxAreaRatio(
        boxWidth: Float,
        boxHeight: Float,
        frameWidth: Int,
        frameHeight: Int
    ): Float {
        val imageArea = frameWidth.coerceAtLeast(1) * frameHeight.coerceAtLeast(1).toFloat()
        return boxWidth * boxHeight / imageArea
    }

    private fun formatBox(detection: DetectionResult): String {
        return "left=${detection.left}, top=${detection.top}, right=${detection.right}, bottom=${detection.bottom}"
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
                lastMlKitDetectedObjects = results.map { it.toDetectedObject(DetectionSource.ML_KIT) }

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
        userLocationTracker.stop()
        cameraExecutor.shutdown()
        mlKitDetector.close()
        detector.close()
        warningPlayer.release()
    }

    companion object {
        private const val SHOW_DEBUG_INFO = false
        private const val ENABLE_VERBOSE_LOG = false
        private const val TAG = "ObjectDetectionVision"
        private const val DETECTION_TIMING_TAG = "DetectionTiming"
        private const val DETECTION_FILTER_TAG = "DetectionFilter"
        private const val WARNING_FEEDBACK_TAG = "WarningFeedback"
        private const val WARNING_SELECTED_TAG = "WarningSelected"
        private const val CROWD_DECISION_TAG = "CrowdDecision"
        private const val ML_KIT_DETECT_INTERVAL_MS = 1500L
        private const val ML_KIT_WARNING_MAX_AGE_MS = 3000L
    }
}
