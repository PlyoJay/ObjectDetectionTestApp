package com.samin.objectdetection.arcore

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DepthPlaneSamplerTest {
    @Test fun littleEndianUnsignedSamplesHonorBothStridesAndBufferPosition() {
        val width = 7; val height = 7; val rowStride = 32; val pixelStride = 4
        val data = ByteBuffer.allocate(11 + height * rowStride).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) for (x in 0 until width) data.putShort(11 + y * rowStride + x * pixelStride, 40000.toShort())
        data.position(11)
        data.order(ByteOrder.BIG_ENDIAN) // Reader must explicitly use little-endian.
        val result = DepthPlaneSampler.sample(data, width, height, rowStride, pixelStride, DepthPixel(3, 3))
        assertEquals(40.0, result.meters!!, 0.0)
        assertEquals(25, result.validCount)
        assertEquals(11, data.position())
        assertEquals(ByteOrder.BIG_ENDIAN, data.order())
    }

    @Test fun millimetersBecomeMetersAndHighBitsAreNotMaskedAsPackedConfidence() {
        for (mm in listOf(1, 1000, 2350, 5000, 65535)) {
            val data = plane(5, 5) { _, _ -> mm }
            val result = DepthPlaneSampler.sample(data, 5, 5, 10, 2, DepthPixel(2, 2))
            assertEquals(mm / 1000.0, result.meters!!, 1e-9)
        }
    }

    @Test fun zeroHolesAndTooFewSamplesNeverGenerateDistance() {
        val empty = plane(5, 5) { _, _ -> 0 }
        assertNull(DepthPlaneSampler.sample(empty, 5, 5, 10, 2, DepthPixel(2, 2)).meters)
        val eight = plane(5, 5) { x, y -> if (y * 5 + x < 8) 2350 else 0 }
        val sparse = DepthPlaneSampler.sample(eight, 5, 5, 10, 2, DepthPixel(2, 2))
        assertNull(sparse.meters); assertEquals(8, sparse.validCount)
        val nine = plane(5, 5) { x, y -> if (y * 5 + x < 9) 2350 else 0 }
        assertEquals(2.35, DepthPlaneSampler.sample(nine, 5, 5, 10, 2, DepthPixel(2, 2)).meters!!, 1e-9)
    }

    @Test fun localMedianAndMadRejectAnIsolatedFarSurfaceWithoutAveragingItsError() {
        val data = plane(5, 5) { x, y -> if (x == 2 && y == 2) 60000 else 2000 + x }
        val region = DepthPlaneSampler.sample(data, 5, 5, 10, 2, DepthPixel(2, 2))
        assertEquals(2.002, region.meters!!, .002)
        assertEquals(24, region.validCount)
    }

    @Test fun transformedCoordinatesUseDepthDimensionsAndRejectOutOfRangeWithoutClamping() {
        assertEquals(DepthPixel(80, 60), DepthPlaneSampler.pixel(.5f, .5f, 160, 120))
        assertEquals(DepthPixel(60, 80), DepthPlaneSampler.pixel(.5f, .5f, 120, 160))
        assertEquals(DepthPixel(32, 90), DepthPlaneSampler.pixel(.2f, .75f, 160, 120))
        for ((u, v) in listOf(-.01f to .5f, 1f to .5f, .5f to 1f, Float.NaN to .5f, .5f to Float.POSITIVE_INFINITY)) {
            assertNull(DepthPlaneSampler.pixel(u, v, 160, 120))
        }
        assertNull(DepthPlaneSampler.pixel(.5f, .5f, 0, 120))
    }

    @Test fun clippedNeighborhoodTruncatedBufferAndInvalidLayoutsAreSafe() {
        val data = plane(5, 5) { _, _ -> 1000 }
        val edge = DepthPlaneSampler.sample(data, 5, 5, 10, 2, DepthPixel(0, 0))
        assertEquals(9, edge.validCount); assertEquals(1.0, edge.meters!!, 0.0)
        assertNull(DepthPlaneSampler.sample(ByteBuffer.allocate(3), 5, 5, 10, 2, DepthPixel(2, 2)).meters)
        assertNull(DepthPlaneSampler.sample(data, 5, 5, 9, 2, DepthPixel(2, 2)).meters)
        assertNull(DepthPlaneSampler.sample(data, 5, 5, 10, 1, DepthPixel(2, 2)).meters)
        assertNull(DepthPlaneSampler.sample(data, 5, 5, 10, 2, DepthPixel(5, 2)).meters)
    }

    private fun plane(width: Int, height: Int, value: (Int, Int) -> Int) =
        ByteBuffer.allocate(width * height * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (y in 0 until height) for (x in 0 until width) putShort((y * width + x) * 2, value(x, y).toShort())
        }
}
