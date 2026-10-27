package com.samin.objectdetection.arcore.yolo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import java.util.Locale

/** Receives viewport-normalized boxes and precomputed Z. Canvas never samples depth or runs YOLO. */
class ArCoreYoloOverlay(context: Context) : View(context) {
    var currentEpoch: () -> Long = { 0L }
    var showConfidence = false
    var staleTimeoutNs = 1_000_000_000L
    private var frame: ArYoloFrame? = null
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xb0000000.toInt() }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 30f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    fun update(value: ArYoloFrame?) { frame = value; postInvalidateOnAnimation() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val value = frame ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        if (!value.isFresh(now, currentEpoch(), staleTimeoutNs) || width <= 20 || height <= 20) return
        val placed = mutableListOf<RectF>()
        for (obj in value.objects) {
            val b = obj.viewBox
            if (b.right <= 0 || b.bottom <= 0 || b.left >= 1 || b.top >= 1) continue
            val left = (b.left * width).coerceIn(0f, width.toFloat())
            val top = (b.top * height).coerceIn(0f, height.toFloat())
            val right = (b.right * width).coerceIn(0f, width.toFloat())
            val bottom = (b.bottom * height).coerceIn(0f, height.toFloat())
            if (right <= left || bottom <= top) continue
            boxPaint.color = when (obj.detection.label) {
                "person" -> Color.rgb(255, 105, 180)
                "cat" -> Color.rgb(186, 104, 200)
                "dog" -> Color.rgb(38, 198, 218)
                else -> Color.rgb(0, 191, 255)
            }
            canvas.drawRoundRect(left, top, right, bottom, 16f, 16f, boxPaint)
            val meters = obj.depth.meters?.takeIf { it.isFinite() && it > 0 && value.hasFreshDepth(now) }
            val label = obj.detection.label + (meters?.let {
                if (it < .05f) " 0.1m 미만" else " 약 ${String.format(Locale.US, "%.1f", it)}m"
            } ?: "") +
                if (showConfidence) " ${String.format(Locale.US, "%.2f", obj.detection.confidence)}" else ""
            val paint = TextPaint(text).apply { textSize = minOf(textSize, width / 15f) }
            val textWidth = kotlin.math.ceil(paint.measureText(label).toDouble()).toInt().coerceIn(1, width - 20)
            val layout = StaticLayout.Builder.obtain(label, 0, label.length, paint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build()
            val labelWidth = textWidth + 20f; val labelHeight = layout.height + 12f
            if (labelHeight > height) continue
            val x = left.coerceIn(0f, (width - labelWidth).coerceAtLeast(0f))
            val candidates = listOf(top - labelHeight - 6, bottom + 6, top + 8) + placed.map { it.bottom + 4 }
            val rectangle = candidates.asSequence().filter { it >= 0 && it + labelHeight <= height }
                .map { RectF(x, it, x + labelWidth, it + labelHeight) }.firstOrNull { candidate -> placed.none { RectF.intersects(it, candidate) } } ?: continue
            placed.add(rectangle)
            canvas.drawRoundRect(rectangle, 8f, 8f, background)
            canvas.save(); canvas.translate(rectangle.left + 10, rectangle.top + 6); layout.draw(canvas); canvas.restore()
        }
        // Also expires distances/boxes when inference or the ARCore stream stops producing results.
        postInvalidateDelayed(100)
    }
}
