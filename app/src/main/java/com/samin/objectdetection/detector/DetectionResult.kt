package com.samin.objectdetection.detector

import android.graphics.Bitmap
import android.graphics.Rect

data class DetectionResult(
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val frameTimestampMs: Long = System.currentTimeMillis()
)

interface ObjectDetector {
    fun detect(bitmap: Bitmap): List<DetectionResult>
    fun close()
}

fun DetectionResult.mapToOriginalFrame(
    roi: Rect,
    modelInputSize: Int = 640
): DetectionResult {
    val scaleX = roi.width() / modelInputSize.toFloat()
    val scaleY = roi.height() / modelInputSize.toFloat()

    return copy(
        left = left * scaleX + roi.left,
        top = top * scaleY + roi.top,
        right = right * scaleX + roi.left,
        bottom = bottom * scaleY + roi.top
    )
}
