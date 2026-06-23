package com.samin.objectdetection.detector

import android.graphics.Bitmap
import android.graphics.Rect
import com.samin.objectdetection.motion.ApproachSpeedLevel
import com.samin.objectdetection.motion.MotionDirection
import com.samin.objectdetection.motion.ObjectMovementState
import com.samin.objectdetection.motion.UserObjectRelation
import com.samin.objectdetection.warning.HorizontalPosition
import com.samin.objectdetection.warning.ProximityLevel
import com.samin.objectdetection.warning.RiskLevel
import com.samin.objectdetection.warning.RiskObjectCategory
import com.samin.objectdetection.warning.WarningFeedback

data class DetectionResult(
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val frameTimestampMs: Long = System.currentTimeMillis(),
    val motionDirection: MotionDirection = MotionDirection.UNKNOWN,
    val approachSpeedLevel: ApproachSpeedLevel = ApproachSpeedLevel.UNKNOWN,
    val objectMovementState: ObjectMovementState = ObjectMovementState.UNKNOWN,
    val userObjectRelation: UserObjectRelation = UserObjectRelation.UNKNOWN,
    val bboxWidth: Float = 0f,
    val bboxHeight: Float = 0f,
    val bboxAreaRatio: Float = 0f,
    val bboxHeightRatio: Float = 0f,
    val centerXRatio: Float = 0f,
    val centerYRatio: Float = 0f,
    val horizontalPosition: HorizontalPosition = HorizontalPosition.CENTER,
    val riskObjectCategory: RiskObjectCategory = RiskObjectCategory.UNKNOWN,
    val proximityLevel: ProximityLevel = ProximityLevel.FAR,
    val riskLevel: RiskLevel = RiskLevel.NONE,
    val isIgnored: Boolean = false,
    val warningFeedback: WarningFeedback = WarningFeedback.NONE
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
