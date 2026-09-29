package com.samin.objectdetection.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.samin.objectdetection.camera.SizeFilterMode
import com.samin.objectdetection.camera.YoloResizeMode
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

class VisionStyleYoloDetector(
    private val context: Context,
    modelName: String,
    private val confidenceThreshold: Float,
    private val nmsThreshold: Float,
    private val sizeFilterMode: SizeFilterMode = SizeFilterMode.NORMAL,
    private val resizeMode: YoloResizeMode = YoloResizeMode.STRETCH,
    interpreterThreadCount: Int = 4,
    private val maxCandidates: Int = 100,
    private val debugRecorder: DetectionDebugRecorder? = null
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
    private lateinit var reusableInputBitmap: Bitmap
    private lateinit var reusableInputCanvas: Canvas
    private val inputDestination = RectF()
    private var cachedTransform: YoloResizeTransform? = null
    private val resizePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val labels: List<String> = runCatching {
        context.assets.open(DEFAULT_LABELS_NAME).bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotEmpty).toList()
        }
    }.getOrElse { cause ->
        throw IllegalStateException("Failed to load labels asset: $DEFAULT_LABELS_NAME", cause)
    }

    var enableDiagnostics: Boolean = false

    private lateinit var loadedModelIdentity: ModelIdentity
    @Volatile
    private var latestFrameDiagnostics: DetectorFrameDiagnostics? = null
    private var debugFrame: DetectionDebugFrame? = null
    override fun setDebugFrame(frame: DetectionDebugFrame?) { debugFrame = frame }

    init {
        val modelBuffer = loadModelFile(context, modelName)
        val options = Interpreter.Options().apply {
            setNumThreads(interpreterThreadCount.coerceAtLeast(1))
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
        reusableInputBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        reusableInputCanvas = Canvas(reusableInputBitmap)

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
        latestFrameDiagnostics = null
        val detectorStartNs = SystemClock.elapsedRealtimeNanos()
        return try {
            val resizeStartNs = SystemClock.elapsedRealtimeNanos()
            val transform = cachedTransform?.takeIf {
                it.mode == resizeMode && it.sourceWidth == bitmap.width && it.sourceHeight == bitmap.height
            } ?: YoloResizeTransform.calculate(
                resizeMode, bitmap.width, bitmap.height, inputWidth, inputHeight
            ).also { cachedTransform = it }
            val modelInput = if (bitmap.width != inputWidth || bitmap.height != inputHeight) {
                if (resizeMode == YoloResizeMode.LETTERBOX) {
                    reusableInputCanvas.drawColor(LETTERBOX_PADDING_COLOR)
                    inputDestination.set(
                        transform.padLeft.toFloat(), transform.padTop.toFloat(),
                        (transform.padLeft + transform.scaledWidth).toFloat(),
                        (transform.padTop + transform.scaledHeight).toFloat()
                    )
                    reusableInputCanvas.drawBitmap(bitmap, null, inputDestination, resizePaint)
                } else {
                    reusableInputCanvas.drawBitmap(bitmap, null, INPUT_RECT, resizePaint)
                }
                reusableInputBitmap
            } else {
                bitmap
            }
            val resizeTimeMs = elapsedMs(resizeStartNs)

            if (enableDiagnostics) {
                Log.d(BBOX_DEBUG_TAG, "input=${modelInput.width}x${modelInput.height}")
            }

            val inputBufferStartNs = SystemClock.elapsedRealtimeNanos()
            debugFrame?.let { frame ->
                val resizeDescription = if (resizeMode == YoloResizeMode.LETTERBOX) {
                    "scaled=${transform.scaledWidth}x${transform.scaledHeight} " +
                        "scale=${minOf(transform.scaleX, transform.scaleY)} " +
                        "scaleX=${transform.scaleX} scaleY=${transform.scaleY} " +
                        "padLeft=${transform.padLeft} padTop=${transform.padTop} " +
                        "padRight=${transform.padRight} padBottom=${transform.padBottom} " +
                        "paddingRgb=114,114,114"
                } else {
                    "scaleX=${transform.scaleX} scaleY=${transform.scaleY}"
                }
                debugRecorder?.modelInput(frame,
                    "input=${inputWidth}x$inputHeight modelInput=${inputWidth}x$inputHeight " +
                        "source=${bitmap.width}x${bitmap.height} resizeMode=$resizeMode " +
                        "letterbox=${resizeMode == YoloResizeMode.LETTERBOX} $resizeDescription " +
                        "channelOrder=RGB normalization=channel/255.0f inputRange=0..1 " +
                        "tensorType=${loadedModelIdentity.inputType} inputShape=${loadedModelIdentity.inputShape} " +
                        "bufferLayout=NHWC_interleaved bufferWrite=putFloat_nativeOrder " +
                        "outputShape=$outputShapeText outputType=${loadedModelIdentity.outputType} " +
                        "parser=xywh_plus_class_scores_no_objectness_no_sigmoid transposed=$isTransposed " +
                        "coordinateScale=per_box_axis_1.1_heuristic modelSha256=${loadedModelIdentity.sha256}")
            }
            fillInputBuffer(modelInput)
            val inputBufferTimeMs = elapsedMs(inputBufferStartNs)

            outputBuffer.rewind()
            val inferenceStartNs = SystemClock.elapsedRealtimeNanos()
            interpreter.run(inputBuffer, outputBuffer)
            val inferenceTimeMs = elapsedMs(inferenceStartNs)
            outputBuffer.rewind()

            parseOutput(
                buffer = outputBuffer,
                transform = transform,
                resizeTimeMs = resizeTimeMs,
                inputBufferTimeMs = inputBufferTimeMs,
                inferenceTimeMs = inferenceTimeMs,
                detectorStartNs = detectorStartNs
            ).also { results ->
                debugFrame?.let { debugRecorder?.saveInput(it, modelInput, results) }
                if (enableDiagnostics && results.isNotEmpty()) {
                    val top = results.maxByOrNull { it.confidence }
                    Log.d(TAG, "detected=${results.size}, top=${top?.label}, conf=${top?.confidence}")
                }
            }
        } catch (e: Exception) {
            debugFrame?.line("[DETECTOR_ERROR] frame=${debugFrame?.id} error=$e")
            Log.e(TAG, "detect error", e)
            emptyList()
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

    private fun parseOutput(
        buffer: ByteBuffer,
        transform: YoloResizeTransform,
        resizeTimeMs: Long,
        inputBufferTimeMs: Long,
        inferenceTimeMs: Long,
        detectorStartNs: Long
    ): List<DetectionResult> {
        val sourceWidth = transform.sourceWidth
        val sourceHeight = transform.sourceHeight
        val postprocessStartNs = SystemClock.elapsedRealtimeNanos()
        val outputCopyStartNs = SystemClock.elapsedRealtimeNanos()
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
        val outputCopyTimeMs = elapsedMs(outputCopyStartNs)

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
        val candidateScanStartNs = SystemClock.elapsedRealtimeNanos()

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

            debugFrame?.raw(i, bestClassId, labels.getOrElse(bestClassId) { "none" }, bestScore,
                outputData[0][i], outputData[1][i], outputData[2][i], outputData[3][i], confidenceThreshold)

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

            val left = transform.sourceX(normalized.left)
            val top = transform.sourceY(normalized.top)
            val right = transform.sourceX(normalized.right)
            val bottom = transform.sourceY(normalized.bottom)

            val diagnosticBox = if (debugFrame != null) DetectionResult(
                label = labels.getOrElse(bestClassId) { "class_$bestClassId" }, confidence = bestScore,
                left = left, top = top, right = right, bottom = bottom
            ) else null

            if (right <= left || bottom <= top) {
                invalidBoxCount++
                diagnosticBox?.let { debugFrame?.filtered(it, "INVALID_BOX", "roi_pixels") }
                if (enableDiagnostics) Log.d(BBOX_DEBUG_TAG, "candidate=$i removed=invalid_box")
                continue
            }

            val area = if (resizeMode == YoloResizeMode.STRETCH) {
                normalized.width() * normalized.height()
            } else {
                (right - left) * (bottom - top) / (sourceWidth.toFloat() * sourceHeight)
            }
            val label = labels.getOrElse(bestClassId) { "class_$bestClassId" }
            val detectorAreaRange = when (sizeFilterMode) {
                SizeFilterMode.DISABLED -> null
                SizeFilterMode.RELAXED -> RELAXED_MIN_AREA_RATIO..RELAXED_MAX_AREA_RATIO
                SizeFilterMode.NORMAL -> NORMAL_MIN_AREA_RATIO..NORMAL_MAX_AREA_RATIO
            }
            if (detectorAreaRange != null && area !in detectorAreaRange) {
                detectorAreaRejectedCount++
                diagnosticBox?.let { debugFrame?.filtered(it, "SIZE_DETECTOR", "roi_pixels") }
                if (enableDiagnostics) {
                    val boxWidth = right - left
                    val boxHeight = bottom - top
                    Log.d(
                        SIZE_FILTER_TAG,
                        "removed stage=detector mode=$sizeFilterMode class=$label confidence=$bestScore " +
                            "bboxWidth=$boxWidth bboxHeight=$boxHeight bboxArea=${boxWidth * boxHeight} " +
                            "screenAreaRatio=$area reason=area_outside_${detectorAreaRange.start}_to_${detectorAreaRange.endInclusive}"
                    )
                }
                continue
            }

            val detection = DetectionResult(
                label = label,
                confidence = bestScore,
                left = left,
                top = top,
                right = right,
                bottom = bottom
            )
            candidates.add(detection)
        }
        val candidateScanTimeMs = elapsedMs(candidateScanStartNs)
        debugFrame?.summary(boxCount)

        val nmsInput = candidates.sortedByDescending { it.confidence }.take(maxCandidates.coerceAtLeast(1))
        if (debugFrame != null) candidates.filterNot { it in nmsInput }.forEach {
            debugFrame?.filtered(it, "MAX_CANDIDATES", "roi_pixels")
        }
        val nmsStartNs = SystemClock.elapsedRealtimeNanos()
        val nmsResults = nms(nmsInput)
        debugFrame?.let { frame ->
            nmsResults.forEach { detection ->
                frame.line("[YOLO_RESULT] frame=${frame.id} class=${detection.label} confidence=${detection.confidence} " +
                    "bbox=[${detection.left},${detection.top},${detection.right},${detection.bottom}] bboxSpace=roi_pixels")
            }
        }
        val nmsTimeMs = elapsedMs(nmsStartNs)
        latestFrameDiagnostics = DetectorFrameDiagnostics(
            rawCandidateCount = boxCount,
            rawTopConfidences = rawConfidences?.sortedDescending()?.take(RAW_TOP_CONFIDENCE_COUNT).orEmpty(),
            confidencePassedCount = confidencePassedCount,
            invalidBoxCount = invalidBoxCount,
            detectorAreaRejectedCount = detectorAreaRejectedCount,
            nmsInputCount = nmsInput.size,
            nmsOutputCount = nmsResults.size,
            preprocessTimeMs = resizeTimeMs + inputBufferTimeMs,
            resizeTimeMs = resizeTimeMs,
            inputBufferTimeMs = inputBufferTimeMs,
            inferenceTimeMs = inferenceTimeMs,
            outputCopyTimeMs = outputCopyTimeMs,
            candidateScanTimeMs = candidateScanTimeMs,
            nmsTimeMs = nmsTimeMs,
            postprocessTimeMs = elapsedMs(postprocessStartNs),
            detectorTotalTimeMs = elapsedMs(detectorStartNs)
        )
        if (enableDiagnostics) {
            Log.d(
                BOLLARD_DIAGNOSTICS_TAG,
                "stage=nms threshold=$nmsThreshold before=${nmsInput.size} after=${nmsResults.size} " +
                    "sizeFilterMode=$sizeFilterMode detectorSizeRejected=$detectorAreaRejectedCount"
            )
            nmsResults.forEach { detection ->
                logFinalBox(detection, sourceWidth, sourceHeight)
            }
        }
        return nmsResults
    }

    private fun nms(items: List<DetectionResult>): List<DetectionResult> {
        val result = mutableListOf<DetectionResult>()
        // parseOutput already supplies confidence-descending items.
        val sorted = items.toMutableList()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)
            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                if (best.label == other.label && iou(best, other) > nmsThreshold) {
                    debugFrame?.filtered(other, "NMS", "roi_pixels")
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

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fd = context.assets.openFd(modelName)
        FileInputStream(fd.fileDescriptor).use { input ->
            return input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    override fun close() {
        reusableInputCanvas.setBitmap(null)
        reusableInputBitmap.recycle()
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

    private fun elapsedMs(startNs: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L

    companion object {
        private const val DEFAULT_MODEL_NAME = "best_float32.tflite"
        private const val DEFAULT_LABELS_NAME = "labels.txt"
        private const val MODEL_INPUT_SIZE = 640
        private const val YOLO_BOX_VALUE_COUNT = 4
        private const val RAW_TOP_CONFIDENCE_COUNT = 5
        private const val HASH_BUFFER_SIZE = 64 * 1024
        private const val NORMAL_MIN_AREA_RATIO = 0.0005f
        private const val NORMAL_MAX_AREA_RATIO = 0.95f
        private const val RELAXED_MIN_AREA_RATIO = 0.00005f
        private const val RELAXED_MAX_AREA_RATIO = 0.99f
        private const val TAG = "VisionStyleYoloDetector"
        private const val BBOX_DEBUG_TAG = "BBoxDebug"
        private const val SIZE_FILTER_TAG = "DetectionSizeFilter"
        private const val BOLLARD_DIAGNOSTICS_TAG = "BollardDiagnostics"
        private val LETTERBOX_PADDING_COLOR = Color.rgb(114, 114, 114)
        private val INPUT_RECT = RectF(0f, 0f, MODEL_INPUT_SIZE.toFloat(), MODEL_INPUT_SIZE.toFloat())
    }
}
