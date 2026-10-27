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
        assertEquals(1f, smoother.update(listOf(obj(10f, 9f)), 1, 2).single().depth.meters!!, 0f)
        assertEquals(9f, smoother.update(listOf(obj(10f, 9f, "cat")), 2, 3).single().depth.meters!!, 0f)
        assertEquals(5f, smoother.update(listOf(obj(10f, 5f, "cat")), 3, 1_000_000_003).single().depth.meters!!, 0f)
    }
    @Test fun medianHasAtMostFiveSamplesAndResetDoesNotResurrectOldDistance() {
        val smoother = ArObjectDepthSmoother()
        var result = obj(10f, 1f)
        for (i in 1..10) result = smoother.update(listOf(obj(10f, i.toFloat())), i.toLong(), i.toLong()).single()
        assertEquals(8f, result.depth.meters!!, 0f); assertEquals(10f, result.depth.rawMeters!!, 0f)
        smoother.reset()
        assertEquals(20f, smoother.update(listOf(obj(10f, 20f)), 11, 11).single().depth.meters!!, 0f)
    }
    @Test fun abruptDepthChangeAndInputSourceSwitchStartNewHistoryWithoutDisplayLag() {
        val smoother = ArObjectDepthSmoother()
        repeat(5) { smoother.update(listOf(obj(10f, 2f)), it.toLong() + 1, it.toLong() + 1) }
        assertEquals(4f, smoother.update(listOf(obj(10f, 4f)), 6, 6).single().depth.meters!!, 0f)
        val raw = obj(10f, 4.2f).let { it.copy(depth = it.depth.copy(method = com.samin.objectdetection.distance.DepthMethod.RAW, timestampNs = 7)) }
        assertEquals(4.2f, smoother.update(listOf(raw), null, 7).single().depth.meters!!, 0f)
    }
    @Test fun ambiguousSameClassOverlappingBoxesDoNotBorrowEitherTrackHistory() {
        val smoother = ArObjectDepthSmoother()
        smoother.update(listOf(obj(10f, 2f), obj(20f, 4f)), 1, 1)
        val result = smoother.update(listOf(obj(14f, 2.2f), obj(16f, 4.2f)), 2, 2)
        assertEquals(2.2f, result[0].depth.meters!!, 0f); assertEquals(4.2f, result[1].depth.meters!!, 0f)
    }
}
