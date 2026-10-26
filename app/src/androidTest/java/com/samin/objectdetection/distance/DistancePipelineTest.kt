package com.samin.objectdetection.distance

import android.graphics.Bitmap
import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.camera.FrameRoi
import com.samin.objectdetection.camera.RecognitionRoi
import com.samin.objectdetection.camera.YoloResizeMode
import com.samin.objectdetection.camera.rotate
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.ObjectDetector
import com.samin.objectdetection.detector.YoloModel
import com.samin.objectdetection.detector.YoloResizeTransform
import com.samin.objectdetection.location.UserLocationSnapshot
import com.samin.objectdetection.motion.ObjectMotionTracker
import com.samin.objectdetection.pipeline.DetectionPipeline
import org.junit.Assert.*
import org.junit.Test

class DistancePipelineTest {
    @Test fun realRotationAndPipelineRestorationKeepDistanceAndWarningsAcrossBothModelsRoiAndResizeModes() {
        val raw = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val mount = DistanceSettings(150f, 0f)
        try {
            for (rotation in listOf(0, 90, 180, 270)) {
                val bitmap = raw.rotate(rotation.toFloat())
                try {
                    val geometry = AnalysisCameraGeometry(1280, 720, rotation,
                        automaticIntrinsics = CameraIntrinsics(600f, 600f, 640f, 360f, "SYNTHETIC"))
                    val k = geometry.intrinsics(mount)!!
                    val desired = DetectionResult("bollard", .9f, k.cx - 20, k.cy - 30, k.cx + 20, k.cy + 120)
                    for (model in YoloModel.entries) for (resize in YoloResizeMode.entries) for (roiMode in 0..2) {
                        val config = DetectionConfig(inputSize = model.inputSize, yoloResizeMode = resize,
                            recognitionRoi = RecognitionRoi.PRESET.copy(enabled = roiMode == 1),
                            useCenterSquareCrop = roiMode == 2, bollardGeometryFilterEnabled = false)
                        val roi = FrameRoi.calculate(bitmap.width, bitmap.height, config.recognitionRoi, config.useCenterSquareCrop)
                        val transform = YoloResizeTransform.calculate(resize, roi.width, roi.height, model.inputSize, model.inputSize)
                        fun x(value: Float) = transform.sourceX(((value - roi.left) * transform.scaleX + transform.padLeft) / model.inputSize)
                        fun y(value: Float) = transform.sourceY(((value - roi.top) * transform.scaleY + transform.padTop) / model.inputSize)
                        val detector = object : ObjectDetector {
                            override fun detect(bitmap: Bitmap): List<DetectionResult> {
                                assertEquals(roi.width, bitmap.width); assertEquals(roi.height, bitmap.height)
                                return model.classLabels.map { label -> desired.copy(label = label,
                                    left = x(desired.left), top = y(desired.top), right = x(desired.right), bottom = y(desired.bottom)) }
                            }
                            override fun close() = Unit
                        }
                        fun pipeline(settings: DistanceSettings) = DetectionPipeline(detector, config, ObjectMotionTracker(),
                            { UserLocationSnapshot() }, distanceSettings = settings)
                        val calibrated = pipeline(mount).process(bitmap, 1234, rotation, 1, geometry)
                        val baseline = pipeline(DistanceSettings()).process(bitmap, 1234, rotation, 1, geometry)
                        assertEquals(model.classLabels.size, calibrated.mappedDetections.size)
                        for (detection in calibrated.mappedDetections) {
                            assertEquals(desired.bottom, detection.bottom, .001f)
                            assertEquals(7.5f, detection.estimatedDistanceMeters!!, .001f)
                        }
                        fun strip(d: DetectionResult) = d.copy(estimatedDistanceMeters = null, distanceEstimation = null)
                        assertEquals(baseline.overlayDetections.map(::strip), calibrated.overlayDetections.map(::strip))
                        assertEquals(baseline.warningDetections.map(::strip), calibrated.warningDetections.map(::strip))
                        assertEquals(baseline.stageCounts, calibrated.stageCounts)
                    }
                } finally { if (bitmap !== raw) bitmap.recycle() }
            }
        } finally { raw.recycle() }
    }

    @Test fun clippedOrUncalibratedDistanceDoesNotRemoveAnOtherwiseValidDetection() {
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val detector = object : ObjectDetector {
            override fun detect(bitmap: Bitmap) = listOf(DetectionResult("person", .95f, 500f, 400f, 700f, 720f))
            override fun close() = Unit
        }
        try {
            val geometry = AnalysisCameraGeometry(1280, 720, 0, automaticIntrinsics = CameraIntrinsics(600f, 600f, 640f, 360f, "TEST"))
            val pipeline = DetectionPipeline(detector, DetectionConfig(bollardGeometryFilterEnabled = false),
                ObjectMotionTracker(), { UserLocationSnapshot() }, distanceSettings = DistanceSettings(150f, 0f))
            val result = pipeline.process(bitmap, 1234, 0, 1, geometry)
            assertEquals("CONTACT_CLIPPED", result.mappedDetections.single().distanceEstimation!!.status)
            assertEquals(1, result.overlayDetections.size)
            assertNull(result.overlayDetections.single().estimatedDistanceMeters)
            val mismatched = pipeline.process(bitmap, 1235, 0, 2, geometry.copy(bufferWidth = 720, bufferHeight = 1280))
            assertNull(mismatched.mappedDetections.single().estimatedDistanceMeters)
        } finally { bitmap.recycle() }
    }
}
