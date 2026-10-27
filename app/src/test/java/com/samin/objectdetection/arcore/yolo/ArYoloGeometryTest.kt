package com.samin.objectdetection.arcore.yolo

import com.samin.objectdetection.camera.YoloResizeMode
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.YoloModel
import com.samin.objectdetection.detector.YoloResizeTransform
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ArYoloGeometryTest {
    private val identity = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val rotations = listOf(identity, floatArrayOf(1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f),
        floatArrayOf(1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f), floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
    private val raw = ImageBox(10f, 12f, 70f, 42f)
    private val frameNs = 2_000_000_000L

    @Test fun fourRotationsRestoreUprightDetectorBoxesToCpuPixels() {
        val uprightBoxes = listOf(raw, ImageBox(18f, 10f, 48f, 70f), ImageBox(30f, 18f, 90f, 48f), ImageBox(12f, 30f, 42f, 90f))
        for ((i, corners) in rotations.withIndex()) {
            val mapping = ArImageMapping(100, 60, corners, identity)
            assertTrue(mapping.valid()); assertEquals(i * 90, mapping.rotation)
            assertEquals(raw, mapping.rawBox(uprightBoxes[i]))
            val point = mapping.view(mapping.bottomCenter(raw))
            val viewBox = mapping.viewBox(raw)
            assertEquals((viewBox.left + viewBox.right) / 2, point.x, 1e-6f)
            assertEquals(viewBox.bottom, point.y, 1e-6f)
        }
    }

    @Test fun viewportCropAndDepthTextureFovAreIndependentAndDoNotCropCpuBoxesTwice() {
        val view = floatArrayOf(-35f / 30, 0f, 65f / 30, 0f, -35f / 30, 1f, 65f / 30, 1f)
        val depth = floatArrayOf(.1f, .2f, .9f, .2f, .1f, .8f, .9f, .8f)
        val mapping = ArImageMapping(100, 60, view, depth)
        val box = mapping.viewBox(ImageBox(40f, 12f, 60f, 48f))
        assertEquals(1f / 6, box.left, 1e-5f); assertEquals(5f / 6, box.right, 1e-5f)
        assertEquals(.2f, box.top, 1e-5f); assertEquals(.8f, box.bottom, 1e-5f)
        val middle = mapping.depth(ImagePoint(50f, 30f))
        assertEquals(.5f, middle.x, 1e-6f); assertEquals(.5f, middle.y, 1e-6f)
        assertEquals(ImageBox(40f, 12f, 60f, 48f), mapping.rawBox(ImageBox(40f, 12f, 60f, 48f)))
    }

    @Test fun letterboxAndStretchAtBothModelSizesRestoreWithoutAnExtraModelScale() {
        for ((i, corners) in rotations.withIndex()) for (model in YoloModel.entries) for (mode in YoloResizeMode.entries) {
            val width = if (i % 2 == 0) 100 else 60
            val height = if (i % 2 == 0) 60 else 100
            val t = YoloResizeTransform.calculate(mode, width, height, model.inputSize, model.inputSize)
            val target = ImageBox(width * .2f, height * .2f, width * .7f, height * .7f)
            fun x(value: Float) = t.sourceX((value * t.scaleX + t.padLeft) / model.inputSize)
            fun y(value: Float) = t.sourceY((value * t.scaleY + t.padTop) / model.inputSize)
            val restored = ImageBox(x(target.left), y(target.top), x(target.right), y(target.bottom))
            val mapping = ArImageMapping(100, 60, corners, identity)
            val expected = mapping.rawBox(target); val actual = mapping.rawBox(restored)
            assertEquals(expected.left, actual.left, .001f); assertEquals(expected.bottom, actual.bottom, .001f)
        }
    }

    @Test fun bottomSamplesFollowDisplayDownOnEveryRotation() {
        val primaryPoints = listOf(ImagePoint(40f, 42f), ImagePoint(70f, 27f), ImagePoint(40f, 12f), ImagePoint(10f, 27f))
        for ((i, corners) in rotations.withIndex()) {
            val plane = depthPlane(primaryPoints[i], 2350)
            val value = ArObjectDepthSampler.sample(raw, ArImageMapping(100, 60, corners, identity), plane, frameNs, "TRACKING")
            assertEquals("BOTTOM_CENTER", value.status)
            assertEquals(2.35f, value.meters!!, .0001f)
            assertEquals(25, value.sampleCount)
            assertEquals(primaryPoints[i], value.samplePoint)
        }
    }

    @Test fun missingBottomSamplesFallBackToCenterAndNoSamplesKeepTheDetectionWithoutDistance() {
        val mapping = ArImageMapping(100, 60, identity, identity)
        val fallback = ArObjectDepthSampler.sample(raw, mapping, depthPlane(ImagePoint(40f, 27f), 3000), frameNs, "TRACKING")
        assertEquals("CENTER_FALLBACK", fallback.status); assertEquals(3f, fallback.meters!!, 0f)
        val invalid = ArObjectDepthSampler.sample(raw, mapping, depthPlane(ImagePoint(1f, 1f), 0), frameNs, "TRACKING")
        assertNull(invalid.meters); assertEquals("INSUFFICIENT_DEPTH", invalid.status)
        assertEquals(raw, mapping.rawBox(raw))
    }

    @Test fun depthUnavailableTrackingPausedStaleFutureAndInvalidBoxesRejectZ() {
        val mapping = ArImageMapping(100, 60, identity, identity)
        val plane = depthPlane(ImagePoint(40f, 42f), 1000)
        assertEquals("DEPTH_NOT_READY", ArObjectDepthSampler.sample(raw, mapping, null, frameNs, "TRACKING").status)
        assertEquals("TRACKING_PAUSED", ArObjectDepthSampler.sample(raw, mapping, plane, frameNs, "PAUSED").status)
        for (timestamp in listOf(0L, frameNs - 1_000_000_000, frameNs + 1)) {
            assertNull(ArObjectDepthSampler.sample(raw, mapping, plane.copy(timestampNs = timestamp), frameNs, "TRACKING").meters)
        }
        for (box in listOf(raw.copy(left = Float.NaN), raw.copy(right = 101f), raw.copy(top = 50f))) {
            assertNull(ArObjectDepthSampler.sample(box, mapping, plane, frameNs, "TRACKING").meters)
        }
        assertFalse(ArImageMapping(100, 60, FloatArray(8), identity).valid())
    }

    @Test fun allLabelsInBothModelContractsCanUseTheSameDepthSampler() {
        val mapping = ArImageMapping(100, 60, identity, identity)
        for (model in YoloModel.entries) for (label in model.classLabels) {
            val box = DetectionResult(label, .9f, raw.left, raw.top, raw.right, raw.bottom)
            assertEquals(2.35f, ArObjectDepthSampler.sample(ImageBox.of(box), mapping,
                depthPlane(ImagePoint(40f, 42f), 2350), frameNs, "TRACKING").meters!!, .0001f)
        }
    }

    @Test fun invalidTextureCoordinateAndCroppedDepthNeverReadOutsideValidPixels() {
        val outside = FloatArray(8) { 2f }
        val plane = depthPlane(ImagePoint(40f, 42f), 2350)
        assertNull(ArObjectDepthSampler.sample(raw, ArImageMapping(100, 60, identity, outside), plane, frameNs, "TRACKING").meters)
        assertNull(ArObjectDepthSampler.sample(raw, ArImageMapping(100, 60, identity, identity),
            plane.copy(crop = ImageBox(0f, 0f, 30f, 30f)), frameNs, "TRACKING").meters)
    }

    private fun depthPlane(center: ImagePoint, mm: Int): ArDepthPlane {
        val buffer = ByteBuffer.allocate(100 * 60 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in (center.y.toInt() - 2).coerceAtLeast(0)..(center.y.toInt() + 2).coerceAtMost(59))
            for (x in (center.x.toInt() - 2).coerceAtLeast(0)..(center.x.toInt() + 2).coerceAtMost(99)) buffer.putShort((y * 100 + x) * 2, mm.toShort())
        return ArDepthPlane(buffer.array(), 100, 60, 200, 2, frameNs)
    }
}
