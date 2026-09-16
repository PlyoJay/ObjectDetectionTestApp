package com.samin.objectdetection.policy

import android.util.Log
import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.camera.SizeFilterMode
import com.samin.objectdetection.detector.DetectionResult
import java.util.Locale

object SmallBoxFilterPolicy {

    fun filter(
        detections: List<DetectionResult>,
        frameWidth: Int,
        frameHeight: Int,
        config: DetectionConfig
    ): List<DetectionResult> {
        if (config.sizeFilterMode == SizeFilterMode.DISABLED) {
            if (config.enableDetectorDiagnostics) {
                Log.d(
                    TAG,
                    "mode=${config.sizeFilterMode} confidenceFilteredCount=${detections.size} " +
                        "sizeFilteredCount=${detections.size} sizeRejectedCount=0"
                )
            }
            return detections
        }

        val kept = mutableListOf<DetectionResult>()

        detections.forEach { detection ->
            val boxWidth = (detection.right - detection.left).coerceAtLeast(0f)
            val boxHeight = (detection.bottom - detection.top).coerceAtLeast(0f)
            val areaRatio = getBoxAreaRatio(boxWidth, boxHeight, frameWidth, frameHeight)
            val widthRatio = boxWidth / frameWidth.coerceAtLeast(1).toFloat()
            val heightRatio = boxHeight / frameHeight.coerceAtLeast(1).toFloat()
            val requiredAreaRatio = when (config.sizeFilterMode) {
                SizeFilterMode.RELAXED -> RELAXED_MIN_AREA_RATIO
                SizeFilterMode.NORMAL -> ObjectTuningPolicyRegistry.minAreaRatioFor(detection.label, config)
                SizeFilterMode.DISABLED -> 0f
            }
            val requiredWidthRatio = when (config.sizeFilterMode) {
                SizeFilterMode.RELAXED -> RELAXED_MIN_WIDTH_RATIO
                SizeFilterMode.NORMAL -> ObjectTuningPolicyRegistry.minWidthRatioFor(detection.label, config)
                SizeFilterMode.DISABLED -> 0f
            }
            val requiredHeightRatio = when (config.sizeFilterMode) {
                SizeFilterMode.RELAXED -> RELAXED_MIN_HEIGHT_RATIO
                SizeFilterMode.NORMAL -> ObjectTuningPolicyRegistry.minHeightRatioFor(detection.label, config)
                SizeFilterMode.DISABLED -> 0f
            }
            val keep = areaRatio >= requiredAreaRatio &&
                widthRatio >= requiredWidthRatio &&
                heightRatio >= requiredHeightRatio
            val reason = when {
                areaRatio < requiredAreaRatio -> "area below threshold"
                widthRatio < requiredWidthRatio -> "width below threshold"
                heightRatio < requiredHeightRatio -> "height below threshold"
                else -> "kept"
            }

            if (keep) {
                kept.add(detection)
            }
            if (config.enableDetectorDiagnostics) {
                Log.d(
                    TAG,
                    "small box filter label=${detection.label}, conf=${detection.confidence}, " +
                        "mode=${config.sizeFilterMode}, bboxWidth=$boxWidth, bboxHeight=$boxHeight, " +
                        "bboxArea=${boxWidth * boxHeight}, screenAreaRatio=$areaRatio, " +
                        "requiredAreaRatio=$requiredAreaRatio, widthRatio=$widthRatio, " +
                        "requiredWidthRatio=$requiredWidthRatio, heightRatio=$heightRatio, " +
                        "requiredHeightRatio=$requiredHeightRatio, " +
                        "keep=$keep, reason=$reason, box=${formatBox(detection)}"
                )
            }
        }

        if (config.enableDetectorDiagnostics) {
            Log.d(
                TAG,
                "mode=${config.sizeFilterMode} confidenceFilteredCount=${detections.size} " +
                    "sizeFilteredCount=${kept.size} sizeRejectedCount=${detections.size - kept.size}"
            )
        }
        return kept
    }

    fun minAreaRatioFor(label: String, config: DetectionConfig): Float {
        return ObjectTuningPolicyRegistry.minAreaRatioFor(label, config)
    }

    private fun getBoxAreaRatio(
        boxWidth: Float,
        boxHeight: Float,
        frameWidth: Int,
        frameHeight: Int
    ): Float {
        val imageArea = frameWidth.coerceAtLeast(1) * frameHeight.coerceAtLeast(1).toFloat()
        return boxWidth * boxHeight / imageArea
    }

    private fun formatBox(detection: DetectionResult): String {
        return String.format(
            Locale.US,
            "left=%.1f, top=%.1f, right=%.1f, bottom=%.1f",
            detection.left,
            detection.top,
            detection.right,
            detection.bottom
        )
    }

    private const val TAG = "DetectionFilter"
    private const val RELAXED_MIN_AREA_RATIO = 0.00005f
    private const val RELAXED_MIN_WIDTH_RATIO = 0.002f
    private const val RELAXED_MIN_HEIGHT_RATIO = 0.002f
}
