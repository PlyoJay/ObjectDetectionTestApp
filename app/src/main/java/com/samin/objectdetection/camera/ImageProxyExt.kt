package com.samin.objectdetection.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.os.SystemClock
import androidx.core.os.trace
import androidx.camera.core.ImageProxy

class BitmapConversionException(cause: Throwable) : RuntimeException("Camera bitmap conversion failed", cause)

fun ImageProxy.toBitmapSafe(enableDiagnostics: Boolean = false,
    onTiming: ((cameraToBitmapNs: Long, rotationNs: Long) -> Unit)? = null): Bitmap? {
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
        val conversionStartNs = SystemClock.elapsedRealtimeNanos()
        val bitmap = trace("CameraX.toBitmap") { toBitmap() }
        val cameraToBitmapNs = SystemClock.elapsedRealtimeNanos() - conversionStartNs

        val rotationDegrees = imageInfo.rotationDegrees
        val rotationStartNs = SystemClock.elapsedRealtimeNanos()
        val result = if (rotationDegrees != 0) {
            trace("CameraX.rotate") { bitmap.rotate(rotationDegrees.toFloat()) }.also { rotated ->
                if (rotated !== bitmap && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        } else {
            bitmap
        }
        onTiming?.invoke(cameraToBitmapNs, if (rotationDegrees != 0)
            SystemClock.elapsedRealtimeNanos() - rotationStartNs else 0L)
        if (enableDiagnostics) Log.d("ImageProxyExt", "[CAMERA_BITMAP] rotated=${result.width}x${result.height} config=${result.config}")
        result

    } catch (e: Exception) {
        Log.e("ImageProxyExt", "Camera bitmap conversion failed format=$format size=${width}x$height", e)
        throw BitmapConversionException(e)
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
