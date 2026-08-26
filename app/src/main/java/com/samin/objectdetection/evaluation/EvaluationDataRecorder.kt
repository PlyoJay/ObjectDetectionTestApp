package com.samin.objectdetection.evaluation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.Log
import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.detector.DetectionResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Locale

class EvaluationDataRecorder(
    private val context: Context,
    private val detectionConfig: DetectionConfig = DetectionConfig(),
    private val modelName: String = "yolo11n_float32.tflite",
    private val detectorType: String = "VisionStyleYoloDetector"
) {
    private val lock = Any()
    private var detectionsWriter: BufferedWriter? = null
    private var activeDetectionsFile: File? = null

    val capturesDir: File
        get() = File(rootDir, "captures").also { it.mkdirs() }

    val recordingsDir: File
        get() = File(rootDir, "recordings").also { it.mkdirs() }

    private val rootDir: File
        get() = File(context.getExternalFilesDir(null), ROOT_DIR).also { it.mkdirs() }

    fun saveCapture(snapshot: DetectionFrameSnapshot, timestamp: String): CaptureFiles {
        val imageFile = File(capturesDir, "$timestamp.jpg")
        val jsonFile = File(capturesDir, "$timestamp.json")
        val overlayFile = File(capturesDir, "${timestamp}_overlay.jpg")

        saveJpeg(snapshot.bitmap, imageFile)
        jsonFile.writeText(
            buildFrameJson(snapshot, timestamp).toString(2),
            Charsets.UTF_8
        )
        val overlayBitmap = drawOverlay(snapshot)
        try {
            saveJpeg(overlayBitmap, overlayFile)
        } finally {
            overlayBitmap.recycle()
        }

        return CaptureFiles(imageFile, jsonFile, overlayFile)
    }

    fun startRecordingLog(timestamp: String): File {
        synchronized(lock) {
            closeRecordingLogLocked()
            val file = File(recordingsDir, "${timestamp}_detections.jsonl")
            detectionsWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8))
            activeDetectionsFile = file
            return file
        }
    }

    fun appendRecordingDetections(snapshot: DetectionFrameSnapshot) {
        synchronized(lock) {
            val writer = detectionsWriter ?: return
            try {
                writer.write(buildFrameJson(snapshot, snapshot.frameTimestampMs.toString()).toString())
                writer.newLine()
                writer.flush()
            } catch (e: Exception) {
                Log.e(TAG, "append recording detections failed", e)
            }
        }
    }

    fun stopRecordingLog(): File? {
        synchronized(lock) {
            val file = activeDetectionsFile
            closeRecordingLogLocked()
            return file
        }
    }

    fun close() {
        synchronized(lock) {
            closeRecordingLogLocked()
        }
    }

    private fun closeRecordingLogLocked() {
        try {
            detectionsWriter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "close recording detections failed", e)
        } finally {
            detectionsWriter = null
            activeDetectionsFile = null
        }
    }

    private fun saveJpeg(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
    }

    private fun drawOverlay(snapshot: DetectionFrameSnapshot): Bitmap {
        val overlayBitmap = snapshot.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(overlayBitmap)
        snapshot.detections.forEach { detection ->
            val left = detection.left.coerceIn(0f, snapshot.imageWidth.toFloat())
            val top = detection.top.coerceIn(0f, snapshot.imageHeight.toFloat())
            val right = detection.right.coerceIn(0f, snapshot.imageWidth.toFloat())
            val bottom = detection.bottom.coerceIn(0f, snapshot.imageHeight.toFloat())
            canvas.drawRect(left, top, right, bottom, boxPaint)
            val label = "${detection.label} ${String.format(Locale.US, "%.2f", detection.confidence)}"
            canvas.drawText(label, left, (top - 8f).coerceAtLeast(24f), labelPaint)
        }
        return overlayBitmap
    }

    private fun buildFrameJson(snapshot: DetectionFrameSnapshot, id: String): JSONObject {
        val summary = snapshot.toFrameSummary()
        return JSONObject()
            .put("id", id)
            .put("timestampMs", snapshot.frameTimestampMs)
            .put("frameTimestampMs", snapshot.frameTimestampMs)
            .put("frameWidth", snapshot.imageWidth)
            .put("frameHeight", snapshot.imageHeight)
            .put("imageWidth", snapshot.imageWidth)
            .put("imageHeight", snapshot.imageHeight)
            .put("roiApplied", snapshot.roiApplied)
            .put("roiLeft", snapshot.roi?.left ?: JSONObject.NULL)
            .put("roiTop", snapshot.roi?.top ?: JSONObject.NULL)
            .put("roiRight", snapshot.roi?.right ?: JSONObject.NULL)
            .put("roiBottom", snapshot.roi?.bottom ?: JSONObject.NULL)
            .put("roi", snapshot.roi?.toJson() ?: JSONObject.NULL)
            .put("coordinateSpace", "original_image")
            .put("metadata", buildMetadataJson())
            .put("frameSummary", summary.toJson())
            .put("detections", JSONArray(snapshot.evaluationDetections.mapIndexed { index, detection ->
                detection.toJson(snapshot, summary, index)
            }))
    }

    private fun DetectionResult.toJson(
        snapshot: DetectionFrameSnapshot,
        summary: EvaluationFrameSummary,
        index: Int
    ): JSONObject {
        val detectionId = buildDetectionId(snapshot.frameTimestampMs, index, label)
        return JSONObject()
            .put("detectionIndex", index)
            .put("detectionId", detectionId)
            .put("timestampMs", snapshot.frameTimestampMs)
            .put("frameWidth", snapshot.imageWidth)
            .put("frameHeight", snapshot.imageHeight)
            .put("roiApplied", snapshot.roiApplied)
            .put("roiLeft", snapshot.roi?.left ?: JSONObject.NULL)
            .put("roiTop", snapshot.roi?.top ?: JSONObject.NULL)
            .put("roiRight", snapshot.roi?.right ?: JSONObject.NULL)
            .put("roiBottom", snapshot.roi?.bottom ?: JSONObject.NULL)
            .put("label", label)
            .put("confidence", confidence.toDouble())
            .put("frameTimestampMs", frameTimestampMs)
            .put("imageWidth", snapshot.imageWidth)
            .put("imageHeight", snapshot.imageHeight)
            .put("left", left.toDouble())
            .put("top", top.toDouble())
            .put("right", right.toDouble())
            .put("bottom", bottom.toDouble())
            .put("bboxWidth", bboxWidth.toDouble())
            .put("bboxHeight", bboxHeight.toDouble())
            .put("bboxAreaRatio", bboxAreaRatio.toDouble())
            .put("bboxHeightRatio", bboxHeightRatio.toDouble())
            .put("centerXRatio", centerXRatio.toDouble())
            .put("centerYRatio", centerYRatio.toDouble())
            .put("horizontalPosition", horizontalPosition.name)
            .put("riskObjectCategory", riskObjectCategory.name)
            .put("objectPriority", objectPriority.name)
            .put("proximityLevel", proximityLevel.name)
            .put("riskLevel", riskLevel.name)
            .put("warningScenario", warningScenario.name)
            .put("motionDirection", motionDirection.name)
            .put("approachSpeedLevel", approachSpeedLevel.name)
            .put("objectMovementState", objectMovementState.name)
            .put("userObjectRelation", userObjectRelation.name)
            .put("isIgnored", isIgnored)
            .put("warningMessage", warningFeedback.message ?: JSONObject.NULL)
            .put("beepLevel", warningFeedback.beepLevel.name)
            .put("vibrationLevel", warningFeedback.vibrationLevel.name)
            .put("voiceLevel", warningFeedback.voiceLevel.name)
            .put("shouldNotify", warningFeedback.shouldNotify)
            .put("detectionCount", summary.detectionCount)
            .put("visibleDetectionCount", summary.visibleDetectionCount)
            .put("warningDetectionCount", summary.warningDetectionCount)
            .put("topLabel", summary.topLabel ?: JSONObject.NULL)
            .put("topConfidence", summary.topConfidence?.toDouble() ?: JSONObject.NULL)
            .put("selectedWarningLabel", summary.selectedWarningLabel ?: JSONObject.NULL)
            .put("selectedRiskLevel", summary.selectedRiskLevel?.name ?: JSONObject.NULL)
            .put("selectedWarningMessage", summary.selectedWarningMessage ?: JSONObject.NULL)
            .put("inferenceTimeMs", summary.inferenceTimeMs)
            .put("fps", summary.fps)
            .put("userMotionState", summary.userMotionState ?: JSONObject.NULL)
            .put("gpsSpeedMps", summary.gpsSpeedMps?.toDouble() ?: JSONObject.NULL)
            .put("gpsAccuracyMeters", summary.gpsAccuracyMeters?.toDouble() ?: JSONObject.NULL)
            .put(
                "bbox",
                JSONObject()
                    .put("left", left.toDouble())
                    .put("top", top.toDouble())
                    .put("right", right.toDouble())
                    .put("bottom", bottom.toDouble())
            )
    }

    private fun DetectionFrameSnapshot.toFrameSummary(): EvaluationFrameSummary {
        return EvaluationFrameSummary(
            timestampMs = frameTimestampMs,
            frameWidth = imageWidth,
            frameHeight = imageHeight,
            detectionCount = rawDetectionCount,
            visibleDetectionCount = visibleDetectionCount,
            warningDetectionCount = warningDetectionCount,
            topLabel = topDetection?.label,
            topConfidence = topDetection?.confidence,
            selectedWarningLabel = selectedWarningCandidate?.label,
            selectedRiskLevel = selectedWarningCandidate?.riskLevel,
            selectedWarningMessage = selectedWarningCandidate?.feedback?.message,
            inferenceTimeMs = inferenceTimeMs,
            fps = fps,
            userMotionState = userLocationSnapshot?.motionState?.name,
            gpsSpeedMps = userLocationSnapshot?.speedMps,
            gpsAccuracyMeters = userLocationSnapshot?.accuracyMeters
        )
    }

    private fun EvaluationFrameSummary.toJson(): JSONObject {
        return JSONObject()
            .put("timestampMs", timestampMs)
            .put("frameWidth", frameWidth)
            .put("frameHeight", frameHeight)
            .put("detectionCount", detectionCount)
            .put("visibleDetectionCount", visibleDetectionCount)
            .put("warningDetectionCount", warningDetectionCount)
            .put("topLabel", topLabel ?: JSONObject.NULL)
            .put("topConfidence", topConfidence?.toDouble() ?: JSONObject.NULL)
            .put("selectedWarningLabel", selectedWarningLabel ?: JSONObject.NULL)
            .put("selectedRiskLevel", selectedRiskLevel?.name ?: JSONObject.NULL)
            .put("selectedWarningMessage", selectedWarningMessage ?: JSONObject.NULL)
            .put("inferenceTimeMs", inferenceTimeMs)
            .put("fps", fps)
            .put("userMotionState", userMotionState ?: JSONObject.NULL)
            .put("gpsSpeedMps", gpsSpeedMps?.toDouble() ?: JSONObject.NULL)
            .put("gpsAccuracyMeters", gpsAccuracyMeters?.toDouble() ?: JSONObject.NULL)
    }

    private fun buildMetadataJson(): JSONObject {
        return JSONObject()
            .put("appVersionName", resolveAppVersionName())
            .put("modelName", modelName)
            .put("detectorType", detectorType)
            .put("detectionConfig", detectionConfig.toJson())
            .put("createdAt", System.currentTimeMillis())
            .put("deviceModel", Build.MODEL)
            .put("androidVersion", Build.VERSION.RELEASE)
    }

    private fun DetectionConfig.toJson(): JSONObject {
        return JSONObject()
            .put("detectIntervalMs", detectIntervalMs)
            .put("inputSize", inputSize)
            .put("confidenceThreshold", confidenceThreshold.toDouble())
            .put("nmsThreshold", nmsThreshold.toDouble())
            .put("minBoxAreaRatio", minBoxAreaRatio.toDouble())
            .put("minBoxWidthRatio", minBoxWidthRatio.toDouble())
            .put("minBoxHeightRatio", minBoxHeightRatio.toDouble())
            .put("overlayDebugMode", overlayDebugMode.name)
    }

    private fun resolveAppVersionName(): String {
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "resolve app version failed", e)
            "unknown"
        }
    }

    private fun buildDetectionId(timestampMs: Long, index: Int, label: String): String {
        val safeLabel = label.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_\\-]+"), "_")
            .trim('_')
            .ifBlank { "object" }
        return "${timestampMs}_${index}_$safeLabel"
    }

    private fun Rect.toJson(): JSONObject {
        return JSONObject()
            .put("left", left)
            .put("top", top)
            .put("right", right)
            .put("bottom", bottom)
            .put("width", width())
            .put("height", height())
    }

    data class CaptureFiles(
        val image: File,
        val detectionsJson: File,
        val overlayImage: File
    )

    companion object {
        private const val TAG = "EvaluationRecorder"
        private const val ROOT_DIR = "ObjectDetectionTestApp"
        private const val JPEG_QUALITY = 92

        private val boxPaint = Paint().apply {
            color = Color.CYAN
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        private val labelPaint = Paint().apply {
            color = Color.YELLOW
            textSize = 24f
            isAntiAlias = true
        }
    }
}
