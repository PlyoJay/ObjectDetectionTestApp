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
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.ObjectDetector
import com.samin.objectdetection.camera.toBitmapSafe
import com.samin.objectdetection.detector.VisionStyleYoloDetector
import com.samin.objectdetection.mlkit.MlKitObjectDetector
import com.samin.objectdetection.policy.YoloDefaultPolicyRegistry
import com.samin.objectdetection.ui.BoundingBoxOverlay
import com.samin.objectdetection.warning.WarningDecisionMaker
import com.samin.objectdetection.warning.WarningMessageBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: BoundingBoxOverlay
    private lateinit var debugTextView: TextView
    private lateinit var toggleButton: Button

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var detector: ObjectDetector
    private lateinit var mlKitDetector: MlKitObjectDetector

    @Volatile
    private var isProcessing = AtomicBoolean(false)
    private val isMlKitProcessing = AtomicBoolean(false)
    @Volatile
    private var lastMlKitCount = 0

    @Volatile
    private var lastMlKitTimeMs = 0L

    private var lastFpsTime = 0L
    private var lastMlKitDetectionTime = 0L
    private var frameCount = 0
    private var currentFps = 0
    private var overlayEnabled = true

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
        }
        detector = yoloDetector
        mlKitDetector = MlKitObjectDetector()

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

        overlayView.bringToFront()
        debugTextView.bringToFront()
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
                    calculateFps()

                    if (!isProcessing.compareAndSet(false, true)) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    try {
                        val bitmap = imageProxy.toBitmapSafe()
                        if (bitmap == null) {
                            imageProxy.close()
                            isProcessing.set(false)
                            return@setAnalyzer
                        }

                        processBitmap(bitmap)
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

    private fun processBitmap(bitmap: Bitmap) {
        val start = System.currentTimeMillis()

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

        val mapped = croppedResults.map { res ->
            DetectionResult(
                label = res.label,
                confidence = res.confidence,
                left = res.left + cropRect.left,
                top = res.top + cropRect.top,
                right = res.right + cropRect.left,
                bottom = res.bottom + cropRect.top
            )
        }

        val filtered = mapped.filter { detection ->
            val policy = YoloDefaultPolicyRegistry.get(detection.label)
            policy != null && detection.confidence >= policy.minConfidence
        }

        val inferenceTime = System.currentTimeMillis() - start
        val topObject = filtered.maxByOrNull { it.confidence }
        val warningCandidate = WarningDecisionMaker.selectBest(
            detections = filtered,
            frameWidth = width,
            frameHeight = height
        )
        val warningMessage = warningCandidate?.let {
            WarningMessageBuilder.build(it)
        } ?: "안내 대상 없음"

        runOnUiThread {
            overlayView.updateDetections(filtered, width, height, inferenceTime, currentFps)
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
            }
        }

        Log.d(TAG, "frame=${width}x$height, detections=${filtered.size}, time=${inferenceTime}ms")
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
                        bottom = box.bottom * scaleY
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
            frameCount = 0
            lastFpsTime = now
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        mlKitDetector.close()
        detector.close()
    }

    companion object {
        private const val TAG = "ObjectDetectionVision"
        private const val ML_KIT_DETECT_INTERVAL_MS = 1500L
    }
}
