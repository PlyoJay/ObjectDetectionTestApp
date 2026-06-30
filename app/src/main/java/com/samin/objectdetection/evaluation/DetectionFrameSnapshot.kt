package com.samin.objectdetection.evaluation

import android.graphics.Bitmap
import android.graphics.Rect
import com.samin.objectdetection.detector.DetectionResult

data class DetectionFrameSnapshot(
    val bitmap: Bitmap,
    val detections: List<DetectionResult>,
    val frameTimestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val roiApplied: Boolean,
    val roi: Rect?
)
