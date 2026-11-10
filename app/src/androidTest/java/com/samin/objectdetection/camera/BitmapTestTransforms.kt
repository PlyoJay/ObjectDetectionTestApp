package com.samin.objectdetection.camera

import android.graphics.Bitmap
import android.graphics.Matrix

/** Allocating reference transform for optimized rotation/ROI regression tests only. */
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
