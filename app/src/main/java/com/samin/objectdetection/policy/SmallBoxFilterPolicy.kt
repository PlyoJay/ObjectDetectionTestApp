package com.samin.objectdetection.policy

import android.util.Log
import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.detector.DetectionResult
import java.util.Locale

object SmallBoxFilterPolicy {

    fun filter(
        detections: List<DetectionResult>,
        frameWidth: Int,
        frameHeight: Int,
        config: DetectionConfig
    ): List<DetectionResult> {
        val kept = mutableListOf<DetectionResult>()

        detections.forEach { detection ->
            val boxWidth = (detection.right - detection.left).coerceAtLeast(0f)
            val boxHeight = (detection.bottom - detection.top).coerceAtLeast(0f)
            val areaRatio = getBoxAreaRatio(boxWidth, boxHeight, frameWidth, frameHeight)
            val widthRatio = boxWidth / frameWidth.coerceAtLeast(1).toFloat()
            val heightRatio = boxHeight / frameHeight.coerceAtLeast(1).toFloat()
            val minAreaRatio = minAreaRatioFor(detection.label, config)
            val keep = areaRatio >= minAreaRatio &&
                widthRatio >= config.minBoxWidthRatio &&
                heightRatio >= config.minBoxHeightRatio

            if (keep) {
                kept.add(detection)
            } else {
                Log.d(
                    TAG,
                    "skip small box label=${detection.label}, conf=${detection.confidence}, " +
                        "areaRatio=$areaRatio, widthRatio=$widthRatio, heightRatio=$heightRatio, " +
                        "minAreaRatio=$minAreaRatio, minWidthRatio=${config.minBoxWidthRatio}, " +
                        "minHeightRatio=${config.minBoxHeightRatio}, box=${formatBox(detection)}"
                )
            }
        }

        Log.d(TAG, "before=${detections.size}, after=${kept.size}")
        return kept
    }

    fun minAreaRatioFor(label: String, config: DetectionConfig): Float {
        return when (OverlayObjectFilter.normalize(label)) {
            "traffic light",
            "stop sign" -> 0.002f
            "person" -> 0.006f
            "car",
            "bus",
            "truck",
            "motorcycle",
            "bicycle" -> 0.008f
            "bench",
            "fire hydrant" -> 0.01f
            else -> config.minBoxAreaRatio
        }
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
}
