package com.samin.objectdetection.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.samin.objectdetection.camera.DetectionResult
import com.samin.objectdetection.camera.ObjectDetector
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class VisionStyleYoloDetector(
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
    private var outputData: Array<FloatArray>

    private val labels: List<String> = runCatching {
        context.assets.open("labels.txt").bufferedReader().readLines().filter { it.isNotBlank() }
    }.getOrDefault(listOf("object"))

    var confidenceThreshold: Float = 0.25f
    var nmsThreshold: Float = 0.45f
    var maxCandidates: Int = 100

    @Volatile
    private var isProcessing = false

    init {
        val modelBuffer = loadModelFile(context, modelName)
        val options = Interpreter.Options().apply {
            setNumThreads(4)
            useNNAPI = false
        }
        interpreter = Interpreter(modelBuffer, options)

        val inputShape = interpreter.getInputTensor(0).shape()
        if (inputShape.size == 4) {
            if (inputShape[1] == 3) {
                inputHeight = inputShape[2]
                inputWidth = inputShape[3]
            } else {
                inputHeight = inputShape[1]
                inputWidth = inputShape[2]
            }
        }

        val outputShape = interpreter.getOutputTensor(0).shape()
        if (outputShape.size != 3) {
            throw IllegalStateException("Unsupported output shape=${outputShape.contentToString()}")
        }

        if (outputShape[1] > outputShape[2]) {
            isTransposed = false
            boxCount = outputShape[1]
            outputDim = outputShape[2]
        } else {
            isTransposed = true
            outputDim = outputShape[1]
            boxCount = outputShape[2]
        }

        inputBuffer = ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * 3 * 4)
            .order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(1 * outputDim * boxCount * 4)
            .order(ByteOrder.nativeOrder())
        pixels = IntArray(inputWidth * inputHeight)
        outputData = Array(outputDim) { FloatArray(boxCount) }

        Log.d(TAG, "model=$modelName")
        Log.d(TAG, "input=${inputShape.contentToString()}, ${inputWidth}x$inputHeight")
        Log.d(TAG, "output=${outputShape.contentToString()}, dim=$outputDim, boxes=$boxCount, transposed=$isTransposed")
        Log.d(TAG, "labels=${labels.size}")
    }

    override fun detect(bitmap: Bitmap): List<DetectionResult> {
        if (isProcessing) return emptyList()
        isProcessing = true

        return try {
            val scaled = if (bitmap.width != inputWidth || bitmap.height != inputHeight) {
                Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            } else {
                bitmap
            }

            fillInputBuffer(scaled)

            outputBuffer.rewind()
            interpreter.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()

            parseOutput(outputBuffer, bitmap.width, bitmap.height).also { results ->
                if (results.isNotEmpty()) {
                    val top = results.maxByOrNull { it.confidence }
                    Log.d(TAG, "detected=${results.size}, top=${top?.label}, conf=${top?.confidence}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "detect error", e)
            emptyList()
        } finally {
            isProcessing = false
        }
    }

    private fun fillInputBuffer(bitmap: Bitmap) {
        inputBuffer.rewind()
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        for (pixel in pixels) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)
        }
        inputBuffer.rewind()
    }

    private fun parseOutput(buffer: ByteBuffer, sourceWidth: Int, sourceHeight: Int): List<DetectionResult> {
        buffer.rewind()
        if (isTransposed) {
            for (c in 0 until outputDim) {
                for (i in 0 until boxCount) {
                    if (buffer.hasRemaining()) outputData[c][i] = buffer.float
                }
            }
        } else {
            for (i in 0 until boxCount) {
                for (c in 0 until outputDim) {
                    if (buffer.hasRemaining()) outputData[c][i] = buffer.float
                }
            }
        }

        val classCount = outputDim - 4
        val candidates = mutableListOf<DetectionResult>()

        for (i in 0 until boxCount) {
            var bestClassId = -1
            var bestScore = 0f

            for (c in 0 until classCount) {
                val score = outputData[4 + c][i]
                if (score > bestScore) {
                    bestScore = score
                    bestClassId = c
                }
            }

            if (bestClassId < 0 || bestScore < confidenceThreshold) continue

            val cx = outputData[0][i]
            val cy = outputData[1][i]
            val w = outputData[2][i]
            val h = outputData[3][i]

            // YOLO export에 따라 0~1 또는 0~inputSize 값이 나올 수 있어 정규화 좌표로 통일
            val scaleW = if (cx > 1.1f || w > 1.1f) inputWidth.toFloat() else 1f
            val scaleH = if (cy > 1.1f || h > 1.1f) inputHeight.toFloat() else 1f

            val normalized = RectF(
                ((cx - w / 2f) / scaleW).coerceIn(0f, 1f),
                ((cy - h / 2f) / scaleH).coerceIn(0f, 1f),
                ((cx + w / 2f) / scaleW).coerceIn(0f, 1f),
                ((cy + h / 2f) / scaleH).coerceIn(0f, 1f)
            )

            if (normalized.right <= normalized.left || normalized.bottom <= normalized.top) continue

            val area = normalized.width() * normalized.height()
            if (area < 0.0005f || area > 0.95f) continue

            val label = labels.getOrElse(bestClassId) { "class_$bestClassId" }
            candidates.add(
                DetectionResult(
                    label = label,
                    confidence = bestScore,
                    left = normalized.left * sourceWidth,
                    top = normalized.top * sourceHeight,
                    right = normalized.right * sourceWidth,
                    bottom = normalized.bottom * sourceHeight
                )
            )
        }

        return nms(candidates.sortedByDescending { it.confidence }.take(maxCandidates))
    }

    private fun nms(items: List<DetectionResult>): List<DetectionResult> {
        val result = mutableListOf<DetectionResult>()
        val sorted = items.sortedByDescending { it.confidence }.toMutableList()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)
            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                if (best.label == other.label && iou(best, other) > nmsThreshold) {
                    iterator.remove()
                }
            }
        }
        return result
    }

    private fun iou(a: DetectionResult, b: DetectionResult): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val areaA = maxOf(0f, a.right - a.left) * maxOf(0f, a.bottom - a.top)
        val areaB = maxOf(0f, b.right - b.left) * maxOf(0f, b.bottom - b.top)
        return inter / (areaA + areaB - inter + 1e-6f)
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fd = context.assets.openFd(modelName)
        FileInputStream(fd.fileDescriptor).use { input ->
            return input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        private const val TAG = "VisionStyleYoloDetector"
    }
}
