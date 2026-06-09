package com.samin.objectdetection.model

import android.graphics.RectF
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.motion.ApproachSpeedLevel
import com.samin.objectdetection.motion.MotionDirection
import com.samin.objectdetection.warning.DetectionCategoryMapper

data class DetectedObject(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
    val source: DetectionSource,
    val category: DetectionCategory,
    val priority: DetectionPriority,
    val timestampMs: Long,
    val motionDirection: MotionDirection,
    val approachSpeedLevel: ApproachSpeedLevel
)

fun DetectionResult.toDetectedObject(
    source: DetectionSource = DetectionSource.YOLO
): DetectedObject {
    val category = DetectionCategoryMapper.mapCategory(label)
    val priority = DetectionCategoryMapper.mapPriority(label)
    return DetectedObject(
        label = label,
        confidence = confidence,
        boundingBox = RectF(left, top, right, bottom),
        source = source,
        category = category,
        priority = priority,
        timestampMs = frameTimestampMs,
        motionDirection = motionDirection,
        approachSpeedLevel = approachSpeedLevel
    )
}
