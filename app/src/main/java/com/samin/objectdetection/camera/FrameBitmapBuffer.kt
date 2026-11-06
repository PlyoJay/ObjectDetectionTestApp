package com.samin.objectdetection.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import kotlin.math.roundToInt

/**
 * Serial-owner scratch storage, never shared with an asynchronous pixel reader.
 * Results are borrowed until the next render/close; full-frame/zero-rotation returns the source.
 * The owner must drain its reader before closing. This buffer does not own source bitmaps.
 */
internal class FrameBitmapBuffer : AutoCloseable {
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val matrix = Matrix()
    private val sourceRect = Rect()
    private val destination = RectF()
    private val bounds = RectF()
    private val rotationPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var closed = false

    fun rotate(source: Bitmap, rotationDegrees: Int): Bitmap {
        check(!closed) { "Frame bitmap buffer is closed" }
        require(rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270)
        check(!source.isRecycled) { "Source bitmap is recycled" }
        if (rotationDegrees == 0) return source
        return render(source, 0, 0, source.width, source.height, rotationDegrees)
    }

    fun crop(source: Bitmap, rect: Rect): Bitmap {
        check(!closed) { "Frame bitmap buffer is closed" }
        check(!source.isRecycled) { "Source bitmap is recycled" }
        require(rect.left >= 0 && rect.top >= 0 && rect.right <= source.width &&
            rect.bottom <= source.height && rect.width() > 0 && rect.height() > 0)
        if (rect.left == 0 && rect.top == 0 && rect.right == source.width && rect.bottom == source.height) return source
        return render(source, rect.left, rect.top, rect.width(), rect.height(), 0)
    }

    fun owns(frame: Bitmap): Boolean = frame === bitmap

    private fun render(source: Bitmap, x: Int, y: Int, width: Int, height: Int, degrees: Int): Bitmap {
        require(source !== bitmap) { "A borrowed output cannot be its own source" }
        matrix.reset()
        if (degrees != 0) matrix.postRotate(degrees.toFloat())
        sourceRect.set(x, y, x + width, y + height)
        destination.set(0f, 0f, width.toFloat(), height.toFloat())
        matrix.mapRect(bounds, destination)
        val outputWidth = bounds.width().roundToInt()
        val outputHeight = bounds.height().roundToInt()

        // CameraX.toBitmap produces software ARGB_8888. Preserve the platform implementation
        // for other configs/HDR gainmaps rather than silently changing their format/metadata.
        val colorSpace = source.colorSpace
        if (source.config != Bitmap.Config.ARGB_8888 || colorSpace == null ||
            (Build.VERSION.SDK_INT >= 34 && source.hasGainmap())) {
            val result = Bitmap.createBitmap(source, x, y, width, height, matrix, degrees != 0)
            releaseStorage()
            bitmap = result
            return result
        }

        val old = bitmap
        val target = if (canvas != null && old != null && !old.isRecycled &&
            old.width == outputWidth && old.height == outputHeight && old.colorSpace == colorSpace) {
            old
        } else {
            val created = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888,
                source.hasAlpha(), colorSpace)
            releaseStorage()
            bitmap = created
            canvas = Canvas(created)
            created
        }
        target.density = source.density
        target.setHasAlpha(source.hasAlpha())
        target.setPremultiplied(source.isPremultiplied)
        // Match fresh createBitmap storage, including a transparent frame following an opaque one.
        target.eraseColor(Color.TRANSPARENT)
        val outputCanvas = checkNotNull(canvas)
        outputCanvas.density = source.density
        val saved = outputCanvas.save()
        try {
            // Match Bitmap.createBitmap's crop/quarter-turn Canvas operations, without scaling
            // the ROI directly into the model input (which can change edge filtering).
            outputCanvas.translate(-bounds.left, -bounds.top)
            outputCanvas.concat(matrix)
            outputCanvas.drawBitmap(source, sourceRect, destination, if (degrees == 0) null else rotationPaint)
        } finally { outputCanvas.restoreToCount(saved) }
        return target
    }

    private fun releaseStorage() {
        canvas?.setBitmap(null)
        canvas = null
        bitmap?.let { if (!it.isRecycled) it.recycle() }
        bitmap = null
    }

    override fun close() {
        if (closed) return
        closed = true
        releaseStorage()
    }
}
