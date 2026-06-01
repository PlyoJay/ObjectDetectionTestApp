package com.samin.objectdetection.detector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.samin.objectdetection.camera.DetectionResult
import com.samin.objectdetection.camera.ObjectDetector
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class TfliteYoloDetector(
    private val context: Context,
    modelName: String = "yolo11n_float32.tflite"
) : ObjectDetector {

    private val interpreter: Interpreter

    private var inputWidth = 640
    private var inputHeight = 640

    private var outputDim = 0
    private var boxCount = 0
    private var isTransposed = true

    private var inputBuffer: ByteBuffer
    private var outputBuffer: ByteBuffer
    private var pixels: IntArray

    private val labels: List<String> = context.assets.open("labels.txt")
        .bufferedReader()
        .readLines()
        .filter { it.isNotBlank() }

    private val confidenceThreshold = 0.55f
    private val nmsThreshold = 0.35f
    private val maxCandidates = 100

    init {
        val modelBuffer = loadModelFile(context, modelName)

        val options = Interpreter.Options().apply {
            setNumThreads(4)
            useNNAPI = false
        }

        interpreter = Interpreter(modelBuffer, options)

        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()

        if (inputShape.size == 4) {
            if (inputShape[1] == 3) {
                inputHeight = inputShape[2]
                inputWidth = inputShape[3]
            } else {
                inputHeight = inputShape[1]
                inputWidth = inputShape[2]
            }
        }

        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape()

        if (outputShape.size != 3) {
            throw IllegalStateException("Unsupported YOLO output shape: ${outputShape.contentToString()}")
        }

        /*
            YOLO TFLite output 예시

            [1, 84, 8400]
            - outputDim = 84
            - boxCount = 8400
            - isTransposed = true

            [1, 8400, 84]
            - outputDim = 84
            - boxCount = 8400
            - isTransposed = false
        */
        if (outputShape[1] < outputShape[2]) {
            isTransposed = true
            outputDim = outputShape[1]
            boxCount = outputShape[2]
        } else {
            isTransposed = false
            boxCount = outputShape[1]
            outputDim = outputShape[2]
        }

        inputBuffer = ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * 3 * 4)
            .order(ByteOrder.nativeOrder())

        outputBuffer = ByteBuffer.allocateDirect(1 * outputDim * boxCount * 4)
            .order(ByteOrder.nativeOrder())

        pixels = IntArray(inputWidth * inputHeight)

        Log.d(TAG, "model=$modelName")
        Log.d(TAG, "input shape=${inputShape.contentToString()}, input=${inputWidth}x$inputHeight")
        Log.d(TAG, "output shape=${outputShape.contentToString()}, outputDim=$outputDim, boxCount=$boxCount, transposed=$isTransposed")
        Log.d(TAG, "labels=${labels.size}")
    }

    override fun detect(bitmap: Bitmap): List<DetectionResult> {
        try {
            val resized = if (bitmap.width != inputWidth || bitmap.height != inputHeight) {
                Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            } else {
                bitmap
            }

            bitmapToInputBuffer(resized)

            outputBuffer.rewind()
            interpreter.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()

            val results = parseOutput(outputBuffer)

            if (results.isNotEmpty()) {
                val top = results.first()
                Log.d(
                    TAG,
                    "detected=${results.size}, top=${top.label}, conf=${top.confidence}, box=${top.left},${top.top},${top.right},${top.bottom}"
                )
            }

            return results

        } catch (e: Exception) {
            Log.e(TAG, "detect error", e)
            return emptyList()
        }
    }

    private fun bitmapToInputBuffer(bitmap: Bitmap) {
        inputBuffer.rewind()

        bitmap.getPixels(
            pixels,
            0,
            inputWidth,
            0,
            0,
            inputWidth,
            inputHeight
        )

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }

        inputBuffer.rewind()
    }

    private fun parseOutput(buffer: ByteBuffer): List<DetectionResult> {
        val data = Array(outputDim) { FloatArray(boxCount) }

        if (isTransposed) {
            // [1, outputDim, boxCount]
            for (c in 0 until outputDim) {
                for (i in 0 until boxCount) {
                    if (buffer.hasRemaining()) {
                        data[c][i] = buffer.float
                    }
                }
            }
        } else {
            // [1, boxCount, outputDim]
            for (i in 0 until boxCount) {
                for (c in 0 until outputDim) {
                    if (buffer.hasRemaining()) {
                        data[c][i] = buffer.float
                    }
                }
            }
        }

        val classCount = outputDim - 4
        val candidates = mutableListOf<DetectionResult>()

        for (i in 0 until boxCount) {
            val cx = data[0][i]
            val cy = data[1][i]
            val w = data[2][i]
            val h = data[3][i]

            var bestClassId = -1
            var bestScore = 0f

            for (c in 0 until classCount) {
                val score = data[4 + c][i]

                if (score > bestScore) {
                    bestScore = score
                    bestClassId = c
                }
            }

            if (bestClassId < 0) continue
            if (bestScore < confidenceThreshold) continue

            val rawLeft = cx - w / 2f
            val rawTop = cy - h / 2f
            val rawRight = cx + w / 2f
            val rawBottom = cy + h / 2f

            /*
                모델에 따라 bbox가
                0~1 정규화 좌표로 나오거나,
                0~640 픽셀 좌표로 나올 수 있음.
            */
            val isNormalized = rawRight <= 1.5f && rawBottom <= 1.5f

            val left = if (isNormalized) rawLeft * inputWidth else rawLeft
            val top = if (isNormalized) rawTop * inputHeight else rawTop
            val right = if (isNormalized) rawRight * inputWidth else rawRight
            val bottom = if (isNormalized) rawBottom * inputHeight else rawBottom

            val clampedLeft = left.coerceIn(0f, inputWidth.toFloat())
            val clampedTop = top.coerceIn(0f, inputHeight.toFloat())
            val clampedRight = right.coerceIn(0f, inputWidth.toFloat())
            val clampedBottom = bottom.coerceIn(0f, inputHeight.toFloat())

            if (clampedRight <= clampedLeft) continue
            if (clampedBottom <= clampedTop) continue

            val boxWidth = clampedRight - clampedLeft
            val boxHeight = clampedBottom - clampedTop
            val boxArea = boxWidth * boxHeight
            val imageArea = inputWidth * inputHeight

            // 너무 작은 박스 / 화면 전체를 덮는 박스 제거
            if (boxArea < imageArea * 0.0005f) continue
            if (boxArea > imageArea * 0.95f) continue

            val label = labels.getOrElse(bestClassId) {
                "class_$bestClassId"
            }

            candidates.add(
                DetectionResult(
                    label = label,
                    confidence = bestScore,
                    left = clampedLeft,
                    top = clampedTop,
                    right = clampedRight,
                    bottom = clampedBottom
                )
            )
        }

        val limitedCandidates = candidates
            .sortedByDescending { it.confidence }
            .take(maxCandidates)

        return nms(limitedCandidates, nmsThreshold)
    }

    private fun nms(
        detections: List<DetectionResult>,
        iouThreshold: Float
    ): List<DetectionResult> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<DetectionResult>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            selected.add(best)

            val iterator = sorted.iterator()

            while (iterator.hasNext()) {
                val other = iterator.next()

                // 서로 다른 클래스는 제거하지 않음
                if (best.label != other.label) {
                    continue
                }

                if (iou(best, other) > iouThreshold) {
                    iterator.remove()
                }
            }
        }

        return selected
    }

    private fun iou(a: DetectionResult, b: DetectionResult): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)

        val intersectionWidth = maxOf(0f, right - left)
        val intersectionHeight = maxOf(0f, bottom - top)
        val intersection = intersectionWidth * intersectionHeight

        val areaA = maxOf(0f, a.right - a.left) * maxOf(0f, a.bottom - a.top)
        val areaB = maxOf(0f, b.right - b.left) * maxOf(0f, b.bottom - b.top)

        return intersection / (areaA + areaB - intersection + 1e-6f)
    }

    private fun loadModelFile(context: Context, modelName: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)

        FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            return inputStream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                fileDescriptor.startOffset,
                fileDescriptor.declaredLength
            )
        }
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        private const val TAG = "TfliteYoloDetector"
    }
}