package com.samin.objectdetection.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.ui.BoundingBoxOverlay
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.ObjectDetector
import com.samin.objectdetection.location.UserLocationSnapshot
import com.samin.objectdetection.motion.ObjectMotionTracker
import com.samin.objectdetection.pipeline.DetectionPipeline
import org.junit.Assert.*
import org.junit.Test

class RecognitionRoiInstrumentedTest {
    @Test fun regionGuideUsesFullFrameFitCenterEvenWhenBboxesAreDisabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val overlay = BoundingBoxOverlay(instrumentation.targetContext)
            overlay.layout(0, 0, 1000, 1000)
            overlay.setDrawingEnabled(false)
            overlay.updateRecognitionRegion(100, 200, FrameRoi(10, 20, 90, 180), true)
            val rendered = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
            try {
                rendered.eraseColor(Color.BLACK)
                overlay.draw(Canvas(rendered))
                // FIT_CENTER scale=5, offsetX=250, offsetY=0; top edge at y=100.
                assertEquals(Color.GREEN, rendered.getPixel(500, 100))
                assertEquals(Color.BLACK, rendered.getPixel(500, 50))
                overlay.updateRecognitionRegion(100, 200, FrameRoi(10, 20, 90, 180), false)
                rendered.eraseColor(Color.BLACK)
                overlay.draw(Canvas(rendered))
                assertEquals(Color.BLACK, rendered.getPixel(500, 100))
            } finally { overlay.clearDetections(); rendered.recycle() }
        }
    }

    @Test fun realBitmapRotationThenSinglePipelineCropRestoresBoxesOnEveryQuarterTurn() {
        val raw = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888)
        for (y in 0 until 60) for (x in 0 until 100) raw.setPixel(x, y,
            if (y < 30) { if (x < 50) Color.RED else Color.GREEN }
            else { if (x < 50) Color.BLUE else Color.YELLOW })
        try {
            for ((rotation, expectedTopLeft) in listOf(0 to Color.RED, 90 to Color.BLUE, 180 to Color.YELLOW, 270 to Color.GREEN)) {
                val upright = raw.rotate(rotation.toFloat())
                try {
                    assertEquals(expectedTopLeft, upright.getPixel(0, 0))
                    val roi = FrameRoi.calculate(upright.width, upright.height, RecognitionRoi.PRESET, false)
                    var calls = 0
                    val detector = object : ObjectDetector {
                        override fun detect(bitmap: Bitmap): List<DetectionResult> {
                            calls++
                            assertEquals(roi.width, bitmap.width); assertEquals(roi.height, bitmap.height)
                            assertEquals(upright.getPixel(roi.left, roi.top), bitmap.getPixel(0, 0))
                            return listOf(DetectionResult("person", .9f, 0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
                        }
                        override fun close() {}
                    }
                    val pipeline = DetectionPipeline(detector,
                        DetectionConfig(recognitionRoi = RecognitionRoi.PRESET, useCenterSquareCrop = true),
                        ObjectMotionTracker(), { UserLocationSnapshot() })
                    val result = pipeline.process(upright, 1234, rotation)
                    assertEquals(1, calls)
                    val box = result.mappedDetections.single()
                    assertEquals(roi.left.toFloat(), box.left, 0f)
                    assertEquals(roi.top.toFloat(), box.top, 0f)
                    assertEquals(roi.right.toFloat(), box.right, 0f)
                    assertEquals(roi.bottom.toFloat(), box.bottom, 0f)
                    assertEquals(roi.sourceX(640f, 640), box.right, .001f)
                    assertEquals(roi.sourceY(360f, 360), box.bottom, .001f)
                    assertFalse(upright.isRecycled)
                } finally { if (upright !== raw) upright.recycle() }
            }
        } finally { raw.recycle() }
    }
}
