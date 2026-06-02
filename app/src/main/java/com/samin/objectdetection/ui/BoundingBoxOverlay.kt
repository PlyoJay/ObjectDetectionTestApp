package com.samin.objectdetection.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.samin.objectdetection.detector.DetectionResult

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
        invalidate()
    }

    fun setDrawingEnabled(enabled: Boolean) {
        this.enabled = enabled
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawText("연산: ${inferenceTimeMs}ms | FPS: $fps | 객체: ${detections.size}", 40f, 70f, infoPaint)

        // 중앙 감시 구역 가이드
//        val guidePaint = Paint().apply {
//            color = Color.parseColor("#00E676")
//            strokeWidth = 5f
//            alpha = 180
//        }
//        canvas.drawLine(width * 0.33f, 0f, width * 0.33f, height.toFloat(), guidePaint)
//        canvas.drawLine(width * 0.67f, 0f, width * 0.67f, height.toFloat(), guidePaint)

        if (!enabled) return

        val scaleX = width / frameWidth.toFloat()
        val scaleY = height / frameHeight.toFloat()

        detections.forEach { res ->
            val left = res.left * scaleX
            val top = res.top * scaleY
            val right = res.right * scaleX
            val bottom = res.bottom * scaleY

            boxPaint.color = if (res.label == "person" || res.label == "사람") {
                Color.parseColor("#FF69B4")
            } else {
                Color.parseColor("#00BFFF")
            }

            canvas.drawRoundRect(left, top, right, bottom, 20f, 20f, boxPaint)

            val label = "${res.label} ${String.format("%.2f", res.confidence)}"
            val textWidth = textPaint.measureText(label)
            val bgTop = (top - 56f).coerceAtLeast(0f)
            canvas.drawRoundRect(left, bgTop, left + textWidth + 28f, bgTop + 52f, 10f, 10f, bgPaint)
            canvas.drawText(label, left + 14f, bgTop + 38f, textPaint)
        }

        mlKitDetections.forEach { res ->
            val left = res.left * scaleX
            val top = res.top * scaleY
            val right = res.right * scaleX
            val bottom = res.bottom * scaleY

            canvas.drawRoundRect(left, top, right, bottom, 20f, 20f, mlKitBoxPaint)

            val label = "MLKit"
            canvas.drawText(label, left + 10f, top.coerceAtLeast(40f), textPaint)
        }
    }
}
