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
    @Test fun rotationsRoiAndModelClassesRestoreBoxesWithoutProducingDistance() {
        val raw = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        try {
            for (rotation in listOf(0, 90, 180, 270)) {
                val bitmap = raw.rotate(rotation.toFloat())
                try {
                    val desired = DetectionResult("person", .95f, bitmap.width * .4f, bitmap.height * .4f,
                        bitmap.width * .6f, bitmap.height * .6f)
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
                                return model.classLabels.map { desired.copy(label = it,
                                    left = x(desired.left), top = y(desired.top), right = x(desired.right), bottom = y(desired.bottom)) }
                            }
                            override fun close() = Unit
                        }
                        val pipeline = DetectionPipeline(detector, config, ObjectMotionTracker(), { UserLocationSnapshot() })
                        val result = pipeline.process(bitmap, 1234, rotation, 1)
                        assertEquals(model.classLabels, result.mappedDetections.map { it.label })
                        for (detection in result.mappedDetections) {
                            assertEquals(desired.left, detection.left, .001f)
                            assertEquals(desired.bottom, detection.bottom, .001f)
                            assertEquals(.95f, detection.confidence)
                            assertNull(detection.estimatedDistanceMeters)
                            assertNull(detection.distanceEstimation)
                        }
                        assertTrue(result.overlayDetections.isNotEmpty())
                    }
                } finally { if (bitmap !== raw) bitmap.recycle() }
            }
        } finally { raw.recycle() }
    }

    @Test fun missingDistanceDoesNotSuppressBoxOrExistingVisualWarning() {
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val detector = object : ObjectDetector {
            override fun detect(bitmap: Bitmap) = listOf(DetectionResult("person", .95f, 400f, 200f, 850f, 710f))
            override fun close() = Unit
        }
        try {
            val result = DetectionPipeline(detector, DetectionConfig(bollardGeometryFilterEnabled = false),
                ObjectMotionTracker(), { UserLocationSnapshot() }).process(bitmap, 1234, 0, 1)
            assertEquals(1, result.overlayDetections.size)
            assertNull(result.overlayDetections.single().estimatedDistanceMeters)
            assertTrue(result.warningDetections.isNotEmpty())
            assertTrue(result.mappedDetections.single().warningFeedback.shouldNotify)
        } finally { bitmap.recycle() }
    }
}
