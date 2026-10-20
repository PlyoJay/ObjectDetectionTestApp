package com.samin.objectdetection.detector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.camera.*
import com.samin.objectdetection.location.UserLocationSnapshot
import com.samin.objectdetection.motion.ObjectMotionTracker
import com.samin.objectdetection.pipeline.DetectionPipeline
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelSelectionDetectorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun field(target: Any, name: String): Any = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)!!

    @Test fun bothModelsRunAndReuseTensorSizedStorageWithExactLetterboxAndStretchPixels() {
        val source = Bitmap.createBitmap(511, 733, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val positive = context.assets.open("self_test/known.png").use { requireNotNull(BitmapFactory.decodeStream(it)) }
        try {
            for (model in listOf(YoloModel.YOLO_640, YoloModel.YOLO_512, YoloModel.YOLO_640)) {
                for (mode in YoloResizeMode.entries) {
                    val detector = VisionStyleYoloDetector(context, model.assetName, .2f, .45f,
                        sizeFilterMode = SizeFilterMode.DISABLED, resizeMode = mode)
                    val reference = Bitmap.createBitmap(model.inputSize, model.inputSize, Bitmap.Config.ARGB_8888)
                    try {
                        val identity = detector.modelIdentity()
                        assertEquals(model.inputSize, identity.inputWidth)
                        assertEquals(model.inputSize, identity.inputHeight)
                        assertEquals(9, identity.classCount)
                        assertEquals(ModelAssets.sha256(context, model.assetName), identity.sha256)
                        val contract = ModelAssets.validate(field(detector, "interpreter") as org.tensorflow.lite.Interpreter,
                            model.assetName, 9)
                        assertEquals(if (model == YoloModel.YOLO_512) 5376 else 8400, contract.output.candidates)
                        val buffer = field(detector, "inputBuffer") as ByteBuffer
                        val bitmap = field(detector, "reusableInputBitmap") as Bitmap
                        val output = field(detector, "outputBuffer") as ByteBuffer
                        repeat(2) {
                            detector.detect(source)
                            val transform = YoloResizeTransform.calculate(mode, source.width, source.height, identity.inputWidth, identity.inputHeight)
                            val canvas = Canvas(reference)
                            canvas.drawColor(Color.rgb(114, 114, 114))
                            canvas.drawBitmap(source, null, RectF(transform.padLeft.toFloat(), transform.padTop.toFloat(),
                                (transform.padLeft + transform.scaledWidth).toFloat(), (transform.padTop + transform.scaledHeight).toFloat()),
                                Paint(Paint.FILTER_BITMAP_FLAG))
                            val pixels = IntArray(identity.inputWidth * identity.inputHeight)
                            reference.getPixels(pixels, 0, identity.inputWidth, 0, 0, identity.inputWidth, identity.inputHeight)
                            val expected = ByteBuffer.allocate(buffer.capacity()).order(ByteOrder.nativeOrder())
                            RgbTensorWriter.fill(expected, pixels, contract.input.layout, contract.inputCodec)
                            val actual = ByteArray(buffer.capacity())
                            buffer.duplicate().apply { rewind() }.get(actual)
                            assertArrayEquals("${model.assetName} $mode", expected.array(), actual)
                            assertSame(buffer, field(detector, "inputBuffer"))
                            assertSame(bitmap, field(detector, "reusableInputBitmap"))
                            assertSame(output, field(detector, "outputBuffer"))
                            assertEquals(0, TensorStatistics.measure(output, contract.outputCodec).nonFinite)
                        }
                        val detections = detector.detect(positive)
                        assertTrue("${model.assetName} $mode positive image", detections.any { it.label == "bollard" })
                        assertTrue(detections.all { it.left >= 0 && it.top >= 0 && it.right <= positive.width && it.bottom <= positive.height })
                        assertEquals("SUCCESS", detector.executionInfo().lastInference)
                    } finally { detector.close(); reference.recycle() }
                }
            }
        } finally { source.recycle(); positive.recycle() }
    }

    @Test fun syntheticBoxesExerciseActualParserNmsAndRoiRestorationForBothModelsAndModes() {
        val source = Bitmap.createBitmap(721, 1281, Bitmap.Config.ARGB_8888)
        try {
            for (model in YoloModel.entries) for (mode in YoloResizeMode.entries) {
                val detector = VisionStyleYoloDetector(context, model.assetName, .2f, .45f,
                    sizeFilterMode = SizeFilterMode.DISABLED, resizeMode = mode)
                try {
                    val roi = FrameRoi.calculate(source.width, source.height, RecognitionRoi.PRESET, false)
                    val transform = YoloResizeTransform.calculate(mode, roi.width, roi.height, model.inputSize, model.inputSize)
                    val contract = ModelAssets.validate(field(detector, "interpreter") as org.tensorflow.lite.Interpreter, model.assetName, 9)
                    val buffer = field(detector, "outputBuffer") as ByteBuffer
                    val values = buffer.asFloatBuffer()
                    repeat(values.capacity()) { values.put(it, 0f) }
                    fun value(channel: Int, candidate: Int, v: Float) {
                        values.put(if (contract.output.channelsFirst) channel * contract.output.candidates + candidate
                            else candidate * contract.output.channels + channel, v)
                    }
                    val left = (roi.width * .2f * transform.scaleX + transform.padLeft) / model.inputSize
                    val right = (roi.width * .4f * transform.scaleX + transform.padLeft) / model.inputSize
                    val top = (roi.height * .25f * transform.scaleY + transform.padTop) / model.inputSize
                    val bottom = (roi.height * .75f * transform.scaleY + transform.padTop) / model.inputSize
                    // Two identical bollards must be suppressed to one by the production NMS.
                    for (candidate in 0..1) {
                        value(0, candidate, (left + right) / 2); value(1, candidate, (top + bottom) / 2)
                        value(2, candidate, right - left); value(3, candidate, bottom - top)
                        value(4, candidate, .9f - candidate * .1f)
                    }
                    val proxy = object : ObjectDetector {
                        @Suppress("UNCHECKED_CAST")
                        override fun detect(bitmap: Bitmap): List<DetectionResult> {
                            assertEquals(roi.width, bitmap.width); assertEquals(roi.height, bitmap.height)
                            return detector.javaClass.declaredMethods.single { it.name == "parseOutput" }.apply { isAccessible = true }
                                .invoke(detector, buffer, transform, 0L, 0L, 0L, SystemClock.elapsedRealtimeNanos(),
                                    System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), 0L, 0L, 0L) as List<DetectionResult>
                        }
                        override fun close() = Unit
                    }
                    val pipeline = DetectionPipeline(proxy, DetectionConfig(inputSize = model.inputSize,
                        recognitionRoi = RecognitionRoi.PRESET, yoloResizeMode = mode), ObjectMotionTracker(), { UserLocationSnapshot() })
                    val result = pipeline.process(source, 1234L, 0)
                    val box = result.mappedDetections.single()
                    assertEquals(roi.left + roi.width * .2f, box.left, .001f)
                    assertEquals(roi.left + roi.width * .4f, box.right, .001f)
                    assertEquals(roi.top + roi.height * .25f, box.top, .001f)
                    assertEquals(roi.top + roi.height * .75f, box.bottom, .001f)
                    assertEquals(1, result.visibleDetections.size) // Geometry and size filters preserve the valid box.
                } finally { detector.close() }
            }
        } finally { source.recycle() }
    }

    @Test fun missingAssetHasActionableError() {
        val error = assertThrows(IllegalArgumentException::class.java) { ModelAssets.load(context, "missing_model.tflite") }
        assertTrue(error.message!!.contains("missing_model.tflite"))
        assertTrue(error.message!!.contains("app/src/main/assets/"))
    }

    @Test fun mislabeledInputResolutionIsRejectedWithActualShape() {
        org.tensorflow.lite.Interpreter(ModelAssets.load(context)).use { interpreter ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                ModelAssets.validate(interpreter, YoloModel.YOLO_512.assetName, 9)
            }
            assertTrue(error.message!!.contains("best_512.tflite"))
            assertTrue(error.message!!.contains("512×512"))
            assertTrue(error.message!!.contains("[1, 3, 640, 640]"))
        }
    }
}
