package com.samin.objectdetection.arcore.yolo

import com.samin.objectdetection.detector.DetectionResult
import org.junit.Assert.*
import org.junit.Test

class ArObjectDepthSmootherTest {
    private fun obj(x: Float, meters: Float?, label: String = "person") = ArYoloObject(
        DetectionResult(label, .9f, x, 10f, x + 20f, 40f), ImageBox(0f, 0f, 1f, 1f),
        ArObjectDepth(meters, status = if (meters != null) "BOTTOM_CENTER" else "DEPTH_NOT_READY"))

    @Test fun sameLabelObjectsMatchByBoxOverlapEvenWhenDetectionOrderChanges() {
        val smoother = ArObjectDepthSmoother()
        smoother.update(listOf(obj(10f, 1f), obj(60f, 9f)), 1, 1)
        val next = smoother.update(listOf(obj(60f, 10f), obj(10f, 1.2f)), 2, 2)
        assertEquals(9.5f, next[0].depth.meters!!, 0f); assertEquals(1.1f, next[1].depth.meters!!, .0001f)
        assertEquals(1.2f, next[1].depth.rawMeters!!, 0f)
    }
    @Test fun absentDepthNeverBorrowsHistoryAndNextValidSampleStartsAgain() {
        val smoother = ArObjectDepthSmoother()
        smoother.update(listOf(obj(10f, 1f)), 1, 1)
        assertNull(smoother.update(listOf(obj(10f, null)), null, 2).single().depth.meters)
        assertEquals(5f, smoother.update(listOf(obj(10f, 5f)), 3, 3).single().depth.meters!!, 0f)
    }
    @Test fun duplicateDepthDoesNotBiasMedianAndOldTracksOrDifferentClassesDoNotCarryOver() {
        val smoother = ArObjectDepthSmoother()
        smoother.update(listOf(obj(10f, 1f)), 1, 1)
        assertNull(smoother.update(listOf(obj(10f, 9f)), 1, 2).single().depth.meters)
        assertEquals(9f, smoother.update(listOf(obj(10f, 9f, "cat")), 2, 3).single().depth.meters!!, 0f)
        assertEquals(5f, smoother.update(listOf(obj(10f, 5f, "cat")), 3, 1_000_000_003).single().depth.meters!!, 0f)
    }
    @Test fun medianHasAtMostFiveSamplesAndResetDoesNotResurrectOldDistance() {
        val smoother = ArObjectDepthSmoother()
        var result = obj(10f, 1f)
        val stationary = listOf(10f, 10.02f, 9.98f, 10.01f, 9.99f, 10.02f, 9.98f)
        stationary.forEachIndexed { i, value -> result = smoother.update(listOf(obj(10f, value)), i.toLong() + 1, i.toLong() + 1).single() }
        assertEquals(9.99f, result.depth.meters!!, .0001f); assertEquals(9.98f, result.depth.rawMeters!!, 0f)
        smoother.reset()
        assertEquals(20f, smoother.update(listOf(obj(10f, 20f)), 11, 11).single().depth.meters!!, 0f)
    }
    @Test fun abruptDepthChangeAndInputSourceSwitchStartNewHistoryWithoutDisplayLag() {
        val smoother = ArObjectDepthSmoother()
        repeat(5) { smoother.update(listOf(obj(10f, 2f)), it.toLong() + 1, it.toLong() + 1) }
        assertNull(smoother.update(listOf(obj(10f, 4f)), 6, 6).single().depth.meters)
        assertEquals(4f, smoother.update(listOf(obj(10f, 4f)), 7, 7).single().depth.meters!!, 0f)
        val raw = obj(10f, 4.2f).let { it.copy(depth = it.depth.copy(method = com.samin.objectdetection.distance.DepthMethod.RAW, timestampNs = 7)) }
        assertEquals(4.2f, smoother.update(listOf(raw), null, 7).single().depth.meters!!, 0f)
    }
    @Test fun ambiguousSameClassOverlappingBoxesDoNotBorrowEitherTrackHistory() {
        val smoother = ArObjectDepthSmoother()
        smoother.update(listOf(obj(10f, 2f), obj(20f, 4f)), 1, 1)
        val result = smoother.update(listOf(obj(14f, 2.2f), obj(16f, 4.2f)), 2, 2)
        assertEquals(2.2f, result[0].depth.meters!!, 0f); assertEquals(4.2f, result[1].depth.meters!!, 0f)
    }
    @Test fun isolatedLargeSpikeIsRejectedWithoutReplacingStableHistory() {
        val smoother = ArObjectDepthSmoother()
        repeat(5) { smoother.update(listOf(obj(10f, 2f)), it.toLong() + 1, it.toLong() + 1) }
        val spike = smoother.update(listOf(obj(10f, 8f)), 6, 6).single()
        assertNull(spike.depth.meters); assertEquals(8f, spike.depth.rawMeters!!, 0f)
        assertEquals(2f, smoother.update(listOf(obj(10f, 2.01f)), 7, 7).single().depth.meters!!, 0f)
    }
    @Test fun consistentApproachAndRecessionDoNotLagBehindFiveSampleMedian() {
        for (direction in listOf(-1, 1)) {
            val smoother = ArObjectDepthSmoother()
            var value = obj(10f, 2f)
            for (i in 0..5) value = smoother.update(listOf(obj(10f, 2f + direction * i * .1f)), i.toLong() + 1, i.toLong() + 1).single()
            assertEquals(2f + direction * .5f, value.depth.meters!!, .0001f)
            assertEquals("TREND", value.depth.filterStatus)
        }
    }
    @Test fun switchingRawFullDoesNotBypassTheLargeJumpGuardOrMixHistories() {
        val smoother = ArObjectDepthSmoother()
        fun sourced(meters: Float, method: com.samin.objectdetection.distance.DepthMethod) = obj(10f, meters).let { it.copy(depth = it.depth.copy(method = method)) }
        val raw = com.samin.objectdetection.distance.DepthMethod.RAW; val full = com.samin.objectdetection.distance.DepthMethod.FULL
        val original = smoother.update(listOf(sourced(2f, raw)), 1, 1).single()
        val changed = smoother.update(listOf(sourced(6f, full)), 2, 2).single()
        assertNull(changed.depth.meters); assertEquals(original.depth.trackId, changed.depth.trackId)
        assertEquals(6f, smoother.update(listOf(sourced(6f, full)), 3, 3).single().depth.meters!!, 0f)
    }
    @Test fun changedReprojectionNeverDisplaysAnOldZAsIfItWereCurrent() {
        val smoother = ArObjectDepthSmoother()
        smoother.update(listOf(obj(10f, 2f)), 100, 1)
        val repeated = smoother.update(listOf(obj(10f, 3f)), 100, 2).single()
        assertNull(repeated.depth.meters); assertEquals("REPROJECTED_DEPTH_CHANGE", repeated.depth.status)
        assertEquals(3f, repeated.depth.rawMeters!!, 0f)
    }
}
