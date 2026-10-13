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

data class PerformanceMetricStats(val averageMs: Long, val p95Ms: Long)

data class PerformanceSnapshot(
    val durationMs: Long,
    val receivedFrames: Long,
    val processedFrames: Long,
    val droppedFrames: Long,
    val averageFps: Int,
    val preprocess: PerformanceMetricStats,
    val inference: PerformanceMetricStats,
    val postprocess: PerformanceMetricStats,
    val pipeline: PerformanceMetricStats,
    val frameAgeAtOverlay: PerformanceMetricStats,
    val yoloDetections: Long,
    val visibleDetections: Long,
    val warnings: Long,
    val mlKitAverageMs: Long,
    val successfulFrames: Long = 0L,
    val failedFrames: Long = 0L,
    val analyzerFps: Double = 0.0,
    val inferenceFps: Double = 0.0,
    val overlayLatencySamples: Int = 0
)

class DetectionMetricsCollector {
    var successfulFrameCount: Long = 0L
        private set
    var failedFrameCount: Long = 0L
        private set
    private var rateStartedNs: Long? = null
    @Synchronized fun recordInferenceSucceeded(nowNs: Long = System.nanoTime()) {
        successfulFrameCount++
        val duration = nowNs - (rateStartedNs ?: nowNs)
        averageFps = if (duration > 0) kotlin.math.round(successfulFrameCount * 1_000_000_000.0 / duration).toInt() else 0
    }
    @Synchronized fun recordFrameFailed() { failedFrameCount++ }
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
    var yoloVisibleDetectionCount: Long = 0L
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
    private var mlKitInferenceTimeSumMs = 0L
    private var mlKitInferenceSampleCount = 0L
    private val performanceSamples = ArrayDeque<PerformanceSample>()
    private var performanceSampleCount = 0L
    private val sessionPerformanceSamples = ArrayDeque<PerformanceSample>()
    private var performanceSessionStartedAtMs = 0L
    private var performanceSessionStartedElapsedNs = 0L
    private var sessionSucceededBaseline = 0L
    private var sessionFailedBaseline = 0L
    private val overlaySamples = ArrayDeque<Long>()
    private val recentOverlayAges = ArrayDeque<Long>()
    private var performanceSessionActive = false
    private var sessionReceivedFrameBaseline = 0L
    private var sessionProcessedFrameBaseline = 0L
    private var sessionDroppedFrameBaseline = 0L
    private var sessionYoloDetectionBaseline = 0L
    private var sessionVisibleDetectionBaseline = 0L
    private var sessionWarningBaseline = 0L
    private var sessionMlKitTimeBaseline = 0L
    private var sessionMlKitSampleBaseline = 0L

    @Synchronized
    fun recordFrameReceived(nowNs: Long = System.nanoTime()) {
        if (rateStartedNs == null) rateStartedNs = nowNs
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
        yoloVisibleDetectionCount += afterSmallBoxFilter.size
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
    fun recordFps(fps: Int, nowNs: Long = System.nanoTime()) {
        latestFps = fps
        val duration = nowNs - (rateStartedNs ?: nowNs)
        averageFps = if (duration > 0) kotlin.math.round(successfulFrameCount * 1_000_000_000.0 / duration).toInt() else 0
    }

    @Synchronized
    fun recordMlKitResult(inferenceTimeMs: Long, detectionCount: Int) {
        mlKitInferenceTimeSumMs += inferenceTimeMs
        mlKitInferenceSampleCount++
        mlKitInferenceTimeAverageMs = mlKitInferenceTimeSumMs / mlKitInferenceSampleCount
        mlKitDetectionCount += detectionCount
    }

    @Synchronized
    fun recordPerformance(
        preprocessMs: Long,
        inferenceMs: Long,
        postprocessMs: Long,
        pipelineMs: Long,
        frameAgeAtOverlayMs: Long,
        fps: Int
    ): String? {
        val sample = PerformanceSample(
            preprocessMs,
            inferenceMs,
            postprocessMs,
            pipelineMs,
            frameAgeAtOverlayMs,
            fps
        )
        performanceSamples.addLast(sample)
        if (performanceSamples.size > PERF_SUMMARY_WINDOW) performanceSamples.removeFirst()
        if (performanceSessionActive) {
            sessionPerformanceSamples.addLast(sample)
            if (sessionPerformanceSamples.size > MAX_SESSION_PERFORMANCE_SAMPLES) sessionPerformanceSamples.removeFirst()
        }
        performanceSampleCount++
        if (performanceSampleCount % PERF_SUMMARY_WINDOW != 0L) return null

        return "[PerfSummary] frames=$performanceSampleCount processedFrames=$analyzedFrameCount " +
            "droppedFrames=$skippedFrameCount " +
            summarize("preprocess", performanceSamples.map { it.preprocessMs }) + " " +
            summarize("inference", performanceSamples.map { it.inferenceMs }) + " " +
            summarize("postprocess", performanceSamples.map { it.postprocessMs }) + " " +
            summarize("pipeline", performanceSamples.map { it.pipelineMs }) + " " +
            summarize("frameAgeAtOverlay", recentOverlayAges.toList())
    }

    @Synchronized
    fun startPerformanceSession(startedAtMs: Long = System.currentTimeMillis(), startedElapsedNs: Long = startedAtMs * 1_000_000L) {
        performanceSessionStartedAtMs = startedAtMs
        performanceSessionStartedElapsedNs = startedElapsedNs
        sessionSucceededBaseline = successfulFrameCount
        sessionFailedBaseline = failedFrameCount
        overlaySamples.clear()
        performanceSessionActive = true
        sessionPerformanceSamples.clear()
        sessionReceivedFrameBaseline = totalFrameCount
        sessionProcessedFrameBaseline = analyzedFrameCount
        sessionDroppedFrameBaseline = skippedFrameCount
        sessionYoloDetectionBaseline = yoloDetectionCountBeforeFilter
        sessionVisibleDetectionBaseline = yoloVisibleDetectionCount
        sessionWarningBaseline = warningCount
        sessionMlKitTimeBaseline = mlKitInferenceTimeSumMs
        sessionMlKitSampleBaseline = mlKitInferenceSampleCount
    }

    @Synchronized
    fun stopPerformanceSessionSnapshot(stoppedAtMs: Long = System.currentTimeMillis(), stoppedElapsedNs: Long = stoppedAtMs * 1_000_000L): PerformanceSnapshot {
        performanceSessionActive = false
        val samples = sessionPerformanceSamples.toList()
        val mlKitSamples = mlKitInferenceSampleCount - sessionMlKitSampleBaseline
        val durationMs = ((stoppedElapsedNs - performanceSessionStartedElapsedNs) / 1_000_000L).coerceAtLeast(0)
        val successes = successfulFrameCount - sessionSucceededBaseline
        val inferenceFps = if (durationMs > 0) successes * 1000.0 / durationMs else 0.0
        return PerformanceSnapshot(
            durationMs = durationMs,
            receivedFrames = totalFrameCount - sessionReceivedFrameBaseline,
            processedFrames = analyzedFrameCount - sessionProcessedFrameBaseline,
            droppedFrames = skippedFrameCount - sessionDroppedFrameBaseline,
            averageFps = kotlin.math.round(inferenceFps).toInt(),
            preprocess = stats(samples.map { it.preprocessMs }),
            inference = stats(samples.map { it.inferenceMs }),
            postprocess = stats(samples.map { it.postprocessMs }),
            pipeline = stats(samples.map { it.pipelineMs }),
            frameAgeAtOverlay = stats(overlaySamples.toList()),
            yoloDetections = yoloDetectionCountBeforeFilter - sessionYoloDetectionBaseline,
            visibleDetections = yoloVisibleDetectionCount - sessionVisibleDetectionBaseline,
            warnings = warningCount - sessionWarningBaseline,
            mlKitAverageMs = if (mlKitSamples > 0L) {
                (mlKitInferenceTimeSumMs - sessionMlKitTimeBaseline) / mlKitSamples
            } else {
                0L
            },
            successfulFrames = successes,
            failedFrames = failedFrameCount - sessionFailedBaseline,
            analyzerFps = if (durationMs > 0) (totalFrameCount - sessionReceivedFrameBaseline) * 1000.0 / durationMs else 0.0,
            inferenceFps = inferenceFps,
            overlayLatencySamples = overlaySamples.size
        )
    }

    @Synchronized fun recordOverlayAge(ageMs: Long) {
        if (ageMs < 0) return
        recentOverlayAges.addLast(ageMs)
        if (recentOverlayAges.size > PERF_SUMMARY_WINDOW) recentOverlayAges.removeFirst()
        if (performanceSessionActive) {
            overlaySamples.addLast(ageMs)
            if (overlaySamples.size > MAX_SESSION_PERFORMANCE_SAMPLES) overlaySamples.removeFirst()
        }
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
            appendLine("Frames: $totalFrameCount / analyzed $analyzedFrameCount / succeeded $successfulFrameCount / failed $failedFrameCount / skipped $skippedFrameCount")
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

    private fun summarize(name: String, values: List<Long>): String {
        if (values.isEmpty()) return "avg${name.replaceFirstChar(Char::uppercase)}=-1ms p95${name.replaceFirstChar(Char::uppercase)}=-1ms"
        val sorted = values.sorted()
        val average = values.sum() / values.size
        val p95Index = ((sorted.size * 95 + 99) / 100 - 1).coerceAtLeast(0)
        val title = name.replaceFirstChar(Char::uppercase)
        return "avg$title=${average}ms p95$title=${sorted[p95Index]}ms"
    }

    private fun stats(values: List<Long>): PerformanceMetricStats {
        if (values.isEmpty()) return PerformanceMetricStats(0L, 0L)
        val sorted = values.sorted()
        return PerformanceMetricStats(
            averageMs = values.sum() / values.size,
            p95Ms = sorted[((sorted.size * 95 + 99) / 100 - 1).coerceAtLeast(0)]
        )
    }

    private fun List<Long>.averageLong(): Long = if (isEmpty()) 0L else sum() / size

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
        private const val PERF_SUMMARY_WINDOW = 100
        private const val MAX_SESSION_PERFORMANCE_SAMPLES = 100_000
    }

    private data class PerformanceSample(
        val preprocessMs: Long,
        val inferenceMs: Long,
        val postprocessMs: Long,
        val pipelineMs: Long,
        val frameAgeAtOverlayMs: Long,
        val fps: Int
    )
}
