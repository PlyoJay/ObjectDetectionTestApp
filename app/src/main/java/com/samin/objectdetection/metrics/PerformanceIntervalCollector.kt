package com.samin.objectdetection.metrics

import com.samin.objectdetection.detector.InferenceExecutionInfo
import kotlin.math.ceil

data class ExactMetricStats(val samples: Long, val averageMs: Double?, val p95Ms: Long?)

data class PerformanceIntervalSummary(
    val index: Long?, // null = cumulative
    val startSeconds: Double,
    val endSeconds: Double,
    val successfulFrames: Long,
    val inference: ExactMetricStats,
    val preprocess: ExactMetricStats,
    val pipeline: ExactMetricStats,
    val inferenceFps: Double?,
    val lastSystemSample: SystemMetricsSample?,
    val totalProcessing: ExactMetricStats = ExactMetricStats(0, null, null),
    val execution: InferenceExecutionInfo? = null
)

/** Only small histogram updates/copies hold the lock. Sorting/formatting runs on a worker.
 * Counts all submitted successful frame data even when the bounded file writer drops a line.
 * Buckets use completion timestamps, not write time or the average of per-frame FPS values. */
class PerformanceIntervalCollector(private val startedElapsedNs: Long) {
    private val buckets = mutableMapOf<Long, Bucket>()
    private val cumulative = Bucket()

    @Synchronized fun record(frame: PerformanceFrameRecord, completedNs: Long) {
        if (frame.trace?.status?.let { it != "SUCCESS" } == true || completedNs < startedElapsedNs) return
        val bucket = buckets.getOrPut((completedNs - startedElapsedNs) / INTERVAL_NS) { Bucket() }
        bucket.record(frame)
        cumulative.record(frame)
    }

    @Synchronized fun recordSystem(sample: SystemMetricsSample) {
        if (sample.elapsedNs < startedElapsedNs) return
        buckets.getOrPut((sample.elapsedNs - startedElapsedNs) / INTERVAL_NS) { Bucket() }.system = sample
        cumulative.system = sample
    }

    @Synchronized fun recordExecution(execution: InferenceExecutionInfo?, completedNs: Long) {
        if (execution == null || completedNs < startedElapsedNs) return
        buckets.getOrPut((completedNs - startedElapsedNs) / INTERVAL_NS) { Bucket() }.execution = execution
        cumulative.execution = execution
    }

    fun interval(index: Long, stoppedElapsedNs: Long): PerformanceIntervalSummary {
        val copy = synchronized(this) { (buckets[index] ?: Bucket()).copy() }
        val end = minOf((index + 1) * INTERVAL_NS, (stoppedElapsedNs - startedElapsedNs).coerceAtLeast(0))
        return copy.summarize(index, index * INTERVAL_NS, end)
    }

    fun finalSummaries(stoppedElapsedNs: Long): List<PerformanceIntervalSummary> {
        val duration = (stoppedElapsedNs - startedElapsedNs).coerceAtLeast(0)
        val copies = synchronized(this) { cumulative.copy() to buckets.mapValues { it.value.copy() } }
        val result = mutableListOf(copies.first.summarize(null, 0, duration))
        // A completion exactly on a boundary belongs to the next [start,end) bucket.
        val last = maxOf(if (duration > 0) (duration - 1) / INTERVAL_NS else 0,
            copies.second.keys.maxOrNull() ?: 0)
        for (index in 0..last) {
            val end = minOf((index + 1) * INTERVAL_NS, duration)
            result += (copies.second[index] ?: Bucket()).summarize(index, index * INTERVAL_NS, end)
        }
        return result
    }

    private class Bucket {
        var frames = 0L
        val inference = Histogram()
        val preprocess = Histogram()
        val pipeline = Histogram()
        val totalProcessing = Histogram()
        var system: SystemMetricsSample? = null
        var execution: InferenceExecutionInfo? = null
        fun record(frame: PerformanceFrameRecord) {
            frames++
            inference.add(frame.inferenceMs); preprocess.add(frame.preprocessMs); pipeline.add(frame.pipelineMs)
            totalProcessing.add(frame.totalProcessingMs)
            frame.execution?.let { execution = it }
        }
        fun copy() = Bucket().also {
            it.frames = frames; it.inference.copyFrom(inference); it.preprocess.copyFrom(preprocess)
            it.pipeline.copyFrom(pipeline); it.system = system
            it.totalProcessing.copyFrom(totalProcessing)
            it.execution = execution
        }
        fun summarize(index: Long?, startNs: Long, endNs: Long): PerformanceIntervalSummary {
            val durationNs = (endNs - startNs).coerceAtLeast(0)
            return PerformanceIntervalSummary(index, startNs / 1e9, endNs / 1e9, frames,
                inference.stats(), preprocess.stats(), pipeline.stats(),
                if (durationNs > 0) frames * 1e9 / durationNs else null, system, totalProcessing.stats(), execution)
        }
    }

    private class Histogram {
        var count = 0L
        var sum = 0.0
        val frequencies = mutableMapOf<Long, Long>()
        fun add(ms: Long) {
            if (ms < 0) return // unknown is not zero latency
            count++; sum += ms
            frequencies[ms] = (frequencies[ms] ?: 0L) + 1
        }
        fun copyFrom(other: Histogram) {
            count = other.count; sum = other.sum; frequencies.putAll(other.frequencies)
        }
        fun stats(): ExactMetricStats {
            if (count == 0L) return ExactMetricStats(0, null, null)
            val rank = ceil(count * 0.95).toLong()
            var seen = 0L
            val p95 = frequencies.toSortedMap().entries.first { seen += it.value; seen >= rank }.key
            return ExactMetricStats(count, sum / count, p95)
        }
    }

    companion object { const val INTERVAL_NS = 30_000_000_000L }
}
