package com.samin.objectdetection.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectionMetricsCollectorPerformanceTest {
    @Test
    fun sessionSnapshotUsesOnlySessionSamples() {
        val collector = DetectionMetricsCollector()
        collector.recordFrameReceived()
        collector.startPerformanceSession(startedAtMs = 1_000L)
        collector.recordFrameReceived()
        collector.recordFrameAnalyzed()
        collector.recordInferenceSucceeded()
        collector.recordPerformance(10L, 20L, 5L, 40L, 60L, 8)
        collector.recordPerformance(30L, 40L, 15L, 80L, 100L, 10)

        val snapshot = collector.stopPerformanceSessionSnapshot(stoppedAtMs = 3_000L)

        assertEquals(2_000L, snapshot.durationMs)
        assertEquals(1L, snapshot.receivedFrames)
        assertEquals(1L, snapshot.processedFrames)
        assertEquals(20L, snapshot.preprocess.averageMs)
        assertEquals(30L, snapshot.preprocess.p95Ms)
        assertEquals(0.5, snapshot.inferenceFps, 0.0)
        assertEquals(1L, snapshot.successfulFrames)
    }

    @Test fun errorsDoNotBecomeSuccessfulPerformanceSamplesAndDurationUsesMonotonicClock() {
        val collector = DetectionMetricsCollector()
        collector.startPerformanceSession(1000, 1_000_000_000)
        repeat(3) { collector.recordFrameReceived(); collector.recordFrameAnalyzed() }
        collector.recordFrameFailed()
        repeat(2) { collector.recordInferenceSucceeded(); collector.recordPerformance(5, 20, 3, 30, -1, 1) }
        collector.recordOverlayAge(-1) // Incomparable camera timestamp: no fabricated latency.
        collector.recordOverlayAge(80)
        val snapshot = collector.stopPerformanceSessionSnapshot(500, 3_000_000_000)
        assertEquals(2000L, snapshot.durationMs)
        assertEquals(3L, snapshot.processedFrames); assertEquals(2L, snapshot.successfulFrames)
        assertEquals(1L, snapshot.failedFrames); assertEquals(1.5, snapshot.analyzerFps, 0.0)
        assertEquals(1.0, snapshot.inferenceFps, 0.0); assertEquals(20L, snapshot.inference.averageMs)
        assertEquals(1, snapshot.overlayLatencySamples); assertEquals(80L, snapshot.frameAgeAtOverlay.averageMs)
    }
}
