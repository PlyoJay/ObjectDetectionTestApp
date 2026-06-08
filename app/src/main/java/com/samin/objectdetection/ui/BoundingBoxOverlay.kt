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
import com.samin.objectdetection.model.toDetectedObject

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
        textSize = 38f
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
        postDelayed({ clearStaleDetectionsIfNeeded() }, MAX_OVERLAY_AGE_MS)
        invalidate()
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
        postDelayed({ clearStaleDetectionsIfNeeded() }, MAX_OVERLAY_AGE_MS)
        invalidate()
    }

    fun setDrawingEnabled(enabled: Boolean) {
        this.enabled = enabled
        invalidate()
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
        val now = System.currentTimeMillis()
        val freshDetections = detections.filter { now - it.frameTimestampMs <= MAX_OVERLAY_AGE_MS }
        val freshMlKitDetections = mlKitDetections.filter { now - it.frameTimestampMs <= MAX_OVERLAY_AGE_MS }

        freshDetections.forEach { res ->
            val left = transform.offsetX + res.left * transform.scale
            val top = transform.offsetY + res.top * transform.scale
            val right = transform.offsetX + res.right * transform.scale
            val bottom = transform.offsetY + res.bottom * transform.scale

            boxPaint.color = if (res.label == "person" || res.label == "사람") {
                Color.parseColor("#FF69B4")
            } else {
                Color.parseColor("#00BFFF")
            }

            canvas.drawRoundRect(left, top, right, bottom, 20f, 20f, boxPaint)

            val label = buildDebugLabel(res, DetectionSource.YOLO)
            val textWidth = textPaint.measureText(label)
            val bgTop = (top - 56f).coerceAtLeast(0f)
            canvas.drawRoundRect(left, bgTop, left + textWidth + 28f, bgTop + 52f, 10f, 10f, bgPaint)
            canvas.drawText(label, left + 14f, bgTop + 38f, textPaint)
        }

        freshMlKitDetections.forEach { res ->
            val left = transform.offsetX + res.left * transform.scale
            val top = transform.offsetY + res.top * transform.scale
            val right = transform.offsetX + res.right * transform.scale
            val bottom = transform.offsetY + res.bottom * transform.scale

            canvas.drawRoundRect(left, top, right, bottom, 20f, 20f, mlKitBoxPaint)

            val label = buildDebugLabel(res, DetectionSource.ML_KIT)
            val textWidth = textPaint.measureText(label)
            val bgTop = (top - 56f).coerceAtLeast(0f)
            canvas.drawRoundRect(left, bgTop, left + textWidth + 28f, bgTop + 52f, 10f, 10f, bgPaint)
            canvas.drawText(label, left + 14f, bgTop + 38f, textPaint)
        }
    }

    private fun buildDebugLabel(
        detection: DetectionResult,
        source: DetectionSource
    ): String {
        val detectedObject = detection.toDetectedObject(source)
        return "${detectedObject.label} ${detectedObject.motionDirection} " +
            "${detection.approachSpeedLevel} " +
            "${String.format("%.2f", detectedObject.confidence)} " +
            "${detectedObject.category}/${detectedObject.priority}/${detectedObject.source}"
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
            detections = emptyList()
            changed = true
        }

        if (mlKitDetections.isNotEmpty() && now - lastMlKitUpdatedAtMs > MAX_OVERLAY_AGE_MS) {
            mlKitDetections = emptyList()
            changed = true
        }

        if (changed) {
            android.util.Log.d(
                DETECTION_TIMING_TAG,
                "overlay stale cleared ageMs=${now - lastDetectionUpdatedAtMs}"
            )
            invalidate()
        }
    }

    private fun logOverlayAge(detections: List<DetectionResult>) {
        val now = System.currentTimeMillis()
        val newestTimestamp = detections.maxOfOrNull { it.frameTimestampMs } ?: now
        val ageMs = now - newestTimestamp
        android.util.Log.d(
            DETECTION_TIMING_TAG,
            "overlayAge=${ageMs}ms detectionCount=${detections.size}"
        )
    }

    companion object {
        private const val MAX_OVERLAY_AGE_MS = 500L
        private const val DETECTION_TIMING_TAG = "DetectionTiming"
        private const val TRANSFORM_TAG = "OverlayTransform"
    }

    private data class PreviewTransform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
        val displayedWidth: Float,
        val displayedHeight: Float
    )
}
