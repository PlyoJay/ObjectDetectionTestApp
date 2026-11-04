package com.samin.objectdetection.detector

/** Empty overrides preserve the global detector threshold and every existing class. */
data class YoloClassOptions(
    val enabled: Boolean = true,
    val confidenceThreshold: Float? = null
)

/** Compiled once at model initialization; candidate scanning does no map lookup/allocation. */
internal class YoloClassFilter(
    labels: List<String>,
    globalThreshold: Float,
    options: Map<String, YoloClassOptions>
) {
    private val enabled = BooleanArray(labels.size) { options[labels[it]]?.enabled ?: true }
    private val thresholds = FloatArray(labels.size) {
        options[labels[it]]?.confidenceThreshold ?: globalThreshold
    }

    init {
        require(thresholds.all { it.isFinite() && it in 0f..1f }) { "Class confidence must be in 0..1" }
    }

    fun threshold(classId: Int): Float = thresholds[classId]
    fun accepts(classId: Int, score: Float): Boolean =
        classId in enabled.indices && enabled[classId] && score.isFinite() && score >= thresholds[classId]
}
