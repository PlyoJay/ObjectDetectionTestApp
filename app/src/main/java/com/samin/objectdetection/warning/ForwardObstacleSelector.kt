package com.samin.objectdetection.warning

import android.util.Log
import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.policy.YoloDefaultPolicyRegistry
import kotlin.math.abs

class ForwardObstacleSelector {

    fun select(
        detections: List<DetectionResult>,
        frameWidth: Int,
        frameHeight: Int,
        config: DetectionConfig
    ): List<ForwardObstacle> {
        val candidates = mutableListOf<ForwardObstacle>()

        detections.forEach { detection ->
            val policy = YoloDefaultPolicyRegistry.get(detection.label)
            val areaRatio = getBoxAreaRatio(detection, frameWidth, frameHeight)
            val centerX = getCenterX(detection)
            val centerY = getCenterY(detection)
            val bottomProximity = detection.bottom / frameHeight
            val centerProximity = getCenterProximity(centerX, frameWidth)
            val registered = policy != null
            val enoughSize = areaRatio >= config.minBoxAreaRatio
            val notTopArea = centerY >= frameHeight * config.ignoreTopRatioForGuide

            if (policy != null && enoughSize && notTopArea) {
                val score = detection.confidence * 0.35f +
                    areaRatio * 0.25f +
                    bottomProximity * 0.25f +
                    centerProximity * 0.15f
                candidates.add(
                    ForwardObstacle(
                        detection = detection,
                        category = policy.category,
                        priority = policy.priority,
                        proximityLevel = estimateProximity(areaRatio, bottomProximity),
                        score = score
                    )
                )
            } else {
                Log.d(
                    TAG,
                    "excluded label=${detection.label}, conf=${detection.confidence}, " +
                        "areaRatio=$areaRatio, centerY=$centerY, registered=$registered, " +
                        "sizeOk=$enoughSize, topOk=$notTopArea"
                )
            }
        }

        val selected = candidates
            .sortedByDescending { it.score }
            .take(config.maxGuideObjectCount)

        selected.forEach {
            Log.d(
                TAG,
                "selected label=${it.detection.label}, conf=${it.detection.confidence}, " +
                    "proximity=${it.proximityLevel}, score=${it.score}"
            )
        }
        Log.d(TAG, "all=${detections.size}, candidates=${candidates.size}, selected=${selected.size}")

        return selected
    }

    private fun getBoxAreaRatio(
        detection: DetectionResult,
        frameWidth: Int,
        frameHeight: Int
    ): Float {
        val boxWidth = (detection.right - detection.left).coerceAtLeast(0f)
        val boxHeight = (detection.bottom - detection.top).coerceAtLeast(0f)
        return boxWidth * boxHeight / (frameWidth * frameHeight.toFloat())
    }

    private fun getCenterX(detection: DetectionResult): Float {
        return detection.left + (detection.right - detection.left) / 2f
    }

    private fun getCenterY(detection: DetectionResult): Float {
        return detection.top + (detection.bottom - detection.top) / 2f
    }

    private fun getCenterProximity(centerX: Float, frameWidth: Int): Float {
        val frameCenterX = frameWidth / 2f
        return (1f - abs(centerX - frameCenterX) / frameCenterX).coerceIn(0f, 1f)
    }

    private fun estimateProximity(areaRatio: Float, bottomProximity: Float): ProximityLevel {
        return when {
            areaRatio >= 0.12f || bottomProximity >= 0.85f -> ProximityLevel.VERY_NEAR
            areaRatio >= 0.06f || bottomProximity >= 0.70f -> ProximityLevel.NEAR
            areaRatio >= 0.025f || bottomProximity >= 0.55f -> ProximityLevel.MID
            else -> ProximityLevel.FAR
        }
    }

    companion object {
        private const val TAG = "GuideFilter"
    }
}
