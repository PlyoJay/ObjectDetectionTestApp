package com.samin.objectdetection.distance

import android.graphics.Bitmap
import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.camera.FrameRoi
import com.samin.objectdetection.camera.RecognitionRoi
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.ObjectDetector
import com.samin.objectdetection.detector.YoloModel
import com.samin.objectdetection.location.UserLocationSnapshot
import com.samin.objectdetection.motion.ObjectMotionTracker
import com.samin.objectdetection.pipeline.DetectionPipeline
import org.junit.Assert.*
import org.junit.Test

/** Android Bitmap/pipeline integration. Compiled locally; execution requires a device/emulator. */
class ArCoreMainPipelineTest {
    @Test fun cameraXFallbackForArCoreNeverRunsTheCalibratedGroundEstimator() {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        try {
            val detector = object : ObjectDetector {
                override fun detect(bitmap: Bitmap) = listOf(DetectionResult("person", .9f, 200f, 100f, 400f, 420f))
                override fun close() = Unit
            }
            val pipeline = DetectionPipeline(detector, DetectionConfig(bollardGeometryFilterEnabled = false),
                ObjectMotionTracker(), { UserLocationSnapshot() }, distanceSettings = DistanceSettings(150f, 20f), groundDistanceEnabled = false)
            val result = pipeline.process(bitmap, 1000, 0, 1)
            assertEquals(1, result.mappedDetections.size)
            assertNull(result.mappedDetections.single().estimatedDistanceMeters)
            assertNull(result.mappedDetections.single().distanceEstimation)
        } finally { bitmap.recycle() }
    }
    @Test fun arCoreDistanceRunsAfterRoiRestorationAndPreservesFiltersWarningsAndCounts() {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        try {
            for (model in YoloModel.entries) for (roiOn in listOf(false, true)) for (meters in listOf(null, 2.3f)) {
                val config = DetectionConfig(inputSize = model.inputSize, recognitionRoi = RecognitionRoi.PRESET.copy(enabled = roiOn),
                    bollardGeometryFilterEnabled = false)
                val roi = FrameRoi.calculate(640, 480, config.recognitionRoi, false)
                val desired = DetectionResult("person", .95f, 220f, 180f, 400f, 430f, frameTimestampMs = 1000)
                val detector = object : ObjectDetector {
                    override fun detect(bitmap: Bitmap): List<DetectionResult> {
                        assertEquals(roi.width, bitmap.width); assertEquals(roi.height, bitmap.height)
                        return listOf(desired.copy(left = desired.left - roi.left, top = desired.top - roi.top,
                            right = desired.right - roi.left, bottom = desired.bottom - roi.top))
                    }
                    override fun close() = Unit
                }
                fun pipeline() = DetectionPipeline(detector, config, ObjectMotionTracker(), { UserLocationSnapshot() })
                val baseline = pipeline().process(bitmap, 1000, 0, 1)
                var called = false
                val context = object : FrameDepthContext {
                    override val projection = ViewportProjection(1.2f, 1f, -.1f, 0f)
                    override fun isCurrent() = true
                    override fun estimate(detections: List<DetectionResult>): List<DetectionResult> {
                        called = true; assertEquals(desired, detections.single())
                        return detections.map { ObjectDepthEstimate(meters, meters, DepthMethod.FULL, 25,
                            if (meters != null) "INTERIOR_CONSISTENT" else "INSUFFICIENT_DEPTH", 100, 100, Long.MAX_VALUE, "TRACKING").applyTo(it) }
                    }
                }
                val arCore = pipeline().process(bitmap, 1000, 0, 1, depthContext = context)
                fun strip(d: DetectionResult) = d.copy(estimatedDistanceMeters = null, distanceEstimation = null, depthEstimation = null)
                assertTrue(called); assertEquals(baseline.stageCounts, arCore.stageCounts)
                assertEquals(baseline.overlayDetections.map(::strip), arCore.overlayDetections.map(::strip))
                assertEquals(baseline.warningDetections.map(::strip), arCore.warningDetections.map(::strip))
                assertEquals(meters, arCore.mappedDetections.single().estimatedDistanceMeters)
            }
        } finally { bitmap.recycle() }
    }
}
