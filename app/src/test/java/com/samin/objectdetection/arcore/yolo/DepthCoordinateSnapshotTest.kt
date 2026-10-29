package com.samin.objectdetection.arcore.yolo

import com.samin.objectdetection.distance.DepthInputMode
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DepthCoordinateSnapshotTest {
    private val identity = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    @Test fun cornerInterpolationMatchesDirectAffinePointTransformsAcrossRotationCropAndDifferentResolutions() {
        val rotations = listOf(identity, floatArrayOf(1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f),
            floatArrayOf(1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f), floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
        for (view in rotations) {
            val depth = floatArrayOf(.1f, .2f, .9f, .2f, .1f, .8f, .9f, .8f)
            val mapping = ArImageMapping(1280, 720, view, depth)
            val cpu = FloatArray(9 * 9 * 2); val direct = FloatArray(cpu.size); val directView = FloatArray(cpu.size)
            for (y in 0..8) for (x in 0..8) {
                val i = (y * 9 + x) * 2; val u = x / 8f; val v = y / 8f
                cpu[i] = u * 1280; cpu[i + 1] = v * 720
                direct[i] = .1f + .8f * u; direct[i + 1] = .2f + .6f * v
                directView[i] = view[0] + (view[2] - view[0]) * u + (view[4] - view[0]) * v
                directView[i + 1] = view[1] + (view[3] - view[1]) * u + (view[5] - view[1]) * v
            }
            val result = CoordinateMappingAudit.compare(mapping, cpu, direct, directView, direct, 160, 120, 1080, 1920)
            assertTrue(result.maxDepthPixelError < .001f); assertTrue(result.maxViewPixelError < .001f)
            assertEquals(0f, result.maxRoundTripPixelError, 0f)
        }
    }
    @Test fun auditDetectsInteriorWarpThatCornersAloneCannotReveal() {
        val mapping = ArImageMapping(100, 100, identity, identity)
        val cpu = floatArrayOf(50f, 50f); val direct = floatArrayOf(.6f, .5f)
        val audit = CoordinateMappingAudit.compare(mapping, cpu, direct, floatArrayOf(.5f, .5f), direct, 100, 100, 100, 100)
        assertEquals(10f, audit.maxDepthPixelError, .001f)
    }
    @Test fun actualPixelMembershipUsesFrozenDirectApiCoordinatesInsteadOfCornerForwardGuess() {
        val bytes = ByteBuffer.allocate(20000).order(ByteOrder.LITTLE_ENDIAN)
        val cpu = FloatArray(20000); val view = FloatArray(20000)
        for (y in 0..99) for (x in 0..99) {
            val i = (y * 100 + x) * 2; val px = x + 20.5f; val py = y + .5f
            cpu[i] = px; cpu[i + 1] = py; view[i] = px / 100; view[i + 1] = py / 100
            bytes.putShort(i, (if (px in 40f..80f && py in 20f..80f) 2000 else 8000).toShort())
        }
        val frozen = DepthPixelCoordinates(100, 100, 100, cpu, view)
        cpu.fill(Float.NaN); view.fill(Float.NaN) // Image/Frame-owned input cannot mutate the snapshot.
        val plane = ArDepthPlane(bytes.array(), 100, 100, 200, 2, 100, coordinates = frozen)
        val sampler = ArObjectSurfaceDepthSampler(); val box = ImageBox(40f, 20f, 80f, 80f)
        val value = sampler.sample(box, ArImageMapping(100, 100, identity, identity), plane, null, DepthInputMode.FULL, 100, "TRACKING")
        assertEquals(2f, value.meters!!, 0f)
        val cropped = DepthPixelCoordinates(100, 100, 100, FloatArray(20000) { i ->
            if (i % 2 == 0) (i / 2 % 100) + 20.5f else (i / 2 / 100) + .5f },
            FloatArray(20000) { i -> if (i % 2 == 0) ((i / 2 % 100) + 20.5f) / 100 else ((i / 2 / 100) + .5f) / 100 },
            cpuValidBounds = ImageBox(0f, 0f, 20f, 100f))
        assertNull(sampler.sample(box, ArImageMapping(100, 100, identity, identity), plane.copy(coordinates = cropped), null, DepthInputMode.FULL, 100, "TRACKING").meters)
        val mismatched = sampler.sample(box, ArImageMapping(100, 100, identity, identity), plane, null, DepthInputMode.FULL, 101, "TRACKING")
        assertNull(mismatched.meters); assertEquals("INVALID_COORDINATE_FRAME", mismatched.status)
    }
}
