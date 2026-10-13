package com.samin.objectdetection.metrics

import org.junit.Assert.*
import org.junit.Test

class FrameRateMeterTest {
    @Test fun countsDeliveryAndSuccessSeparatelyUsingActualElapsedTime() {
        val meter = FrameRateMeter()
        meter.start(0)
        meter.received(0); meter.succeeded(200_000_000)
        meter.received(500_000_000); meter.received(800_000_000)
        val rates = meter.snapshot(1_250_000_000)
        assertEquals(2.4, rates.analyzerFps, 0.00001)
        assertEquals(0.8, rates.inferenceFps, 0.00001)
    }
    @Test fun firstFrameDoesNotUseEpochAndIdleWindowDecaysToZero() {
        val meter = FrameRateMeter()
        meter.start(8_000_000_000)
        meter.received(8_100_000_000)
        assertEquals(0.0, meter.snapshot(8_100_000_000).analyzerFps, 0.0)
        assertEquals(1.0, meter.snapshot(9_000_000_000).analyzerFps, 0.0)
        assertEquals(0.0, meter.snapshot(11_000_000_000).analyzerFps, 0.0)
    }
    @Test fun inferenceLatencyIsUnknownForIncomparableCameraClock() {
        val trace = FrameTrace("session", 3, 1000, 100, false, 1020, 1_000_000_000,
            inferenceStartedElapsedNs = 1_120_000_000)
        assertEquals(-1L, trace.frameAgeAtInferenceMs)
        assertEquals(120L, trace.analyzerToInferenceMs)
        assertEquals(220L, trace.copy(captureTimestampReliable = true, cameraTimestampNs = 900_000_000).frameAgeAtInferenceMs)
    }
}
