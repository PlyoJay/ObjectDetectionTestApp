package com.samin.objectdetection.policy

import com.samin.objectdetection.warning.ObjectPriority
import com.samin.objectdetection.warning.RiskObjectCategory

data class ObjectTuningPolicy(
    val label: String,
    val minConfidence: Float,
    val minAreaRatio: Float,
    val minWidthRatio: Float? = null,
    val minHeightRatio: Float? = null,
    val enableOverlay: Boolean,
    val enableWarning: Boolean,
    val enableVoice: Boolean,
    val priority: ObjectPriority,
    val category: RiskObjectCategory,
    val note: String
)
