package com.samin.objectdetection.metrics

import org.junit.Assert.*
import org.junit.Test

class PerformanceTestWindowTest {
    @Test fun defaultAndAllDurationsUseMonotonicDeadlines() {
        assertEquals(300_000L, PerformanceTestWindow(100).durationMs)
        for (minutes in listOf(3, 5, 10)) {
            val start = 7_000_000_000L
            val window = PerformanceTestWindow(start, minutes)
            assertEquals(minutes * 60_000L, window.remainingMs(start))
            assertEquals(1L, window.remainingMs(window.deadlineNs - 1))
            assertEquals(0L, window.remainingMs(window.deadlineNs))
            assertEquals(0L, window.remainingMs(window.deadlineNs + 50_000_000_000))
            assertFalse(window.accepts(start - 1))
            assertTrue(window.accepts(start))
            assertTrue(window.accepts(window.deadlineNs - 1))
            assertFalse(window.accepts(window.deadlineNs))
            assertEquals(PerformanceStopReason.MANUAL, window.stopReason(PerformanceStopReason.MANUAL, start))
            assertEquals(PerformanceStopReason.AUTO_DURATION, window.stopReason(PerformanceStopReason.MANUAL, window.deadlineNs))
            assertEquals(PerformanceStopReason.ERROR, window.stopReason(PerformanceStopReason.ERROR, window.deadlineNs))
        }
    }

    @Test(expected = IllegalArgumentException::class) fun unsupportedDurationIsRejected() {
        PerformanceTestWindow(0, 4)
    }
}
