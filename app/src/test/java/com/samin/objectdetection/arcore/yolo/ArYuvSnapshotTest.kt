package com.samin.objectdetection.arcore.yolo

import org.junit.Assert.*
import org.junit.Test

class ArYuvSnapshotTest {
    @Test fun neutralYuvConvertsToBlackAndWhiteAndKeepsOpaqueAlpha() {
        assertEquals(0xff000000.toInt(), uniform(16, 128, 128).toArgb().single())
        assertEquals(0xffffffff.toInt(), uniform(235, 128, 128).toArgb().single())
    }
    @Test fun chromaPlanesAreReadAsUAndVWithoutNv21InterleavingAssumptions() {
        val red = uniform(81, 90, 240).toArgb().single()
        assertTrue((red shr 16 and 255) >= 250); assertTrue((red shr 8 and 255) < 5); assertTrue((red and 255) < 5)
        val blue = uniform(41, 240, 110).toArgb().single()
        assertTrue((blue and 255) >= 250); assertTrue((blue shr 16 and 255) < 5)
    }
    @Test fun oddImageSizeAndPaddedRowsWithPixelStrideTwoAreSupported() {
        val y = ByteArray(13)
        for (row in 0..2) for (column in 0..2) y[row * 5 + column] = 100
        val uv = ByteArray(9)
        for (row in 0..1) for (column in 0..1) uv[row * 6 + column * 2] = 128.toByte()
        val snapshot = ArYuvSnapshot(3, 3, YuvPlaneBytes(y, 5, 1), YuvPlaneBytes(uv, 6, 2), YuvPlaneBytes(uv.copyOf(), 6, 2))
        val reference = uniform(100, 128, 128).toArgb().single()
        assertArrayEquals(IntArray(9) { reference }, snapshot.toArgb())
    }
    @Test fun invalidBufferOrDimensionsRejectConversionRatherThanReadingPastAPlane() {
        val good = uniform(100, 128, 128)
        assertThrows(IllegalArgumentException::class.java) { good.copy(width = 0).toArgb() }
        assertThrows(IllegalArgumentException::class.java) { good.copy(width = 2).toArgb() }
        assertThrows(IllegalArgumentException::class.java) { good.copy(y = YuvPlaneBytes(byteArrayOf(), 1, 1)).toArgb() }
    }
    @Test fun singleFlightGateHasNoBacklogHonorsThrottleAndStaysClosedAfterRelease() {
        val gate = ArYoloWorkGate()
        assertTrue(gate.acquire(0)); assertFalse(gate.acquire(1_000_000_000))
        gate.release(); assertFalse(gate.acquire(249_999_999)); assertTrue(gate.acquire(250_000_000))
        gate.release(); assertTrue(gate.acquire(500_000_000))
        gate.close(); gate.release(); assertFalse(gate.acquire(2_000_000_000))
    }
    private fun uniform(y: Int, u: Int, v: Int) = ArYuvSnapshot(1, 1,
        YuvPlaneBytes(byteArrayOf(y.toByte()), 1, 1), YuvPlaneBytes(byteArrayOf(u.toByte()), 1, 1), YuvPlaneBytes(byteArrayOf(v.toByte()), 1, 1))
}
