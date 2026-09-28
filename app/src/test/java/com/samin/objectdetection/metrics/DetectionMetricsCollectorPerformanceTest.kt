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
        collector.recordPerformance(10L, 20L, 5L, 40L, 60L, 8)
        collector.recordPerformance(30L, 40L, 15L, 80L, 100L, 10)

        val snapshot = collector.stopPerformanceSessionSnapshot(stoppedAtMs = 3_000L)

        assertEquals(2_000L, snapshot.durationMs)
        assertEquals(1L, snapshot.receivedFrames)
        assertEquals(1L, snapshot.processedFrames)
        assertEquals(20L, snapshot.preprocess.averageMs)
        assertEquals(30L, snapshot.preprocess.p95Ms)
        assertEquals(9, snapshot.averageFps)
    }
}
