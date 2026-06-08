package com.samin.objectdetection.warning

import com.samin.objectdetection.model.DetectedObject
import com.samin.objectdetection.model.DetectionPriority

class WarningSelector(
    private val warningDecisionMaker: WarningDecisionMaker = WarningDecisionMaker()
) {

    fun select(detectedObjects: List<DetectedObject>): DetectedObject? {
        return detectedObjects
            .filter { warningDecisionMaker.shouldWarn(it) }
            .maxWithOrNull(
                compareBy<DetectedObject> { priorityScore(it.priority) }
                    .thenBy { it.confidence }
            )
    }

    private fun priorityScore(priority: DetectionPriority): Int {
        return when (priority) {
            DetectionPriority.HIGH -> 3
            DetectionPriority.MEDIUM -> 2
            DetectionPriority.LOW -> 1
            DetectionPriority.IGNORE -> 0
        }
    }
}
