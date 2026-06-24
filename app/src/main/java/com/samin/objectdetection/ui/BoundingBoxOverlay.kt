package com.samin.objectdetection.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.model.DetectionSource
import java.util.Locale

class BoundingBoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var detections: List<DetectionResult> = emptyList()
    private var mlKitDetections: List<DetectionResult> = emptyList()
    private var frameWidth: Int = 1
    private var frameHeight: Int = 1
    private var inferenceTimeMs: Long = 0L
    private var fps: Int = 0
    private var enabled = true
    private var debugMode: OverlayDebugMode = OverlayDebugMode.SIMPLE
    private var lastDetectionUpdatedAtMs: Long = 0L
    private var lastMlKitUpdatedAtMs: Long = 0L

    private val boxPaint = Paint().apply {
        color = Color.parseColor("#00BFFF")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = CornerPathEffect(15f)
        isAntiAlias = true
    }

    private val mlKitBoxPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = CornerPathEffect(15f)
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val infoPaint = Paint().apply {
        color = Color.parseColor("#00FF7F")
        textSize = 42f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        isAntiAlias = true
        setShadowLayer(8f, 3f, 3f, Color.BLACK)
    }

    fun updateDetections(
        detections: List<DetectionResult>,
        frameWidth: Int,
        frameHeight: Int,
        inferenceTimeMs: Long,
        fps: Int
    ) {
        this.detections = detections
        this.frameWidth = frameWidth.coerceAtLeast(1)
        this.frameHeight = frameHeight.coerceAtLeast(1)
        this.inferenceTimeMs = inferenceTimeMs
        this.fps = fps
        lastDetectionUpdatedAtMs = System.currentTimeMillis()
        logOverlayAge(this.detections)
        logDetectionDetails(this.detections, DetectionSource.YOLO)
        postDelayed({ clearStaleDetectionsIfNeeded() }, MAX_OVERLAY_AGE_MS)
        postInvalidateOnAnimation()
    }

    fun updateMlKitDetections(
        detections: List<DetectionResult>,
        frameWidth: Int,
        frameHeight: Int
    ) {
        this.mlKitDetections = detections
        this.frameWidth = frameWidth.coerceAtLeast(1)
        this.frameHeight = frameHeight.coerceAtLeast(1)
        lastMlKitUpdatedAtMs = System.currentTimeMillis()
        logDetectionDetails(this.mlKitDetections, DetectionSource.ML_KIT)
        postDelayed({ clearStaleDetectionsIfNeeded() }, MAX_OVERLAY_AGE_MS)
        postInvalidateOnAnimation()
    }

    fun setDebugMode(debugMode: OverlayDebugMode) {
        this.debugMode = debugMode
        postInvalidateOnAnimation()
    }

    fun setDrawingEnabled(enabled: Boolean) {
        this.enabled = enabled
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        clearStaleDetectionsIfNeeded()

        // 중앙 감시 구역 가이드
//        val guidePaint = Paint().apply {
//            color = Color.parseColor("#00E676")
//            strokeWidth = 5f
//            alpha = 180
//        }
//        canvas.drawLine(width * 0.33f, 0f, width * 0.33f, height.toFloat(), guidePaint)
//        canvas.drawLine(width * 0.67f, 0f, width * 0.67f, height.toFloat(), guidePaint)

        if (!enabled) return

        val transform = calculateFitCenterTransform(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            viewWidth = width,
            viewHeight = height
        )
        val freshDetections = detections
        val freshMlKitDetections = mlKitDetections

        freshDetections.forEach { res ->
            val left = transform.offsetX + res.left * transform.scale
            val top = transform.offsetY + res.top * transform.scale
            val right = transform.offsetX + res.right * transform.scale
            val bottom = transform.offsetY + res.bottom * transform.scale

            boxPaint.color = when {
                res.isIgnored -> Color.parseColor("#888888")
                res.label == "person" || res.label == "사람" -> Color.parseColor("#FF69B4")
                else -> Color.parseColor("#00BFFF")
            }

            canvas.drawRoundRect(left, top, right, bottom, 20f, 20f, boxPaint)
            drawLabelIfNeeded(canvas, res, DetectionSource.YOLO, left, top)
        }

        freshMlKitDetections.forEach { res ->
            val left = transform.offsetX + res.left * transform.scale
            val top = transform.offsetY + res.top * transform.scale
            val right = transform.offsetX + res.right * transform.scale
            val bottom = transform.offsetY + res.bottom * transform.scale

            canvas.drawRoundRect(left, top, right, bottom, 20f, 20f, mlKitBoxPaint)
            drawLabelIfNeeded(canvas, res, DetectionSource.ML_KIT, left, top)
        }
    }

    private fun drawLabelIfNeeded(
        canvas: Canvas,
        detection: DetectionResult,
        source: DetectionSource,
        left: Float,
        top: Float
    ) {
        val label = buildLabel(detection, source) ?: return
        val textWidth = textPaint.measureText(label)
        val bgTop = (top - LABEL_HEIGHT_PX).coerceAtLeast(0f)
        canvas.drawRoundRect(left, bgTop, left + textWidth + LABEL_HORIZONTAL_PADDING_PX, bgTop + LABEL_BG_HEIGHT_PX, 8f, 8f, bgPaint)
        canvas.drawText(label, left + LABEL_TEXT_LEFT_PADDING_PX, bgTop + LABEL_BASELINE_PX, textPaint)
    }

    private fun buildLabel(
        detection: DetectionResult,
        source: DetectionSource
    ): String? {
        return when (debugMode) {
            OverlayDebugMode.NONE -> null
            OverlayDebugMode.SIMPLE -> {
                if (source == DetectionSource.ML_KIT) {
                    detection.label
                } else {
                    "${detection.label} ${detection.riskLevel}"
                }
            }
            OverlayDebugMode.FULL -> buildFullDebugLabel(detection, source)
        }
    }

    private fun buildFullDebugLabel(
        detection: DetectionResult,
        source: DetectionSource
    ): String {
        return buildString {
            append("${detection.label} ${source.name} ")
            append("c=${String.format(Locale.US, "%.2f", detection.confidence)} ")
            append("a=${String.format(Locale.US, "%.3f", detection.bboxAreaRatio)} ")
            append("h=${String.format(Locale.US, "%.3f", detection.bboxHeightRatio)} ")
            append("p=${detection.objectPriority} ")
            append("${detection.proximityLevel}/${detection.riskLevel}/${detection.horizontalPosition} ")
            append("b=${detection.warningFeedback.beepLevel} ")
            append("v=${detection.warningFeedback.vibrationLevel} ")
            append("voice=${detection.warningFeedback.voiceLevel} ")
            append("notify=${detection.warningFeedback.shouldNotify} ")
            append("msg=${detection.warningFeedback.message ?: "none"}")
            if (detection.isIgnored) append(" IGNORED")
        }
    }

    private fun logDetectionDetails(
        detections: List<DetectionResult>,
        source: DetectionSource
    ) {
        detections.forEach { detection ->
            Log.d(
                WARNING_DETAIL_TAG,
                "source=${source.name} label=${detection.label} " +
                    "confidence=${String.format(Locale.US, "%.2f", detection.confidence)} " +
                    "bboxAreaRatio=${String.format(Locale.US, "%.3f", detection.bboxAreaRatio)} " +
                    "bboxHeightRatio=${String.format(Locale.US, "%.3f", detection.bboxHeightRatio)} " +
                    "centerXRatio=${String.format(Locale.US, "%.3f", detection.centerXRatio)} " +
                    "centerYRatio=${String.format(Locale.US, "%.3f", detection.centerYRatio)} " +
                    "horizontalPosition=${detection.horizontalPosition} " +
                    "riskObjectCategory=${detection.riskObjectCategory} " +
                    "objectPriority=${detection.objectPriority} " +
                    "proximityLevel=${detection.proximityLevel} " +
                    "riskLevel=${detection.riskLevel} " +
                    "beep=${detection.warningFeedback.beepLevel} " +
                    "vibration=${detection.warningFeedback.vibrationLevel} " +
                    "voice=${detection.warningFeedback.voiceLevel} " +
                    "shouldNotify=${detection.warningFeedback.shouldNotify} " +
                    "message=${detection.warningFeedback.message ?: "none"} " +
                    "ignored=${detection.isIgnored}"
            )
        }
    }

    private fun calculateFitCenterTransform(
        frameWidth: Int,
        frameHeight: Int,
        viewWidth: Int,
        viewHeight: Int
    ): PreviewTransform {
        val safeFrameWidth = frameWidth.coerceAtLeast(1)
        val safeFrameHeight = frameHeight.coerceAtLeast(1)
        val safeViewWidth = viewWidth.coerceAtLeast(1)
        val safeViewHeight = viewHeight.coerceAtLeast(1)
        val scale = minOf(
            safeViewWidth / safeFrameWidth.toFloat(),
            safeViewHeight / safeFrameHeight.toFloat()
        )
        val displayedWidth = safeFrameWidth * scale
        val displayedHeight = safeFrameHeight * scale
        val offsetX = (safeViewWidth - displayedWidth) / 2f
        val offsetY = (safeViewHeight - displayedHeight) / 2f

        logTransformIfAspectMismatch(
            safeFrameWidth,
            safeFrameHeight,
            safeViewWidth,
            safeViewHeight,
            scale,
            offsetX,
            offsetY
        )

        return PreviewTransform(
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
            displayedWidth = displayedWidth,
            displayedHeight = displayedHeight
        )
    }

    private fun logTransformIfAspectMismatch(
        frameWidth: Int,
        frameHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        val frameAspect = frameWidth / frameHeight.toFloat()
        val viewAspect = viewWidth / viewHeight.toFloat()
        val swappedFrameAspect = frameHeight / frameWidth.toFloat()
        val currentDelta = kotlin.math.abs(frameAspect - viewAspect)
        val swappedDelta = kotlin.math.abs(swappedFrameAspect - viewAspect)
        if (currentDelta > 0.05f || swappedDelta < currentDelta) {
            Log.d(
                TRANSFORM_TAG,
                "fitCenter frame=${frameWidth}x$frameHeight view=${viewWidth}x$viewHeight " +
                    "scale=$scale offsetX=$offsetX offsetY=$offsetY swapSuggested=${swappedDelta < currentDelta}"
            )
        }
    }

    private fun clearStaleDetectionsIfNeeded() {
        val now = System.currentTimeMillis()
        var changed = false

        if (detections.isNotEmpty() && now - lastDetectionUpdatedAtMs > MAX_OVERLAY_AGE_MS) {
            Log.d(
                DETECTION_TIMING_TAG,
                "overlay stale clear source=YOLO ageMs=${now - lastDetectionUpdatedAtMs} " +
                    "count=${detections.size}"
            )
            detections = emptyList()
            changed = true
        }

        if (mlKitDetections.isNotEmpty() && now - lastMlKitUpdatedAtMs > MAX_OVERLAY_AGE_MS) {
            Log.d(
                DETECTION_TIMING_TAG,
                "overlay stale clear source=ML_KIT ageMs=${now - lastMlKitUpdatedAtMs} " +
                    "count=${mlKitDetections.size}"
            )
            mlKitDetections = emptyList()
            changed = true
        }

        if (changed) {
            postInvalidateOnAnimation()
        }
    }

    private fun logOverlayAge(detections: List<DetectionResult>) {
        val now = System.currentTimeMillis()
        val updateAgeMs = now - lastDetectionUpdatedAtMs
        val newestFrameTimestamp = detections.maxOfOrNull { it.frameTimestampMs } ?: now
        val frameAgeMs = now - newestFrameTimestamp
        Log.d(
            DETECTION_TIMING_TAG,
            "overlayUpdateAge=${updateAgeMs}ms overlayResultAge=${frameAgeMs}ms overlayCount=${detections.size}"
        )
    }

    companion object {
        private const val MAX_OVERLAY_AGE_MS = 1500L
        private const val DETECTION_TIMING_TAG = "DetectionTiming"
        private const val TRANSFORM_TAG = "OverlayTransform"
        private const val WARNING_DETAIL_TAG = "GotoroWarning"
        private const val LABEL_HEIGHT_PX = 44f
        private const val LABEL_BG_HEIGHT_PX = 40f
        private const val LABEL_BASELINE_PX = 30f
        private const val LABEL_HORIZONTAL_PADDING_PX = 20f
        private const val LABEL_TEXT_LEFT_PADDING_PX = 10f
    }

    private data class PreviewTransform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
        val displayedWidth: Float,
        val displayedHeight: Float
    )
}
