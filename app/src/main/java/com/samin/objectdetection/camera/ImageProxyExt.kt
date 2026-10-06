package com.samin.objectdetection.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy

fun ImageProxy.toBitmapSafe(enableDiagnostics: Boolean = false): Bitmap? {
    return try {
        if (enableDiagnostics) {
            Log.d(
                "ImageProxyExt",
                "[CAMERA_INPUT] format=$format width=$width height=$height rotationDegrees=${imageInfo.rotationDegrees} " +
                    "cropRect=$cropRect proxyCropApplied=false " +
                    "planes=${planes.map { "pixelStride=${it.pixelStride},rowStride=${it.rowStride},bytes=${it.buffer.remaining()}" }} " +
                    "conversion=CameraX.toBitmap rotationThenPipelineRoiThenLetterbox"
            )
        }
        // CameraX 1.3.4 handles RGBA row stride and also YUV_420_888 explicitly.
        // The previous raw plane copy assumed RGBA and a fully padded last row.
        val bitmap = toBitmap()

        val rotationDegrees = imageInfo.rotationDegrees
        val result = if (rotationDegrees != 0) {
            bitmap.rotate(rotationDegrees.toFloat()).also { rotated ->
                if (rotated !== bitmap && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        } else {
            bitmap
        }
        if (enableDiagnostics) Log.d("ImageProxyExt", "[CAMERA_BITMAP] rotated=${result.width}x${result.height} config=${result.config}")
        result

    } catch (e: Exception) {
        Log.e("ImageProxyExt", "Camera bitmap conversion failed format=$format size=${width}x$height", e)
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
