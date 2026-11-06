package com.samin.objectdetection.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.ObjectDetector
import com.samin.objectdetection.detector.VisionStyleYoloDetector
import com.samin.objectdetection.detector.YoloModel
import com.samin.objectdetection.location.UserLocationSnapshot
import com.samin.objectdetection.motion.ObjectMotionTracker
import com.samin.objectdetection.pipeline.DetectionPipeline
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReusableRoiPipelineTest {
    @Test fun cropIsReusedAfterDetectorErrorAndCloseWaitsForReader() {
        val source = Bitmap.createBitmap(97, 61, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        var borrowed: Bitmap? = null
        var calls = 0
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val detector = object : ObjectDetector {
            override fun detect(bitmap: Bitmap): List<DetectionResult> {
                calls++
                if (calls == 1) { borrowed = bitmap; error("expected detector failure") }
                assertSame(borrowed, bitmap)
                assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                assertFalse(bitmap.isRecycled)
                return emptyList()
            }
            override fun close() = fail("Pipeline must not close caller-owned detector")
        }
        val pipeline = DetectionPipeline(detector, DetectionConfig(recognitionRoi = RecognitionRoi.PRESET),
            ObjectMotionTracker(), { UserLocationSnapshot() })
        val workers = Executors.newFixedThreadPool(2)
        try {
            assertThrows(IllegalStateException::class.java) { pipeline.process(source, 1234, 0) }
            assertFalse(checkNotNull(borrowed).isRecycled)
            source.eraseColor(Color.TRANSPARENT)
            val processing = workers.submit { pipeline.process(source, 1234, 0) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closeStarted = CountDownLatch(1)
            val closeFinished = CountDownLatch(1)
            val closing = workers.submit { closeStarted.countDown(); pipeline.close(); closeFinished.countDown() }
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
            assertFalse("Close must wait for detector read", closeFinished.await(100, TimeUnit.MILLISECONDS))
            assertFalse(checkNotNull(borrowed).isRecycled)
            release.countDown()
            processing.get(5, TimeUnit.SECONDS); closing.get(5, TimeUnit.SECONDS)
            assertTrue(checkNotNull(borrowed).isRecycled)
            assertFalse(source.isRecycled)
            pipeline.close()
            assertThrows(IllegalStateException::class.java) { pipeline.process(source, 1234, 0) }
        } finally { release.countDown(); workers.shutdownNow(); pipeline.close(); source.recycle() }
    }

    @Test fun everyRotationAndRoiKeepsTensorOutputAndRestoredBoxesEqualToLegacyBitmaps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = context.assets.open("self_test/known.png").use { requireNotNull(BitmapFactory.decodeStream(it)) }
        val detector = VisionStyleYoloDetector(context, YoloModel.YOLO_512.assetName, .2f, .45f)
        val rotationBuffer = FrameBitmapBuffer()
        fun bytes(name: String): ByteArray {
            val buffer = (detector.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(detector) as ByteBuffer)
                .duplicate().apply { rewind() }
            return ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
        try {
            for ((roiConfig, center) in listOf(RecognitionRoi() to false, RecognitionRoi.PRESET to false,
                RecognitionRoi() to true, RecognitionRoi(true, .13f, .07f, .11f, .17f) to false)) {
                val config = DetectionConfig(inputSize = 512, recognitionRoi = roiConfig, useCenterSquareCrop = center)
                DetectionPipeline(detector, config, ObjectMotionTracker(), { UserLocationSnapshot() }).use { pipeline ->
                    // Repeated dimensions include both reuse and shape replacement in the same pipeline.
                    for (degrees in listOf(0, 90, 180, 270, 90)) {
                        val referenceFrame = source.rotate(degrees.toFloat())
                        val roi = FrameRoi.calculate(referenceFrame.width, referenceFrame.height, roiConfig, center)
                        val referenceCrop = Bitmap.createBitmap(referenceFrame, roi.left, roi.top, roi.width, roi.height)
                        try {
                            val expected = detector.detect(referenceCrop)
                            val input = bytes("inputBuffer"); val output = bytes("outputBuffer")
                            val actual = pipeline.process(rotationBuffer.rotate(source, degrees), 1234, degrees)
                            assertArrayEquals("input degrees=$degrees roi=$roi", input, bytes("inputBuffer"))
                            assertArrayEquals("output degrees=$degrees roi=$roi", output, bytes("outputBuffer"))
                            assertEquals(expected.size, actual.mappedDetections.size)
                            expected.zip(actual.mappedDetections).forEach { (before, after) ->
                                assertEquals(before.label, after.label)
                                assertEquals(before.confidence, after.confidence, 0f)
                                assertEquals(roi.sourceX(before.left), after.left, 0f)
                                assertEquals(roi.sourceY(before.top), after.top, 0f)
                                assertEquals(roi.sourceX(before.right), after.right, 0f)
                                assertEquals(roi.sourceY(before.bottom), after.bottom, 0f)
                            }
                        } finally {
                            if (referenceCrop !== referenceFrame) referenceCrop.recycle()
                            if (referenceFrame !== source) referenceFrame.recycle()
                        }
                    }
                }
            }
        } finally { rotationBuffer.close(); detector.close(); source.recycle() }
    }
}
