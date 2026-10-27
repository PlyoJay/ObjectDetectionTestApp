package com.samin.objectdetection.arcore.yolo

import com.samin.objectdetection.detector.DetectionResult
import org.junit.Assert.*
import org.junit.Test

class ArYoloFrameTest {
    private val captured = 5_000_000_000L
    private val box = ImageBox(.1f, .2f, .4f, .8f)
    private val frame = ArYoloFrame(2_000_000_000, 2_000_000_000, 1_500_000_000,
        captured, captured + 500_000_000, 3, "TRACKING", 640, 480, 160, 120,
        listOf(ArYoloObject(DetectionResult("person", .9f, 64f, 96f, 256f, 384f), box,
            ArObjectDepth(2f, status = "BOTTOM_CENTER"))), 20)

    @Test fun depthExpiresBeforeBoxWhenReturnedDepthIsOlderThanCameraFrame() {
        assertTrue(frame.isFresh(captured + 499_999_999, 3))
        assertTrue(frame.hasFreshDepth(captured + 499_999_999))
        assertTrue(frame.isFresh(captured + 500_000_000, 3))
        assertFalse(frame.hasFreshDepth(captured + 500_000_000))
        assertFalse(frame.isFresh(captured + 1_000_000_000, 3))
        assertEquals(1, frame.objects.size) // Missing/expired Z does not delete the detection.
    }

    @Test fun rotationPauseOrResumeEpochRejectsAnInFlightResultImmediately() {
        assertTrue(frame.isFresh(captured, 3))
        assertFalse(frame.isFresh(captured, 4))
        assertFalse(frame.copy(tracking = "PAUSED").hasFreshDepth(captured))
        assertFalse(frame.copy(depthTimestampNs = null).hasFreshDepth(captured))
        assertFalse(frame.copy(depthTimestampNs = 0).hasFreshDepth(captured))
    }

    @Test fun overlayUsesConfiguredShorterExpiryAndRejectsFutureCaptures() {
        assertTrue(frame.isFresh(captured + 199_999_999, 3, 200_000_000))
        assertFalse(frame.isFresh(captured + 200_000_000, 3, 200_000_000))
        assertFalse(frame.isFresh(captured - 1, 3))
        assertFalse(frame.hasFreshDepth(captured - 1))
    }
}
