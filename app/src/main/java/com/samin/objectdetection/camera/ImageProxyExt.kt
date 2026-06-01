package com.samin.objectdetection.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy

fun ImageProxy.toBitmapSafe(): Bitmap? {
    return try {
        val plane = planes[0]
        val buffer = plane.buffer

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        Log.d(
            "ImageProxyExt",
            "format=$format, width=$width, height=$height, pixelStride=$pixelStride, rowStride=$rowStride, buffer=${buffer.remaining()}"
        )

        val rowPadding = rowStride - pixelStride * width

        val bitmapWithPadding = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )

        buffer.rewind()
        bitmapWithPadding.copyPixelsFromBuffer(buffer)

        val bitmap = Bitmap.createBitmap(
            bitmapWithPadding,
            0,
            0,
            width,
            height
        )

        val rotationDegrees = imageInfo.rotationDegrees
        Log.d("ImageProxyExt", "rotationDegrees=$rotationDegrees")

        if (rotationDegrees != 0) {
            bitmap.rotate(rotationDegrees.toFloat())
        } else {
            bitmap
        }

    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun Bitmap.rotate(degrees: Float): Bitmap {
    if (degrees == 0f) return this

    val matrix = Matrix().apply {
        postRotate(degrees)
    }

    return Bitmap.createBitmap(
        this,
        0,
        0,
        this.width,
        this.height,
        matrix,
        true
    )
}