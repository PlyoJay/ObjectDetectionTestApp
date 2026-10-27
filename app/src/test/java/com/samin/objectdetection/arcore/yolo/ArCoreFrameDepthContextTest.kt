package com.samin.objectdetection.arcore.yolo

import com.samin.objectdetection.camera.FrameRoi
import com.samin.objectdetection.camera.YoloResizeMode
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.YoloModel
import com.samin.objectdetection.detector.YoloResizeTransform
import com.samin.objectdetection.distance.DepthInputMode
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ArCoreFrameDepthContextTest {
    private val identity = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val rotations = listOf(identity, floatArrayOf(1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f),
        floatArrayOf(1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f), floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
    private val frameNs = 10_000_000_000L
    private val elapsed = 20_000_000_000L
    private fun capture(view: FloatArray = identity, tracking: String = "TRACKING"): ArYoloCapture {
        val bytes = ByteBuffer.allocate(100 * 60 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(6000) { bytes.putShort(it * 2, 2350.toShort()) }
        val yuv = ArYuvSnapshot(100, 60, YuvPlaneBytes(ByteArray(6000), 100, 1),
            YuvPlaneBytes(ByteArray(1500), 50, 1), YuvPlaneBytes(ByteArray(1500), 50, 1))
        return ArYoloCapture(yuv, ArImageMapping(100, 60, view, identity),
            ArDepthPlane(bytes.array(), 100, 60, 200, 2, frameNs - 200_000_000),
            "DEPTH_AVAILABLE", frameNs, frameNs, elapsed, 1000, 3, tracking)
    }

    @Test fun restoredRoiAndLetterboxBoxesUseOneMappingAcrossBothModelsAndAllRotations() {
        for ((i, view) in rotations.withIndex()) for (model in YoloModel.entries) for (resize in YoloResizeMode.entries) for (useRoi in listOf(false, true)) {
            val source = capture(view)
            val w = if (i % 2 == 0) 100 else 60; val h = if (i % 2 == 0) 60 else 100
            val roi = if (useRoi) FrameRoi(w / 10, h / 10, w * 9 / 10, h * 9 / 10) else FrameRoi(0, 0, w, h)
            val transform = YoloResizeTransform.calculate(resize, roi.width, roi.height, model.inputSize, model.inputSize)
            val target = source.mapping.uprightBox(ImageBox(20f, 12f, 80f, 48f))
            fun x(pixel: Float) = roi.sourceX(transform.sourceX(((pixel - roi.left) * transform.scaleX + transform.padLeft) / model.inputSize))
            fun y(pixel: Float) = roi.sourceY(transform.sourceY(((pixel - roi.top) * transform.scaleY + transform.padTop) / model.inputSize))
            val detections = model.classLabels.map { DetectionResult(it, .9f, x(target.left), y(target.top), x(target.right), y(target.bottom)) }
            val context = ArCoreFrameDepthContext(source, DepthInputMode.FULL, ArObjectSurfaceDepthSampler(), ArObjectDepthSmoother(), { elapsed + 10 }, { 3 })
            val result = context.estimate(detections)
            assertEquals(model.classLabels.size, result.size)
            result.forEach { d ->
                assertEquals(2.35f, d.estimatedDistanceMeters!!, .0001f)
                assertEquals("TRACKING", d.depthEstimation!!.trackingState); assertNull(d.distanceEstimation)
                assertEquals(target.left, d.left, .001f); assertEquals(target.bottom, d.bottom, .001f)
            }
            val originalView = source.mapping.viewBox(source.mapping.rawBox(target))
            assertEquals(originalView.left, context.projection.x(target.left, w), .0001f)
            assertEquals(originalView.bottom, context.projection.y(target.bottom, h), .0001f)
        }
    }
    @Test fun oldDepthExpiresUsingCaptureAgeAndEpochInvalidationNeverDeletesDetections() {
        val source = capture()
        var now = elapsed + 799_999_999
        var epoch = 3L
        val context = ArCoreFrameDepthContext(source, DepthInputMode.FULL, ArObjectSurfaceDepthSampler(), ArObjectDepthSmoother(), { now }, { epoch })
        val detections = listOf(DetectionResult("person", .9f, 20f, 12f, 80f, 48f))
        val fresh = context.estimate(detections).single()
        assertTrue(fresh.depthEstimation!!.isFresh(now)); assertEquals(2.35f, fresh.estimatedDistanceMeters!!, .0001f)
        now++
        val stale = context.estimate(detections)
        assertEquals(1, stale.size); assertNull(stale.single().estimatedDistanceMeters)
        assertEquals("STALE_DEPTH", stale.single().depthEstimation!!.status)
        assertEquals(2.35f, stale.single().depthEstimation!!.rawMeters!!, .0001f)
        epoch++
        assertFalse(context.isCurrent())
        assertEquals("COORDINATES_INVALIDATED", context.estimate(detections).single().depthEstimation!!.status)
    }
    @Test fun trackingPauseAndMissingDepthPreserveAllLabelsWithoutGroundFallback() {
        val detections = listOf(DetectionResult("car", .9f, 20f, 12f, 80f, 48f))
        for (source in listOf(capture(tracking = "PAUSED"), capture().copy(depth = null))) {
            val context = ArCoreFrameDepthContext(source, DepthInputMode.FULL, ArObjectSurfaceDepthSampler(), ArObjectDepthSmoother(), { elapsed }, { 3 })
            val result = context.estimate(detections).single()
            assertEquals("car", result.label); assertNull(result.estimatedDistanceMeters); assertNull(result.distanceEstimation)
        }
    }
}
