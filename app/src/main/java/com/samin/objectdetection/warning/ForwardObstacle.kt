package com.samin.objectdetection.warning

import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.policy.ObjectCategory
import com.samin.objectdetection.policy.WarningPriority

data class ForwardObstacle(
    val detection: DetectionResult,
    val category: ObjectCategory,
    val priority: WarningPriority,
    val proximityLevel: ProximityLevel,
    val score: Float
)
