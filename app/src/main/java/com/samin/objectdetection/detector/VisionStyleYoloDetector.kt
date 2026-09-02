package com.samin.objectdetection.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VisionStyleYoloDetector(
    private val context: Context,
    modelName: String,
    private val confidenceThreshold: Float,
    private val nmsThreshold: Float
) : ObjectDetector {

    private val interpreter: Interpreter
    private var inputWidth = 640
    private var inputHeight = 640
    private var outputDim = 0
    private var boxCount = 0
    private var isTransposed = true
    private var outputShapeText = ""

    private var inputBuffer: ByteBuffer
    private var outputBuffer: ByteBuffer
    private var pixels: IntArray
    private var outputData: Array<FloatArray>

    private val labels: List<String> = runCatching {
        context.assets.open(DEFAULT_LABELS_NAME).bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotEmpty).toList()
        }
    }.getOrElse { cause ->
        throw IllegalStateException("Failed to load labels asset: $DEFAULT_LABELS_NAME", cause)
    }

    var maxCandidates: Int = 100
    var enableDebugImageSaving: Boolean = false
    var enableDiagnostics: Boolean = false

    private lateinit var loadedModelIdentity: ModelIdentity
    @Volatile
    private var latestFrameDiagnostics: DetectorFrameDiagnostics? = null

    @Volatile
    private var isProcessing = false

    init {
        val modelBuffer = loadModelFile(context, modelName)
        val options = Interpreter.Options().apply {
            setNumThreads(4)
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
        require(inputWidth == MODEL_INPUT_SIZE && inputHeight == MODEL_INPUT_SIZE) {
            "Model input mismatch: expected ${MODEL_INPUT_SIZE}x$MODEL_INPUT_SIZE, " +
                "actual ${inputWidth}x$inputHeight (shape=${inputShape.contentToString()})"
        }

        val outputShape = interpreter.getOutputTensor(0).shape()
        val inputType = interpreter.getInputTensor(0).dataType().toString()
        val outputType = interpreter.getOutputTensor(0).dataType().toString()
        outputShapeText = outputShape.contentToString()
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

        val modelClassCount = outputDim - YOLO_BOX_VALUE_COUNT
        require(modelClassCount > 0) {
            "Invalid YOLO output shape=$outputShapeText: output dimension must include box values and classes"
        }
        require(labels.size == modelClassCount) {
            "Model/label mismatch: model=$modelName exposes $modelClassCount classes, " +
                "$DEFAULT_LABELS_NAME contains ${labels.size} labels"
        }

        inputBuffer = ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * 3 * 4)
            .order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(1 * outputDim * boxCount * 4)
            .order(ByteOrder.nativeOrder())
        pixels = IntArray(inputWidth * inputHeight)
        outputData = Array(outputDim) { FloatArray(boxCount) }

        loadedModelIdentity = ModelIdentity(
            assetName = modelName,
            assetSizeBytes = context.assets.openFd(modelName).use { it.declaredLength },
            sha256 = calculateAssetSha256(modelName),
            inputShape = inputShape.contentToString(),
            inputType = inputType,
            outputShape = outputShape.contentToString(),
            outputType = outputType
        )

        Log.i(
            TAG,
            "modelAsset=asset://$modelName sizeBytes=${loadedModelIdentity.assetSizeBytes} " +
                "sha256=${loadedModelIdentity.sha256} inputShape=${loadedModelIdentity.inputShape} " +
                "inputType=$inputType outputShape=${loadedModelIdentity.outputShape} outputType=$outputType " +
                "labels=${labels.size}"
        )
    }

    override fun detect(bitmap: Bitmap): List<DetectionResult> {
        if (isProcessing) return emptyList()
        isProcessing = true

        val inferenceStartMs = System.currentTimeMillis()
        var scaled: Bitmap? = null
        return try {
            scaled = if (bitmap.width != inputWidth || bitmap.height != inputHeight) {
                Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            } else {
                bitmap
            }
            val modelInput = requireNotNull(scaled)

            if (enableDiagnostics) {
                Log.d(BBOX_DEBUG_TAG, "input=${modelInput.width}x${modelInput.height}")
            }

            fillInputBuffer(modelInput)

            outputBuffer.rewind()
            interpreter.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()

            parseOutput(outputBuffer, bitmap.width, bitmap.height).also { results ->
                latestFrameDiagnostics = latestFrameDiagnostics?.copy(
                    inferenceTimeMs = System.currentTimeMillis() - inferenceStartMs
                )
                if (enableDebugImageSaving) {
                    saveDebugImages(modelInput, results)
                }
                if (enableDiagnostics && results.isNotEmpty()) {
                    val top = results.maxByOrNull { it.confidence }
                    Log.d(TAG, "detected=${results.size}, top=${top?.label}, conf=${top?.confidence}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "detect error", e)
            emptyList()
        } finally {
            if (scaled != null && scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
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

        if (enableDiagnostics) {
            Log.d(
                BBOX_DEBUG_TAG,
                "outputShape=$outputShapeText outputDim=$outputDim boxCount=$boxCount transposed=$isTransposed"
            )
            logRawBoxRange()
        }

        val classCount = outputDim - YOLO_BOX_VALUE_COUNT
        val candidates = mutableListOf<DetectionResult>()
        val rawConfidences = if (enableDiagnostics) ArrayList<Float>(boxCount) else null
        var confidencePassedCount = 0
        var invalidBoxCount = 0
        var detectorAreaRejectedCount = 0

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

            rawConfidences?.add(bestScore)

            if (bestClassId < 0 || bestScore < confidenceThreshold) continue
            confidencePassedCount++

            val cx = outputData[0][i]
            val cy = outputData[1][i]
            val w = outputData[2][i]
            val h = outputData[3][i]
            val rawMin = minOf(cx, cy, w, h)
            val rawMax = maxOf(cx, cy, w, h)

            if (enableDiagnostics) {
                Log.d(
                    BBOX_DEBUG_TAG,
                    "candidate=$i rawConfidence=$bestScore rawBox=[$cx,$cy,$w,$h] rawMin=$rawMin rawMax=$rawMax"
                )
            }

            // YOLO export에 따라 0~1 또는 0~inputSize 값이 나올 수 있어 정규화 좌표로 통일
            val scaleW = if (cx > 1.1f || w > 1.1f) inputWidth.toFloat() else 1f
            val scaleH = if (cy > 1.1f || h > 1.1f) inputHeight.toFloat() else 1f

            val normalized = RectF(
                ((cx - w / 2f) / scaleW).coerceIn(0f, 1f),
                ((cy - h / 2f) / scaleH).coerceIn(0f, 1f),
                ((cx + w / 2f) / scaleW).coerceIn(0f, 1f),
                ((cy + h / 2f) / scaleH).coerceIn(0f, 1f)
            )

            if (normalized.right <= normalized.left || normalized.bottom <= normalized.top) {
                invalidBoxCount++
                if (enableDiagnostics) Log.d(BBOX_DEBUG_TAG, "candidate=$i removed=invalid_box")
                continue
            }

            val area = normalized.width() * normalized.height()
            if (area < 0.0005f || area > 0.95f) {
                detectorAreaRejectedCount++
                if (enableDiagnostics) Log.d(BBOX_DEBUG_TAG, "candidate=$i removed=detector_area areaRatio=$area")
                continue
            }

            val label = labels.getOrElse(bestClassId) { "class_$bestClassId" }
            val detection = DetectionResult(
                label = label,
                confidence = bestScore,
                left = normalized.left * sourceWidth,
                top = normalized.top * sourceHeight,
                right = normalized.right * sourceWidth,
                bottom = normalized.bottom * sourceHeight
            )
            candidates.add(detection)
        }

        val nmsInput = candidates.sortedByDescending { it.confidence }.take(maxCandidates)
        val nmsResults = nms(nmsInput)
        if (enableDiagnostics) {
            latestFrameDiagnostics = DetectorFrameDiagnostics(
                rawCandidateCount = boxCount,
                rawTopConfidences = requireNotNull(rawConfidences).sortedDescending()
                    .take(RAW_TOP_CONFIDENCE_COUNT),
                confidencePassedCount = confidencePassedCount,
                invalidBoxCount = invalidBoxCount,
                detectorAreaRejectedCount = detectorAreaRejectedCount,
                nmsInputCount = nmsInput.size,
                nmsOutputCount = nmsResults.size,
                inferenceTimeMs = 0L
            )
            Log.d(
                BOLLARD_DIAGNOSTICS_TAG,
                "stage=nms threshold=$nmsThreshold before=${nmsInput.size} after=${nmsResults.size}"
            )
            nmsResults.forEach { detection ->
                logFinalBox(detection, sourceWidth, sourceHeight)
            }
        }
        return nmsResults
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

    private fun logRawBoxRange() {
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (i in 0 until boxCount) {
            for (c in 0 until minOf(4, outputDim)) {
                val value = outputData[c][i]
                min = minOf(min, value)
                max = maxOf(max, value)
            }
        }
        Log.d(BBOX_DEBUG_TAG, "rawBoxRange min=$min max=$max")
    }

    private fun logFinalBox(detection: DetectionResult, sourceWidth: Int, sourceHeight: Int) {
        val width = detection.right - detection.left
        val height = detection.bottom - detection.top
        val centerX = detection.left + width / 2f
        val centerY = detection.top + height / 2f
        val safeWidth = sourceWidth.coerceAtLeast(1).toFloat()
        val safeHeight = sourceHeight.coerceAtLeast(1).toFloat()
        val widthRatio = width / safeWidth
        val heightRatio = height / safeHeight
        val areaRatio = width * height / (safeWidth * safeHeight)
        val centerXRatio = centerX / safeWidth
        val centerYRatio = centerY / safeHeight
        val looksNormalized = detection.left in 0f..1f &&
            detection.right in 0f..1f &&
            detection.top in 0f..1f &&
            detection.bottom in 0f..1f
        val looksPixel640 = detection.left >= 0f &&
            detection.right <= inputWidth.toFloat() &&
            detection.top >= 0f &&
            detection.bottom <= inputHeight.toFloat()
        val outOfInput = detection.left < 0f ||
            detection.top < 0f ||
            detection.right > inputWidth.toFloat() ||
            detection.bottom > inputHeight.toFloat()

        Log.d(
            BOLLARD_DIAGNOSTICS_TAG,
            "finalBox left=${detection.left} top=${detection.top} right=${detection.right} bottom=${detection.bottom} " +
                "bboxWidthRatio=$widthRatio bboxHeightRatio=$heightRatio bboxAreaRatio=$areaRatio " +
                "centerXRatio=$centerXRatio centerYRatio=$centerYRatio nmsPassed=true " +
                "looksNormalized=$looksNormalized looksPixel640=$looksPixel640 outOfInput=$outOfInput " +
                "label=${detection.label} conf=${detection.confidence}"
        )
    }

    private fun saveDebugImages(inputBitmap: Bitmap, detections: List<DetectionResult>) {
        val dir = File(context.getExternalFilesDir(null), "debug_roi")
        if (!dir.exists()) {
            val created = dir.mkdirs()
            Log.d(BBOX_DEBUG_TAG, "debug dir created=$created path=${dir.absolutePath}")
        }

        val timestamp = DEBUG_DATE_FORMAT.get()!!.format(Date())
        val inputFile = File(dir, "debug_input_$timestamp.jpg")
        val resultFile = File(dir, "debug_result_existing_box_$timestamp.jpg")

        try {
            FileOutputStream(inputFile).use { out ->
                inputBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            Log.d(BBOX_DEBUG_TAG, "saved debug input=${inputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(BBOX_DEBUG_TAG, "save debug input failed path=${inputFile.absolutePath}", e)
        }

        try {
            val resultBitmap = inputBitmap.copy(Bitmap.Config.ARGB_8888, true)
            drawExistingDetections(resultBitmap, detections)
            FileOutputStream(resultFile).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            Log.d(BBOX_DEBUG_TAG, "saved debug result=${resultFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(BBOX_DEBUG_TAG, "save debug result failed path=${resultFile.absolutePath}", e)
        }
    }

    private fun drawExistingDetections(bitmap: Bitmap, detections: List<DetectionResult>) {
        val canvas = Canvas(bitmap)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.RED
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            textSize = 20f
            color = Color.WHITE
        }
        val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(190, 0, 0, 0)
        }

        detections.forEach { detection ->
            val box = RectF(detection.left, detection.top, detection.right, detection.bottom)
            canvas.drawRect(box, boxPaint)

            val text = "${detection.label} ${String.format(Locale.US, "%.2f", detection.confidence)}"
            val textWidth = textPaint.measureText(text)
            val textHeight = textPaint.textSize
            val labelLeft = box.left.coerceIn(0f, bitmap.width.toFloat())
            val labelTop = (box.top - textHeight - 8f).coerceAtLeast(0f)
            val labelBottom = (labelTop + textHeight + 8f).coerceAtMost(bitmap.height.toFloat())
            canvas.drawRect(
                labelLeft,
                labelTop,
                (labelLeft + textWidth + 12f).coerceAtMost(bitmap.width.toFloat()),
                labelBottom,
                labelBgPaint
            )
            canvas.drawText(text, labelLeft + 6f, labelBottom - 6f, textPaint)
        }
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

    override fun modelIdentity(): ModelIdentity = loadedModelIdentity

    override fun frameDiagnostics(): DetectorFrameDiagnostics? = latestFrameDiagnostics

    private fun calculateAssetSha256(assetName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(assetName).use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val DEFAULT_MODEL_NAME = "best_float32.tflite"
        private const val DEFAULT_LABELS_NAME = "labels.txt"
        private const val MODEL_INPUT_SIZE = 640
        private const val YOLO_BOX_VALUE_COUNT = 4
        private const val RAW_TOP_CONFIDENCE_COUNT = 5
        private const val HASH_BUFFER_SIZE = 64 * 1024
        private const val TAG = "VisionStyleYoloDetector"
        private const val BBOX_DEBUG_TAG = "BBoxDebug"
        private const val BOLLARD_DIAGNOSTICS_TAG = "BollardDiagnostics"
        private val DEBUG_DATE_FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        }
    }
}
