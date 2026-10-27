package com.samin.objectdetection.arcore.yolo

import com.samin.objectdetection.distance.DepthInputMode
import com.samin.objectdetection.distance.DepthMethod
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ArObjectSurfaceDepthSamplerTest {
    private val corners = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val mapping = ArImageMapping(100, 100, corners, corners)
    private val box = ImageBox(10f, 10f, 90f, 90f)
    private val frameNs = 10_000_000_000L
    private val sampler = ArObjectSurfaceDepthSampler()
    private fun plane(method: DepthMethod = DepthMethod.FULL, confidence: Int = 255,
        timestamp: Long = frameNs, value: (Int, Int) -> Int): ArDepthPlane {
        val bytes = ByteBuffer.allocate(100 * 408).order(ByteOrder.LITTLE_ENDIAN)
        val confidenceBytes = ByteArray(100 * 205)
        for (y in 0..99) for (x in 0..99) {
            bytes.putShort(y * 408 + x * 4, value(x, y).toShort())
            confidenceBytes[y * 205 + x * 2] = confidence.toByte()
        }
        return ArDepthPlane(bytes.array(), 100, 100, 408, 4, timestamp, method = method,
            confidence = if (method == DepthMethod.RAW) ArDepthConfidence(YuvPlaneBytes(confidenceBytes, 205, 2), 100, 100, timestamp) else null)
    }
    private fun sample(full: ArDepthPlane?, raw: ArDepthPlane? = null, mode: DepthInputMode = DepthInputMode.FULL) =
        sampler.sample(box, mapping, full, raw, mode, frameNs, "TRACKING")

    @Test fun interiorSurfaceWinsOverFloorAndBackgroundAtBoxEdges() {
        val value = sample(plane { x, y -> if (x in 25..75 && y in 25..75) 2350 else 9000 })
        assertEquals("INTERIOR_CONSISTENT", value.status)
        assertEquals(2.35f, value.meters!!, .0001f)
        assertTrue(value.supportRegions >= 3); assertTrue(value.sampleCount >= 12)
        assertEquals(DepthMethod.FULL, value.method)
    }
    @Test fun twoSupportedDepthClustersRejectInsteadOfChoosingFarBackgroundOrNearestValue() {
        assertEquals("AMBIGUOUS_CLUSTERS", sample(plane { x, _ -> if (x < 50) 2000 else 7000 }).status)
        assertNull(sample(plane { x, _ -> if (x < 50) 2000 else 7000 }).meters)
    }
    @Test fun isolatedNearestNoiseAndZeroPixelsCannotBecomeTheObjectDistance() {
        val value = sample(plane { x, y -> when {
            x % 17 == 0 && y % 19 == 0 -> 1
            x % 13 == 0 && y % 11 == 0 -> 0
            else -> 3000
        } })
        assertEquals(3f, value.meters!!, 0f)
        assertNull(sample(plane { _, _ -> 0 }).meters)
    }
    @Test fun unsigned16BitAndPaddedDepthAndConfidenceStridesAreReadCorrectly() {
        val value = sample(plane { _, _ -> 2000 }, plane(DepthMethod.RAW) { _, _ -> 40000 }, DepthInputMode.RAW_WITH_FULL_FALLBACK)
        assertEquals(40f, value.meters!!, 0f); assertEquals(DepthMethod.RAW, value.method)
    }
    @Test fun rawConfidenceFiltersLowPixelsAndFallsBackToSameFrameFullWithSourceRecorded() {
        val full = plane { _, _ -> 4000 }
        val raw = plane(DepthMethod.RAW, 127) { _, _ -> 1000 }
        val fallback = sample(full, raw, DepthInputMode.RAW_WITH_FULL_FALLBACK)
        assertEquals(4f, fallback.meters!!, 0f); assertEquals(DepthMethod.FULL, fallback.method)
        assertTrue(fallback.status.contains("RAW_FALLBACK:INSUFFICIENT_DEPTH"))
        val accepted = sample(full, plane(DepthMethod.RAW, 128) { _, _ -> 2500 }, DepthInputMode.RAW_WITH_FULL_FALLBACK)
        assertEquals(2.5f, accepted.meters!!, 0f); assertEquals(DepthMethod.RAW, accepted.method)
    }
    @Test fun mismatchedRawConfidenceTimestampResolutionOrLayoutNeverUsesRaw() {
        val raw = plane(DepthMethod.RAW) { _, _ -> 1000 }
        val badPairs = listOf(raw.copy(confidence = null), raw.copy(confidence = raw.confidence!!.copy(timestampNs = frameNs - 1)),
            raw.copy(confidence = raw.confidence.copy(width = 99)),
            raw.copy(confidence = raw.confidence.copy(values = YuvPlaneBytes(ByteArray(1), -1, 1))))
        for (bad in badPairs) {
            val value = sample(plane { _, _ -> 3000 }, bad, DepthInputMode.RAW_WITH_FULL_FALLBACK)
            assertEquals(DepthMethod.FULL, value.method); assertEquals(3f, value.meters!!, 0f)
        }
    }
    @Test fun ambiguousRawIsNotReplacedWithSmoothFullBackground() {
        val value = sample(plane { _, _ -> 7000 }, plane(DepthMethod.RAW) { x, _ -> if (x < 50) 1000 else 7000 }, DepthInputMode.RAW_WITH_FULL_FALLBACK)
        assertEquals(DepthMethod.RAW, value.method); assertNull(value.meters); assertEquals("AMBIGUOUS_CLUSTERS", value.status)
    }
    @Test fun fullModeDoesNotUseRawAndMissingOrStaleDataStaysNull() {
        assertNull(sample(null, plane(DepthMethod.RAW) { _, _ -> 2000 }).meters)
        for (timestamp in listOf(0L, frameNs + 1, frameNs - 1_000_000_000))
            assertNull(sample(plane(timestamp = timestamp) { _, _ -> 2000 }).meters)
        assertNull(sampler.sample(box, mapping, plane { _, _ -> 2000 }, null, DepthInputMode.FULL, frameNs, "PAUSED").meters)
    }
    @Test fun smallClippedInvalidAndOffViewportObjectsCannotRepeatAPixelToInflateSupport() {
        val full = plane { _, _ -> 2000 }
        for (invalid in listOf(ImageBox(49f, 49f, 51f, 51f), box.copy(left = Float.NaN), box.copy(bottom = 101f)))
            assertNull(sampler.sample(invalid, mapping, full, null, DepthInputMode.FULL, frameNs, "TRACKING").meters)
        val outside = ArImageMapping(100, 100, floatArrayOf(2f, 2f, 3f, 2f, 2f, 3f, 3f, 3f), corners)
        assertNull(sampler.sample(box, outside, full, null, DepthInputMode.FULL, frameNs, "TRACKING").meters)
        assertNull(sample(full.copy(crop = ImageBox(0f, 0f, 20f, 20f))).meters)
    }
    @Test fun configurableInteriorGridKeepsDistinctSpatialSupportOnFourRotations() {
        val rotations = listOf(corners, floatArrayOf(1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f),
            floatArrayOf(1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f), floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
        val configured = ArObjectSurfaceDepthSampler(ObjectDepthSamplingConfig(xFractions = listOf(.35f, .5f, .65f)))
        for (view in rotations) {
            val value = configured.sample(box, ArImageMapping(100, 100, view, corners), plane { _, _ -> 5000 }, null,
                DepthInputMode.FULL, frameNs, "TRACKING")
            assertEquals(5f, value.meters!!, 0f)
        }
    }
}
