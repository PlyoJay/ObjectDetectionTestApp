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
import com.samin.objectdetection.mlkit.MlKitObjectDetector
import com.samin.objectdetection.model.DetectedObject
import com.samin.objectdetection.model.DetectionSource
import com.samin.objectdetection.model.toDetectedObject
import com.samin.objectdetection.motion.ObjectMotionTracker
import com.samin.objectdetection.policy.YoloDefaultPolicyRegistry
import com.samin.objectdetection.ui.BoundingBoxOverlay
import com.samin.objectdetection.warning.BeepWarningPlayer
import com.samin.objectdetection.warning.CompositeWarningPlayer
import com.samin.objectdetection.warning.ForwardObstacleSelector
import com.samin.objectdetection.warning.VibrationWarningPlayer
import com.samin.objectdetection.warning.VoiceWarningPlayer
import com.samin.objectdetection.warning.WarningDecisionMaker
import com.samin.objectdetection.warning.WarningMessageBuilder
import com.samin.objectdetection.warning.WarningPlayer
import com.samin.objectdetection.warning.WarningSelector
import com.samin.objectdetection.warning.WarningStabilizer
import com.samin.objectdetection.warning.WarningThrottle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private val warningThrottle = WarningThrottle()
    private val warningStabilizer = WarningStabilizer()
    private val objectMotionTracker = ObjectMotionTracker()
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
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) {
                startCamera()
            } else {
                debugTextView.text = "카메라 권한이 필요합니다."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val yoloDetector = VisionStyleYoloDetector(this, "yolo11n_float32.tflite").apply {
            confidenceThreshold = 0.35f
            enableDebugImageSaving = detectionConfig.enableDetectorDebugImage
        }
        detector = yoloDetector
        mlKitDetector = MlKitObjectDetector()
        warningPlayer = CompositeWarningPlayer(
            listOf(
                BeepWarningPlayer(),
                VoiceWarningPlayer(this),
                VibrationWarningPlayer(this)
            )
        )

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
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val denied = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (denied.isEmpty()) startCamera() else permissionLauncher.launch(denied.toTypedArray())
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
                    Log.d(
                        DETECTION_TIMING_TAG,
                        "frameReceived=$frameReceivedTimeMs isDetecting=${isProcessing.get()}"
                    )
                    calculateFps()

                    if (frameReceivedTimeMs - lastDetectionStartTimeMs < detectionConfig.detectIntervalMs) {
                        skippedFrameCount++
                        Log.d(
                            DETECTION_TIMING_TAG,
                            "skipFrameByInterval skipped=$skippedFrameCount intervalMs=${detectionConfig.detectIntervalMs}"
                        )
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    if (!isProcessing.compareAndSet(false, true)) {
                        skippedFrameCount++
                        Log.d(
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
        Log.d(
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
            DetectionResult(
                label = res.label,
                confidence = res.confidence,
                left = res.left + cropRect.left,
                top = res.top + cropRect.top,
                right = res.right + cropRect.left,
                bottom = res.bottom + cropRect.top,
                frameTimestampMs = start
            )
        }
        val visibleMapped = filterSmallBoxes(
            detections = mapped,
            frameWidth = width,
            frameHeight = height,
            config = detectionConfig
        )
        val motionTracked = objectMotionTracker.update(
            detections = visibleMapped,
            frameWidth = width,
            frameHeight = height,
            timestampMs = start
        )

        val filtered = motionTracked.filter { detection ->
            val policy = YoloDefaultPolicyRegistry.get(detection.label)
            policy != null && detection.confidence >= policy.minConfidence
        }
        val yoloDetectedObjects = filtered.map { it.toDetectedObject(DetectionSource.YOLO) }
        val freshMlKitDetectedObjects = lastMlKitDetectedObjects.filter {
            start - it.timestampMs <= ML_KIT_WARNING_MAX_AGE_MS
        }
        val selectedWarningObject = warningSelector.select(yoloDetectedObjects + freshMlKitDetectedObjects)
        val selectedWarningMessage = selectedWarningObject?.let { WarningMessageBuilder.build(it) }

        val forwardObstacles = forwardObstacleSelector.select(
            detections = filtered,
            frameWidth = width,
            frameHeight = height,
            config = detectionConfig
        )
        val warningDecision = warningDecisionMaker.decide(forwardObstacles)
        val stabilizedDecision = warningStabilizer.stabilize(warningDecision)

        val inferenceTime = detectionEndTimeMs - start
        val topObject = filtered.maxByOrNull { it.confidence }
        val warningMessage = stabilizedDecision.message
        val riskLevel = stabilizedDecision.riskLevel
        val beepLevel = stabilizedDecision.beepLevel
        val voiceLevel = stabilizedDecision.voiceLevel
        val vibrationLevel = stabilizedDecision.vibrationLevel
        warningPlayer.playIfNeeded(stabilizedDecision)
        Log.d(
            DETECTION_TIMING_TAG,
            "detectionEnd=$detectionEndTimeMs inference=${inferenceTime}ms skipped=$skippedFrameCount"
        )

        runOnUiThread {
            val overlayUpdateTimeMs = System.currentTimeMillis()
            val newestDetectionTimestamp = filtered.maxOfOrNull { it.frameTimestampMs } ?: overlayUpdateTimeMs
            val resultAgeMs = overlayUpdateTimeMs - newestDetectionTimestamp
            Log.d(
                DETECTION_TIMING_TAG,
                "overlayUpdate=$overlayUpdateTimeMs resultAge=${resultAgeMs}ms detectionCount=${filtered.size}"
            )
            overlayView.updateDetections(filtered, width, height, inferenceTime, currentFps)
            if (selectedWarningMessage == null) {
                warningMessageTextView.text = ""
                warningMessageTextView.visibility = View.GONE
            } else if (warningThrottle.canShow(selectedWarningMessage, overlayUpdateTimeMs)) {
                warningMessageTextView.text = selectedWarningMessage
                warningMessageTextView.visibility = View.VISIBLE
            }
            debugTextView.text = buildString {
                appendLine("Frame: ${width}x$height / crop: ${cropRect.width()}x${cropRect.height()}")
                appendLine("Detect: ${filtered.size} / ${inferenceTime}ms / FPS=$currentFps")
                appendLine("ML Kit: $lastMlKitCount / ${lastMlKitTimeMs}ms")
                if (topObject != null) {
                    append("Top: ${topObject.label} ${String.format("%.2f", topObject.confidence)}")
                } else {
                    append("Top: none")
                }
                appendLine()
                append("Guide: $warningMessage")
                appendLine()
                appendLine("Risk: $riskLevel")
                append("Feedback: beep=$beepLevel / voice=$voiceLevel / vibrate=$vibrationLevel")
            }
        }

        Log.d(TAG, "frame=${width}x$height, detections=${filtered.size}, time=${inferenceTime}ms")
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
                Log.d(
                    DETECTION_FILTER_TAG,
                    "skip small box label=${detection.label}, conf=${detection.confidence}, " +
                        "areaRatio=$areaRatio, widthRatio=$widthRatio, heightRatio=$heightRatio, " +
                        "box=${formatBox(detection)}"
                )
            }
        }

        Log.d(DETECTION_FILTER_TAG, "before=${detections.size}, after=${kept.size}")
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
            frameCount = 0
            lastFpsTime = now
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        mlKitDetector.close()
        detector.close()
        warningPlayer.close()
    }

    companion object {
        private const val TAG = "ObjectDetectionVision"
        private const val DETECTION_TIMING_TAG = "DetectionTiming"
        private const val DETECTION_FILTER_TAG = "DetectionFilter"
        private const val ML_KIT_DETECT_INTERVAL_MS = 1500L
        private const val ML_KIT_WARNING_MAX_AGE_MS = 3000L
    }
}
