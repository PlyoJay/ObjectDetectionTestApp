package com.samin.objectdetection.warning

import android.util.Log
import com.samin.objectdetection.detector.DetectionResult

object GotoroVisionRiskPolicy {
    const val DEFAULT_MIN_CONFIDENCE = 0.35f
    const val DEFAULT_MIN_AREA_RATIO = 0.005f

    fun evaluate(
        detection: DetectionResult,
        frameWidth: Int,
        frameHeight: Int,
        minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
        minAreaRatio: Float = DEFAULT_MIN_AREA_RATIO
    ): DetectionResult {
        val safeFrameWidth = frameWidth.coerceAtLeast(1).toFloat()
        val safeFrameHeight = frameHeight.coerceAtLeast(1).toFloat()
        val bboxWidth = (detection.right - detection.left).coerceAtLeast(0f)
        val bboxHeight = (detection.bottom - detection.top).coerceAtLeast(0f)
        val areaRatio = bboxWidth * bboxHeight / (safeFrameWidth * safeFrameHeight)
        val heightRatio = bboxHeight / safeFrameHeight
        val centerXRatio = ((detection.left + bboxWidth / 2f) / safeFrameWidth).coerceIn(0f, 1f)
        val centerYRatio = ((detection.top + bboxHeight / 2f) / safeFrameHeight).coerceIn(0f, 1f)
        val horizontalPosition = horizontalPosition(centerXRatio)
        val category = mapCategory(detection.label)
        val proximityLevel = estimateProximity(heightRatio, areaRatio)
        val isIgnored = detection.confidence < minConfidence || areaRatio < minAreaRatio
        val riskLevel = if (isIgnored) {
            RiskLevel.NONE
        } else {
            adjustRisk(
                baseRisk = baseRisk(proximityLevel),
                category = category,
                proximityLevel = proximityLevel,
                horizontalPosition = horizontalPosition
            )
        }

        return detection.copy(
            bboxWidth = bboxWidth,
            bboxHeight = bboxHeight,
            bboxAreaRatio = areaRatio,
            bboxHeightRatio = heightRatio,
            centerXRatio = centerXRatio,
            centerYRatio = centerYRatio,
            horizontalPosition = horizontalPosition,
            riskObjectCategory = category,
            proximityLevel = proximityLevel,
            riskLevel = riskLevel,
            isIgnored = isIgnored
        )
    }

    fun logDebug(detection: DetectionResult) {
        Log.d(
            TAG,
            "label=${detection.label}, conf=${format(detection.confidence)}, " +
                "areaRatio=${format(detection.bboxAreaRatio)}, " +
                "heightRatio=${format(detection.bboxHeightRatio)}, " +
                "proximityLevel=${detection.proximityLevel}, " +
                "riskLevel=${detection.riskLevel}, " +
                "horizontalPosition=${detection.horizontalPosition}, " +
                "category=${detection.riskObjectCategory}, ignored=${detection.isIgnored}"
        )
    }

    private fun estimateProximity(heightRatio: Float, areaRatio: Float): ProximityLevel {
        return when {
            heightRatio >= 0.35f || areaRatio >= 0.15f -> ProximityLevel.VERY_NEAR
            heightRatio >= 0.22f || areaRatio >= 0.07f -> ProximityLevel.NEAR
            heightRatio >= 0.12f || areaRatio >= 0.02f -> ProximityLevel.MID
            else -> ProximityLevel.FAR
        }
    }

    private fun mapCategory(label: String): RiskObjectCategory {
        return when (label.trim().lowercase()) {
            "person" -> RiskObjectCategory.HUMAN_FLOW
            "car",
            "truck",
            "bus",
            "motorcycle",
            "bicycle" -> RiskObjectCategory.VEHICLE_RISK
            "traffic light",
            "stop sign" -> RiskObjectCategory.TRAFFIC_CONTROL
            "bench",
            "fire hydrant" -> RiskObjectCategory.STATIC_OBSTACLE
            "chair" -> RiskObjectCategory.TEMPORARY_OBSTACLE
            else -> RiskObjectCategory.UNKNOWN
        }
    }

    private fun horizontalPosition(centerXRatio: Float): HorizontalPosition {
        return when {
            centerXRatio < LEFT_CENTER_BOUNDARY -> HorizontalPosition.LEFT
            centerXRatio > RIGHT_CENTER_BOUNDARY -> HorizontalPosition.RIGHT
            else -> HorizontalPosition.CENTER
        }
    }

    private fun baseRisk(proximityLevel: ProximityLevel): RiskLevel {
        return when (proximityLevel) {
            ProximityLevel.VERY_NEAR -> RiskLevel.CRITICAL
            ProximityLevel.NEAR -> RiskLevel.HIGH
            ProximityLevel.MID -> RiskLevel.MEDIUM
            ProximityLevel.FAR -> RiskLevel.LOW
        }
    }

    private fun adjustRisk(
        baseRisk: RiskLevel,
        category: RiskObjectCategory,
        proximityLevel: ProximityLevel,
        horizontalPosition: HorizontalPosition
    ): RiskLevel {
        if (category == RiskObjectCategory.VEHICLE_RISK && proximityLevel == ProximityLevel.VERY_NEAR) {
            return RiskLevel.CRITICAL
        }
        if (category == RiskObjectCategory.HUMAN_FLOW && proximityLevel == ProximityLevel.FAR) {
            return RiskLevel.NONE
        }
        if (category == RiskObjectCategory.STATIC_OBSTACLE && proximityLevel == ProximityLevel.FAR) {
            return RiskLevel.NONE
        }

        return if (horizontalPosition != HorizontalPosition.CENTER && proximityLevel != ProximityLevel.VERY_NEAR) {
            decrease(baseRisk)
        } else {
            baseRisk
        }
    }

    private fun decrease(riskLevel: RiskLevel): RiskLevel {
        return when (riskLevel) {
            RiskLevel.NONE,
            RiskLevel.LOW -> RiskLevel.NONE
            RiskLevel.MEDIUM -> RiskLevel.LOW
            RiskLevel.HIGH -> RiskLevel.MEDIUM
            RiskLevel.CRITICAL -> RiskLevel.HIGH
        }
    }

    private fun format(value: Float): String {
        return String.format(java.util.Locale.US, "%.3f", value)
    }

    private const val LEFT_CENTER_BOUNDARY = 1f / 3f
    private const val RIGHT_CENTER_BOUNDARY = 2f / 3f
    private const val TAG = "GotoroVisionRisk"
}
