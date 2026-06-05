package com.samin.objectdetection.warning

import android.util.Log
import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.policy.WarningPriority
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
            val widthRatio = getBoxWidthRatio(detection, frameWidth)
            val heightRatio = getBoxHeightRatio(detection, frameHeight)
            val centerX = getCenterX(detection)
            val centerY = getCenterY(detection)
            val bottomProximity = detection.bottom / frameHeight
            val centerProximity = getCenterProximity(centerX, frameWidth)
            val registered = policy != null
            val enoughSize = areaRatio >= config.minBoxAreaRatio &&
                widthRatio >= config.minBoxWidthRatio &&
                heightRatio >= config.minBoxHeightRatio
            val notTopArea = centerY >= frameHeight * config.ignoreTopRatioForGuide

            if (policy != null && enoughSize && notTopArea) {
                val priorityScore = getPriorityScore(policy.priority)
                val score = priorityScore * 0.30f +
                    detection.confidence * 0.25f +
                    areaRatio * 0.20f +
                    bottomProximity * 0.15f +
                    centerProximity * 0.10f
                candidates.add(
                    ForwardObstacle(
                        detection = detection,
                        category = policy.category,
                        priority = policy.priority,
                        proximityLevel = estimateProximity(areaRatio, bottomProximity),
                        areaRatio = areaRatio,
                        bottomProximity = bottomProximity,
                        centerProximity = centerProximity,
                        score = score
                    )
                )
            } else {
                Log.d(
                    TAG,
                    "excluded label=${detection.label}, conf=${detection.confidence}, " +
                        "areaRatio=$areaRatio, widthRatio=$widthRatio, heightRatio=$heightRatio, " +
                        "centerY=$centerY, registered=$registered, " +
                        "sizeOk=$enoughSize, topOk=$notTopArea"
                )
            }
        }

        val selected = candidates
            .sortedWith(
                compareBy<ForwardObstacle> { priorityRank(it.priority) }
                    .thenBy { proximityRank(it.proximityLevel) }
                    .thenByDescending { it.score }
            )
            .take(config.maxGuideObjectCount)

        selected.forEach {
            Log.d(
                TAG,
                "selected label=${it.detection.label}, conf=${it.detection.confidence}, " +
                    "priority=${it.priority}, " +
                    "areaRatio=${it.areaRatio}, bottomProximity=${it.bottomProximity}, " +
                    "centerProximity=${it.centerProximity}, proximity=${it.proximityLevel}, score=${it.score}"
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

    private fun getBoxWidthRatio(detection: DetectionResult, frameWidth: Int): Float {
        return (detection.right - detection.left).coerceAtLeast(0f) / frameWidth.coerceAtLeast(1).toFloat()
    }

    private fun getBoxHeightRatio(detection: DetectionResult, frameHeight: Int): Float {
        return (detection.bottom - detection.top).coerceAtLeast(0f) / frameHeight.coerceAtLeast(1).toFloat()
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

    private fun getPriorityScore(priority: WarningPriority): Float {
        return when (priority) {
            WarningPriority.CRITICAL -> 1.0f
            WarningPriority.HIGH -> 0.8f
            WarningPriority.MEDIUM -> 0.55f
            WarningPriority.LOW -> 0.3f
            WarningPriority.NONE -> 0f
        }
    }

    private fun priorityRank(priority: WarningPriority): Int {
        return when (priority) {
            WarningPriority.CRITICAL -> 0
            WarningPriority.HIGH -> 1
            WarningPriority.MEDIUM -> 2
            WarningPriority.LOW -> 3
            WarningPriority.NONE -> 4
        }
    }

    private fun proximityRank(proximityLevel: ProximityLevel): Int {
        return when (proximityLevel) {
            ProximityLevel.VERY_NEAR -> 0
            ProximityLevel.NEAR -> 1
            ProximityLevel.MID -> 2
            ProximityLevel.FAR -> 3
        }
    }

    companion object {
        private const val TAG = "GuideFilter"
    }
}
