package com.samin.objectdetection.mlkit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.samin.objectdetection.camera.FrameRoi
import java.util.concurrent.atomic.AtomicBoolean

/** One private ML Kit input. A lease must outlive the SDK Task, including coroutine cancellation. */
internal class MlKitInputBuffer : AutoCloseable {
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val sourceRect = Rect()
    private val destinationRect = RectF()
    private val scale = Matrix()
    private val paint = Paint().apply { isFilterBitmap = true }
    private var leased = false
    private var closed = false

    @Synchronized
    fun acquire(source: Bitmap, roi: FrameRoi): Lease {
        check(!closed) { "ML Kit input buffer is closed" }
        check(!leased) { "ML Kit input is still in use" }
        require(roi.left >= 0 && roi.top >= 0 && roi.right <= source.width &&
            roi.bottom <= source.height && roi.width > 0 && roi.height > 0)
        val target = bitmap ?: Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also {
            bitmap = it
            canvas = Canvas(it)
        }
        target.density = source.density
        target.setHasAlpha(source.hasAlpha())
        // Match the freshly allocated, transparent destination used by createScaledBitmap.
        target.eraseColor(Color.TRANSPARENT)
        // A real crop preserves legacy filtered sampling at ROI edges. Drawing a source subrect
        // directly into the scaled target can sample outside the ROI on Android's software Canvas.
        val cropped = if (roi.left == 0 && roi.top == 0 && roi.right == source.width && roi.bottom == source.height) {
            source
        } else {
            Bitmap.createBitmap(source, roi.left, roi.top, roi.width, roi.height)
        }
        try {
            sourceRect.set(0, 0, roi.width, roi.height)
            destinationRect.set(0f, 0f, roi.width.toFloat(), roi.height.toFloat())
            // Match createScaledBitmap's float Matrix and drawBitmap path exactly.
            scale.setScale(WIDTH.toFloat() / roi.width, HEIGHT.toFloat() / roi.height)
            val outputCanvas = canvas!!
            val saved = outputCanvas.save()
            try {
                outputCanvas.concat(scale)
                outputCanvas.drawBitmap(cropped, sourceRect, destinationRect, paint)
            } finally { outputCanvas.restoreToCount(saved) }
        } finally { if (cropped !== source) cropped.recycle() }
        leased = true
        return Lease(target, ::release)
    }

    @Synchronized
    private fun release() {
        leased = false
        if (closed) recycle()
    }

    @Synchronized
    override fun close() {
        closed = true
        if (!leased) recycle()
    }

    private fun recycle() {
        canvas?.setBitmap(null)
        canvas = null
        bitmap?.recycle()
        bitmap = null
    }

    class Lease internal constructor(val bitmap: Bitmap, private val release: () -> Unit) : AutoCloseable {
        private val released = AtomicBoolean(false)
        override fun close() {
            if (released.compareAndSet(false, true)) release()
        }
    }

    companion object {
        const val WIDTH = 640
        const val HEIGHT = 360
    }
}
