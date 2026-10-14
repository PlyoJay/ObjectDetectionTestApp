package com.samin.objectdetection.metrics

import org.junit.Assert.*
import org.junit.Test
import com.samin.objectdetection.detector.InferenceExecutionInfo

class PerformanceIntervalCollectorTest {
    @Test fun failedInferenceExecutionRemainsInFinalSummaryWithoutBecomingLatencySample() {
        val collector = PerformanceIntervalCollector(0)
        val ready = InferenceExecutionInfo(cpuThreads = 4, xnnpackRequested = true, lastInference = "SUCCESS")
        collector.record(frame().copy(execution = ready), 1_000_000_000L)
        collector.recordExecution(ready.copy(lastInference = "ERROR", inferenceErrors = 1), 31_000_000_000L)
        val summaries = collector.finalSummaries(32_000_000_000L)
        assertEquals(1L, summaries.first().inference.samples)
        assertEquals(1L, summaries.first().execution?.inferenceErrors)
        assertEquals("ERROR", summaries.last().execution?.lastInference)
        assertEquals(0L, summaries.last().inference.samples)
    }
    @Test fun totalProcessingIncludesConversionWithoutChangingLegacyPipelineStatistics() {
        val collector = PerformanceIntervalCollector(0)
        collector.record(frame(100).copy(totalProcessingMs = 150), 1_000_000_000L)
        collector.record(frame(200).copy(totalProcessingMs = 270), 2_000_000_000L)
        collector.record(frame(300), 3_000_000_000L) // missing value is excluded
        val all = collector.finalSummaries(4_000_000_000L).first()
        assertEquals(2L, all.totalProcessing.samples)
        assertEquals(210.0, all.totalProcessing.averageMs!!, 0.0)
        assertEquals(270L, all.totalProcessing.p95Ms)
        assertEquals(222.0, all.pipeline.averageMs!!, 0.0)
        assertEquals(322L, all.pipeline.p95Ms)
    }
    @Test fun completionBoundariesAndFinalPartialUseActualFrames() {
        val collector = PerformanceIntervalCollector(1_000_000_000L)
        collector.record(frame(100), 2_000_000_000L)
        collector.record(frame(200), 30_999_999_999L)
        collector.record(frame(600), 31_000_000_000L)
        collector.record(frame(800), 34_000_000_000L)
        val summaries = collector.finalSummaries(36_000_000_000L)
        val all = summaries[0]; val first = summaries[1]; val partial = summaries[2]
        assertEquals(4, all.successfulFrames.toInt())
        assertEquals(425.0, all.inference.averageMs!!, 0.0)
        assertEquals(800L, all.inference.p95Ms)
        assertEquals(150.0, first.inference.averageMs!!, 0.0)
        assertEquals(200L, first.inference.p95Ms)
        assertEquals(2.0 / 30, first.inferenceFps!!, 1e-9)
        assertEquals(700.0, partial.inference.averageMs!!, 0.0)
        assertEquals(0.4, partial.inferenceFps!!, 1e-9)
        assertEquals(35.0, partial.endSeconds, 0.0)
    }

    @Test fun emptyIntervalsAndUnknownLatenciesAreNotInventedZeroValues() {
        val collector = PerformanceIntervalCollector(0)
        collector.record(frame(900).copy(preprocessMs = -1), 65_000_000_000L)
        val summaries = collector.finalSummaries(70_000_000_000L)
        assertEquals(4, summaries.size)
        assertNull(summaries[1].inference.averageMs)
        assertEquals(0.0, summaries[1].inferenceFps!!, 0.0)
        assertEquals(0, summaries[3].preprocess.samples.toInt())
        assertNull(summaries[3].preprocess.averageMs)
        assertEquals(0.1, summaries[3].inferenceFps!!, 1e-9)
    }

    @Test fun failureAndPreSessionFramesAreExcludedAndDelayedSubmissionsAppearInFinalSummary() {
        val collector = PerformanceIntervalCollector(1_000_000_000L)
        collector.record(frame(123), 0)
        collector.record(frame(456).copy(trace = FrameTrace("s", 1, 0, 0, false, 0, 0, status = "ERROR")), 2_000_000_000L)
        assertEquals(0L, collector.interval(0, 31_000_000_000L).successfulFrames)
        collector.record(frame(321), 30_000_000_000L) // completion earlier than a preview; submission delayed
        assertEquals(1L, collector.finalSummaries(32_000_000_000L)[1].successfulFrames)
    }

    @Test fun exactNearestRankUsesRepeatedSamplesRatherThanAveragesOfWindows() {
        val collector = PerformanceIntervalCollector(0)
        repeat(19) { collector.record(frame(100), 1_000_000_000L) }
        collector.record(frame(1000), 31_000_000_000L)
        val all = collector.finalSummaries(32_000_000_000L).first()
        assertEquals(145.0, all.inference.averageMs!!, 0.0)
        assertEquals(100L, all.inference.p95Ms)
    }

    @Test fun samplesAreAttachedOnlyToTheirOwnInterval() {
        val collector = PerformanceIntervalCollector(0)
        collector.recordSystem(SystemMetricsSample(5_000_000_000L, 0, 2, null, null, 245.0, 5000.0, null, 0.1))
        val intervals = collector.finalSummaries(35_000_000_000L)
        assertEquals(2, intervals[1].lastSystemSample?.thermalStatus)
        assertNull(intervals[2].lastSystemSample)
    }

    companion object {
        fun frame(inference: Long = 100) = PerformanceFrameRecord(
            timestampMs = 0, bitmapConversionMs = 10, preprocessMs = 20, cropMs = 0,
            resizeMs = 1, inferenceMs = inference, outputParsingMs = 2, nmsMs = 0,
            postprocessMs = 2, pipelineMs = inference + 22, frameAgeAtInferenceStartMs = -1,
            frameAgeAtOverlayMs = -1, detectionCount = 0, visibleDetectionCount = 0, warningDetectionCount = 0, fps = 2)
    }
}
