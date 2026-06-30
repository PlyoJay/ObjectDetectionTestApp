package com.samin.objectdetection.evaluation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.samin.objectdetection.detector.DetectionResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Locale

class EvaluationDataRecorder(
    private val context: Context
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
                closeRecordingLogLocked()
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
        return JSONObject()
            .put("id", id)
            .put("frameTimestampMs", snapshot.frameTimestampMs)
            .put("imageWidth", snapshot.imageWidth)
            .put("imageHeight", snapshot.imageHeight)
            .put("roiApplied", snapshot.roiApplied)
            .put("roi", snapshot.roi?.toJson() ?: JSONObject.NULL)
            .put("coordinateSpace", "original_image")
            .put("detections", JSONArray(snapshot.detections.map { it.toJson(snapshot) }))
    }

    private fun DetectionResult.toJson(snapshot: DetectionFrameSnapshot): JSONObject {
        return JSONObject()
            .put("label", label)
            .put("confidence", confidence.toDouble())
            .put("frameTimestampMs", frameTimestampMs)
            .put("imageWidth", snapshot.imageWidth)
            .put("imageHeight", snapshot.imageHeight)
            .put(
                "bbox",
                JSONObject()
                    .put("left", left.toDouble())
                    .put("top", top.toDouble())
                    .put("right", right.toDouble())
                    .put("bottom", bottom.toDouble())
            )
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
