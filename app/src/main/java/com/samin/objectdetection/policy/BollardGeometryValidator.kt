package com.samin.objectdetection.policy

import com.samin.objectdetection.detector.DetectionResult

/** Constant-time rejection of only clearly invalid bollard boxes. */
object BollardGeometryValidator {
    fun isValid(detection: DetectionResult, frameWidth: Int, frameHeight: Int): Boolean {
        if (ObjectTuningPolicyRegistry.normalize(detection.label) != BOLLARD_LABEL) return true
        if (frameWidth <= 0 || frameHeight <= 0) return false
        if (!detection.left.isFinite() || !detection.top.isFinite() ||
            !detection.right.isFinite() || !detection.bottom.isFinite()
        ) return false
        if (detection.left < 0f || detection.top < 0f ||
            detection.right > frameWidth || detection.bottom > frameHeight
        ) return false

        val width = detection.right - detection.left
        val height = detection.bottom - detection.top
        if (width <= 0f || height <= 0f) return false
        val areaRatio = width * height / (frameWidth.toFloat() * frameHeight.toFloat())
        val aspectRatio = width / height
        return areaRatio >= MIN_AREA_RATIO && aspectRatio <= MAX_WIDTH_TO_HEIGHT_RATIO
    }

    private const val BOLLARD_LABEL = "bollard"
    private const val MIN_AREA_RATIO = 0.00001f
    private const val MAX_WIDTH_TO_HEIGHT_RATIO = 4f
}
