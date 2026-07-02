package com.samin.objectdetection.metrics

import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.policy.ObjectTuningPolicyRegistry
import com.samin.objectdetection.warning.RiskLevel
import java.util.Locale

data class DetectionMetricBbox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class DetectionMetricRecord(
    val label: String,
    val confidence: Float,
    val bbox: DetectionMetricBbox,
    val timestampMs: Long
)

class DetectionMetricsCollector {
    var totalFrameCount: Long = 0L
        private set
    var analyzedFrameCount: Long = 0L
        private set
    var skippedFrameCount: Long = 0L
        private set
    var yoloDetectionCountBeforeFilter: Long = 0L
        private set
    var yoloDetectionCountAfterFilter: Long = 0L
        private set
    var filteredSmallBoxCount: Long = 0L
        private set
    var warningCount: Long = 0L
        private set
    var yoloInferenceTimeAverageMs: Long = 0L
        private set
    var yoloInferenceTimeMaxMs: Long = 0L
        private set
    var latestFps: Int = 0
        private set
    var averageFps: Int = 0
        private set
    var mlKitInferenceTimeAverageMs: Long = 0L
        private set
    var mlKitDetectionCount: Long = 0L
        private set

    private val warningCountByRiskLevel = RiskLevel.entries.associateWith { 0L }.toMutableMap()
    private val detectionCountByLabel = mutableMapOf<String, Long>()
    private val confidenceSumByLabel = mutableMapOf<String, Double>()
    private val rawCountByLabel = mutableMapOf<String, Long>()
    private val visibleCountByLabel = mutableMapOf<String, Long>()
    private val warningCountByLabel = mutableMapOf<String, Long>()
    private val ignoredCountByLabel = mutableMapOf<String, Long>()
    private val smallBoxFilteredCountByLabel = mutableMapOf<String, Long>()
    private val confidenceFilteredCountByLabel = mutableMapOf<String, Long>()
    private val records = ArrayDeque<DetectionMetricRecord>()
    private var yoloInferenceTimeSumMs = 0L
    private var yoloInferenceSampleCount = 0L
    private var fpsSum = 0L
    private var fpsSampleCount = 0L
    private var mlKitInferenceTimeSumMs = 0L
    private var mlKitInferenceSampleCount = 0L

    @Synchronized
    fun recordFrameReceived() {
        totalFrameCount++
    }

    @Synchronized
    fun recordFrameAnalyzed() {
        analyzedFrameCount++
    }

    @Synchronized
    fun recordFrameSkipped() {
        skippedFrameCount++
    }

    @Synchronized
    fun recordYoloDetections(
        beforeFilter: List<DetectionResult>,
        afterSmallBoxFilter: List<DetectionResult>,
        afterPolicyFilter: List<DetectionResult>,
        timestampMs: Long
    ) {
        yoloDetectionCountBeforeFilter += beforeFilter.size
        yoloDetectionCountAfterFilter += afterPolicyFilter.size
        filteredSmallBoxCount += (beforeFilter.size - afterSmallBoxFilter.size).coerceAtLeast(0)

        incrementCounts(rawCountByLabel, beforeFilter)
        incrementCounts(visibleCountByLabel, afterSmallBoxFilter)
        incrementCounts(warningCountByLabel, afterPolicyFilter)
        recordFilterBreakdown(beforeFilter, afterSmallBoxFilter, afterPolicyFilter)

        afterPolicyFilter.forEach { detection ->
            detectionCountByLabel[detection.label] = detectionCountByLabel.getOrDefault(detection.label, 0L) + 1L
            confidenceSumByLabel[detection.label] =
                confidenceSumByLabel.getOrDefault(detection.label, 0.0) + detection.confidence.toDouble()
            records.addLast(detection.toMetricRecord(timestampMs))
            if (records.size > MAX_RECORDS) {
                records.removeFirst()
            }
        }
    }

    @Synchronized
    fun recordWarning(riskLevel: RiskLevel) {
        warningCountByRiskLevel[riskLevel] = warningCountByRiskLevel.getOrDefault(riskLevel, 0L) + 1L
        if (riskLevel != RiskLevel.NONE) {
            warningCount++
        }
    }

    @Synchronized
    fun recordYoloInferenceTime(timeMs: Long) {
        yoloInferenceTimeSumMs += timeMs
        yoloInferenceSampleCount++
        yoloInferenceTimeAverageMs = yoloInferenceTimeSumMs / yoloInferenceSampleCount
        yoloInferenceTimeMaxMs = maxOf(yoloInferenceTimeMaxMs, timeMs)
    }

    @Synchronized
    fun recordFps(fps: Int) {
        latestFps = fps
        fpsSum += fps
        fpsSampleCount++
        averageFps = (fpsSum / fpsSampleCount).toInt()
    }

    @Synchronized
    fun recordMlKitResult(inferenceTimeMs: Long, detectionCount: Int) {
        mlKitInferenceTimeSumMs += inferenceTimeMs
        mlKitInferenceSampleCount++
        mlKitInferenceTimeAverageMs = mlKitInferenceTimeSumMs / mlKitInferenceSampleCount
        mlKitDetectionCount += detectionCount
    }

    // TODO: Connect TTS emitted/skipped counts when TtsWarningPlayer exposes playback metrics.

    @Synchronized
    fun buildSummary(): String {
        val topLabels = detectionCountByLabel.entries
            .sortedByDescending { it.value }
            .take(SUMMARY_LABEL_LIMIT)
            .joinToString(", ") { "${it.key} ${it.value}" }
            .ifBlank { "none" }

        val avgConfidence = detectionCountByLabel.entries
            .sortedByDescending { it.value }
            .take(SUMMARY_LABEL_LIMIT)
            .joinToString(", ") { (label, count) ->
                val average = confidenceSumByLabel.getOrDefault(label, 0.0) / count.coerceAtLeast(1)
                "$label ${String.format(Locale.US, "%.2f", average)}"
            }
            .ifBlank { "none" }

        return buildString {
            appendLine("[Metrics]")
            appendLine("Frames: $totalFrameCount / analyzed $analyzedFrameCount / skipped $skippedFrameCount")
            appendLine("YOLO avg: ${yoloInferenceTimeAverageMs}ms / max ${yoloInferenceTimeMaxMs}ms")
            appendLine(
                "Detect: before $yoloDetectionCountBeforeFilter / " +
                    "after $yoloDetectionCountAfterFilter / filtered $filteredSmallBoxCount"
            )
            appendLine(
                "Warnings: C=${warningCountByRiskLevel.getOrDefault(RiskLevel.CRITICAL, 0L)} " +
                    "H=${warningCountByRiskLevel.getOrDefault(RiskLevel.HIGH, 0L)} " +
                    "M=${warningCountByRiskLevel.getOrDefault(RiskLevel.MEDIUM, 0L)} " +
                    "L=${warningCountByRiskLevel.getOrDefault(RiskLevel.LOW, 0L)}"
            )
            appendLine("Top labels: $topLabels")
            appendLine("Avg conf: $avgConfidence")
            appendLine("Label raw: ${formatTopLabelCounts(rawCountByLabel)}")
            appendLine("Label visible: ${formatTopLabelCounts(visibleCountByLabel)}")
            appendLine("Label warning: ${formatTopLabelCounts(warningCountByLabel)}")
            appendLine("Label ignored: ${formatTopLabelCounts(ignoredCountByLabel)}")
            appendLine("Label small/conf: small=${formatTopLabelCounts(smallBoxFilteredCountByLabel)} / conf=${formatTopLabelCounts(confidenceFilteredCountByLabel)}")
            appendLine("FPS: latest $latestFps / avg $averageFps")
            appendLine("ML Kit avg: ${mlKitInferenceTimeAverageMs}ms / count $mlKitDetectionCount")
            appendLine("Accuracy: ground truth required")
            append("False positive: ground truth required")
        }
    }

    private fun incrementCounts(
        target: MutableMap<String, Long>,
        detections: List<DetectionResult>
    ) {
        detections.forEach { detection ->
            val label = normalizeLabel(detection.label)
            target[label] = target.getOrDefault(label, 0L) + 1L
        }
    }

    private fun recordFilterBreakdown(
        beforeFilter: List<DetectionResult>,
        afterSmallBoxFilter: List<DetectionResult>,
        afterPolicyFilter: List<DetectionResult>
    ) {
        val rawCounts = beforeFilter.groupingBy { normalizeLabel(it.label) }.eachCount()
        val visibleCounts = afterSmallBoxFilter.groupingBy { normalizeLabel(it.label) }.eachCount()
        val warningCounts = afterPolicyFilter.groupingBy { normalizeLabel(it.label) }.eachCount()

        rawCounts.forEach { (label, rawCount) ->
            val visibleCount = visibleCounts.getOrDefault(label, 0)
            val removedBySmallBox = (rawCount - visibleCount).coerceAtLeast(0)
            if (removedBySmallBox > 0) {
                smallBoxFilteredCountByLabel[label] =
                    smallBoxFilteredCountByLabel.getOrDefault(label, 0L) + removedBySmallBox
            }
        }

        visibleCounts.forEach { (label, visibleCount) ->
            val warningCount = warningCounts.getOrDefault(label, 0)
            val ignoredCount = (visibleCount - warningCount).coerceAtLeast(0)
            if (ignoredCount > 0) {
                ignoredCountByLabel[label] = ignoredCountByLabel.getOrDefault(label, 0L) + ignoredCount
            }
        }

        afterSmallBoxFilter.forEach { detection ->
            val tuningPolicy = ObjectTuningPolicyRegistry.get(detection.label)
            if (tuningPolicy != null && tuningPolicy.enableWarning && detection.confidence < tuningPolicy.minConfidence) {
                val label = normalizeLabel(detection.label)
                confidenceFilteredCountByLabel[label] =
                    confidenceFilteredCountByLabel.getOrDefault(label, 0L) + 1L
            }
        }
    }

    private fun formatTopLabelCounts(counts: Map<String, Long>): String {
        return counts.entries
            .sortedByDescending { it.value }
            .take(SUMMARY_LABEL_LIMIT)
            .joinToString(", ") { "${it.key} ${it.value}" }
            .ifBlank { "none" }
    }

    private fun normalizeLabel(label: String): String {
        return ObjectTuningPolicyRegistry.normalize(label)
    }

    private fun DetectionResult.toMetricRecord(timestampMs: Long): DetectionMetricRecord {
        return DetectionMetricRecord(
            label = label,
            confidence = confidence,
            bbox = DetectionMetricBbox(
                left = left,
                top = top,
                right = right,
                bottom = bottom
            ),
            timestampMs = timestampMs
        )
    }

    private companion object {
        private const val MAX_RECORDS = 500
        private const val SUMMARY_LABEL_LIMIT = 3
    }
}
