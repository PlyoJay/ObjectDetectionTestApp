package com.samin.objectdetection.policy

import com.samin.objectdetection.detector.DetectionResult

class AdaptiveTemporalValidator(
    private val immediateConfidence: Float,
    private val confirmationConfidence: Float,
    private val matchIouThreshold: Float = 0.30f
) {
    private var pendingBollards: List<DetectionResult> = emptyList()

    fun filter(detections: List<DetectionResult>): List<DetectionResult> {
        val accepted = ArrayList<DetectionResult>(detections.size)
        val nextPending = ArrayList<DetectionResult>()
        detections.forEach { detection ->
            if (ObjectTuningPolicyRegistry.normalize(detection.label) != BOLLARD_LABEL ||
                detection.confidence >= immediateConfidence ||
                detection.confidence < confirmationConfidence
            ) {
                accepted += detection
            } else if (pendingBollards.any { iou(it, detection) >= matchIouThreshold }) {
                accepted += detection
                nextPending += detection
            } else {
                nextPending += detection
            }
        }
        pendingBollards = nextPending
        return accepted
    }

    fun reset() {
        pendingBollards = emptyList()
    }

    companion object {
        private const val BOLLARD_LABEL = "bollard"

        fun iou(a: DetectionResult, b: DetectionResult): Float {
            val left = maxOf(a.left, b.left)
            val top = maxOf(a.top, b.top)
            val right = minOf(a.right, b.right)
            val bottom = minOf(a.bottom, b.bottom)
            val intersection = maxOf(0f, right - left) * maxOf(0f, bottom - top)
            val areaA = maxOf(0f, a.right - a.left) * maxOf(0f, a.bottom - a.top)
            val areaB = maxOf(0f, b.right - b.left) * maxOf(0f, b.bottom - b.top)
            return intersection / (areaA + areaB - intersection + 1e-6f)
        }
    }
}
