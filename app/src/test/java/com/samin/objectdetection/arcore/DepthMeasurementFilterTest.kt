package com.samin.objectdetection.arcore

import org.junit.Assert.*
import org.junit.Test

class DepthMeasurementFilterTest {
    private fun sample(time: Long, meters: Double? = 2.0, tracking: String = "TRACKING") = DepthSample(
        time, tracking, 160, 120, 80, 60, meters, 25, time, "UPDATED", 1_700_000_000_000)

    @Test fun movingMedianIsOptionalAndNeverOverwritesRawReadings() {
        val filter = DepthMeasurementFilter()
        var latest: DepthMeasurement? = null
        for ((i, meters) in listOf(2.0, 2.01, 9.0, 1.99, 2.0).withIndex()) {
            latest = filter.update(sample((i + 1) * 100_000_000L, meters), (i + 1) * 100_000_000L, true, null)
            assertEquals(meters, latest.sample.rawDepthMeters!!, 0.0)
        }
        assertEquals(2.0, latest!!.filteredDepthMeters!!, 0.0)
        assertEquals(5, latest.recentValidCount)
        assertEquals(3.4, latest.recentMeanMeters!!, .001)
        val rawDisplay = filter.update(sample(600_000_000, 8.0), 600_000_000, false, null)
        assertEquals(8.0, rawDisplay.filteredDepthMeters!!, 0.0)
    }

    @Test fun duplicateDepthCannotExtendFreshnessOrBiasSampleCountAndRawStillLogs() {
        val filter = DepthMeasurementFilter()
        val first = filter.update(sample(100_000_000), 100_000_000, true, null)
        assertTrue(first.isFresh(1_099_999_999))
        assertFalse(first.isFresh(1_100_000_000))
        val held = filter.update(sample(100_000_000).copy(frameTimestampNs = 900_000_000), 900_000_000, true, null)
        assertEquals("DUPLICATE_DEPTH", held.sample.depthStatus)
        assertEquals(1, held.recentValidCount)
        assertEquals(2.0, held.sample.rawDepthMeters!!, 0.0)
        val expired = filter.update(sample(100_000_000).copy(frameTimestampNs = 1_200_000_000), 1_200_000_000, true, null)
        assertNull(expired.filteredDepthMeters)
        assertEquals("STALE_DEPTH", expired.sample.depthStatus)
    }

    @Test fun trackingLossClearsDistanceAndHistoryImmediatelyAndRecoversOnNewTracking() {
        val filter = DepthMeasurementFilter()
        filter.update(sample(100), 100, true, null)
        val paused = filter.update(sample(200, null, "PAUSED"), 200, true, null)
        assertNull(paused.filteredDepthMeters); assertEquals(0, paused.recentValidCount)
        val recovered = filter.update(sample(300, 5.0), 300, true, null)
        assertEquals(5.0, recovered.filteredDepthMeters!!, 0.0)
        assertEquals(1, recovered.recentValidCount)
    }

    @Test fun noDataAndInvalidValuesNeverInventDistanceOrKeepItForever() {
        for (meters in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(DepthMeasurementFilter().update(sample(100, meters), 100, true, null).filteredDepthMeters)
        }
        val filter = DepthMeasurementFilter()
        filter.update(sample(100_000_000), 100_000_000, true, null)
        val waiting = filter.update(sample(1_100_000_000, null).copy(depthStatus = "WAITING_FOR_DEPTH"), 1_100_000_000, true, null)
        assertNull(waiting.filteredDepthMeters); assertNull(waiting.recentMeanMeters)
        val fresh = filter.update(sample(1_300_000_000, 5.0), 1_300_000_000, true, null)
        assertEquals(1, fresh.recentValidCount)
        assertEquals(5.0, fresh.filteredDepthMeters!!, 0.0)
    }

    @Test fun outOfOrderFutureAndStaleImagesCannotBecomeNewMeasurements() {
        val filter = DepthMeasurementFilter()
        filter.update(sample(500_000_000), 500_000_000, true, null)
        val old = filter.update(sample(400_000_000, 9.0).copy(frameTimestampNs = 600_000_000), 600_000_000, true, null)
        assertEquals("DUPLICATE_DEPTH", old.sample.depthStatus)
        assertEquals(2.0, old.filteredDepthMeters!!, 0.0)
        val future = filter.update(sample(700_000_000).copy(depthTimestampNs = 800_000_000), 700_000_000, true, null)
        assertEquals("STALE_DEPTH", future.sample.depthStatus)
    }

    @Test fun aNewlyAcquiredEarlierDepthImageKeepsItsExistingAge() {
        val value = DepthMeasurementFilter().update(sample(1_000_000_000).copy(depthTimestampNs = 200_000_000),
            2_000_000_000, true, null)
        assertEquals(1_200_000_000L, value.lastValidElapsedNs)
        assertTrue(value.isFresh(2_199_999_999))
        assertFalse(value.isFresh(2_200_000_000))
    }

    @Test fun errorsRequireExplicitFinitePositiveGroundTruthAndUseRawForwardZ() {
        val filter = DepthMeasurementFilter()
        for (truth in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val result = DepthMeasurementFilter().update(sample(100, 2.35), 100, true, truth)
            assertNull(result.absoluteErrorMeters); assertNull(result.relativeErrorPercent)
        }
        val value = filter.update(sample(100, 2.35), 100, true, 2.0)
        assertEquals(.35, value.absoluteErrorMeters!!, 1e-9)
        assertEquals(17.5, value.relativeErrorPercent!!, 1e-9)
        val next = filter.update(sample(200, 10.0), 200, true, 2.0)
        assertEquals(8.0, next.absoluteErrorMeters!!, 1e-9) // Does not use filtered 6.175.
        val duplicate = filter.update(sample(200, 10.0), 201, true, 2.0)
        assertNull(duplicate.absoluteErrorMeters)
    }
}
