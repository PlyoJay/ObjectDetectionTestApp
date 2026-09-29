package com.samin.objectdetection.detector

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.samin.objectdetection.camera.DetectionConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Bounded, best-effort diagnostics. Never run disk work on the analyzer/UI threads. */
class DetectionDebugRecorder(context: Context, val config: DetectionConfig) : AutoCloseable {
    private val session = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    private val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "debug_detection/$session")
    private val logFile = File(root, "detection_debug_$session.txt")
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16))
    private val imagePending = AtomicBoolean(false)
    private val dropped = AtomicLong()
    private var lastPeriodicMs = Long.MIN_VALUE
    private var lastDetectionMs = Long.MIN_VALUE
    private var lastInputDescription: String? = null
    private var sequence = 0L

    fun begin(timestampMs: Long): DetectionDebugFrame? {
        if (!config.debugDetectionLogging && !config.debugSaveInferenceInput && !config.enableDetectorDebugImage) return null
        return DetectionDebugFrame(++sequence, timestampMs, config)
    }

    fun modelInput(frame: DetectionDebugFrame, description: String) {
        val fullDescription = "${frame.sourceDescription} $description"
        if (fullDescription != lastInputDescription) {
            frame.line("[MODEL_INPUT] frame=${frame.id} $fullDescription")
            lastInputDescription = fullDescription
        }
    }

    fun record(text: String) {
        if (!config.debugDetectionLogging) return
        submit {
            root.mkdirs()
            logFile.appendText("[DEBUG_QUEUE] droppedTasksTotal=${dropped.get()}\n$text\n")
        }
    }

    fun finish(frame: DetectionDebugFrame) = record(frame.text())

    // Called while modelInput still owns its pixels, before the next inference can reuse it.
    fun saveInput(frame: DetectionDebugFrame, bitmap: Bitmap, detections: List<DetectionResult>) {
        if (!config.debugSaveInferenceInput && !config.enableDetectorDebugImage) return
        val now = SystemClock.elapsedRealtime()
        val interval = config.debugSaveIntervalMs.coerceAtLeast(1L)
        val periodic = lastPeriodicMs == Long.MIN_VALUE || now - lastPeriodicMs >= interval
        val detected = config.debugSaveOnDetection && detections.isNotEmpty() &&
            (lastDetectionMs == Long.MIN_VALUE || now - lastDetectionMs >= interval)
        if (!periodic && !detected) return
        if (!imagePending.compareAndSet(false, true)) {
            frame.line("[INPUT_SAVE_SKIPPED] frame=${frame.id} reason=IMAGE_WRITER_BUSY")
            return
        }
        val copy = try { bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (e: Exception) {
            imagePending.set(false)
            frame.line("[INPUT_SAVE_ERROR] frame=${frame.id} error=$e")
            return
        }
        if (copy == null) { imagePending.set(false); return }
        val stem = "frame_${frame.id.toString().padStart(6, '0')}"
        val names = mutableListOf<String>()
        if (periodic) names.add("$stem.png")
        if (detected) names.add("detection_${stem}_conf_${String.format(Locale.US, "%.3f", detections.maxOf { it.confidence })}.png")
        val accepted = submit {
            try {
                val dir = File(root, "input").apply { mkdirs() }
                names.forEach { name ->
                    File(dir, name).outputStream().use { check(copy.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                }
                Log.i(TAG, "[INPUT_SAVED] frame=${frame.id} path=$dir files=$names")
            } finally { copy.recycle(); imagePending.set(false) }
        }
        if (accepted) {
            if (periodic) lastPeriodicMs = now
            if (detected) lastDetectionMs = now
            frame.line("[INPUT_SAVE_QUEUED] frame=${frame.id} files=$names format=PNG_lossless")
        } else {
            copy.recycle()
            imagePending.set(false)
            frame.line("[INPUT_SAVE_SKIPPED] frame=${frame.id} reason=QUEUE_FULL_OR_CLOSED")
        }
    }

    private fun submit(action: () -> Unit): Boolean = try {
        worker.execute {
            try { action() } catch (e: Exception) { Log.e(TAG, "Diagnostic I/O failed path=$root", e) }
        }
        true
    } catch (_: java.util.concurrent.RejectedExecutionException) {
        dropped.incrementAndGet()
        Log.w(TAG, "Diagnostic task dropped total=${dropped.get()}")
        false
    }

    override fun close() { worker.shutdown() } // Queued writes drain without blocking the UI.

    companion object { private const val TAG = "DetectionDebug" }
}

/** Owned by one analyzer frame; no bitmap or tensor buffer is retained. */
class DetectionDebugFrame(val id: Long, timestampMs: Long, private val config: DetectionConfig) {
    var sourceDescription: String = ""
    private val lines = StringBuilder()
    private var rawLogged = 0
    private var filteredLogged = 0
    private var filteredOmitted = 0
    private var qualifying = 0
    private var maxScore = Float.NEGATIVE_INFINITY
    private var maxClass = "none"
    private var bollardMaxScore = Float.NEGATIVE_INFINITY
    private var bollardCandidates = 0
    init { line("[FRAME] frame=$id captureTimestampMs=$timestampMs") }
    fun line(value: String) { if (config.debugDetectionLogging) lines.appendLine(value) }
    fun raw(index: Int, classId: Int, label: String, score: Float, cx: Float, cy: Float, w: Float, h: Float, threshold: Float) {
        if (!config.debugDetectionLogging) return
        if (score > maxScore) { maxScore = score; maxClass = label }
        if (score < config.diagnosticRawConfidenceThreshold || classId < 0) return
        qualifying++
        if (label.equals("bollard", ignoreCase = true)) {
            bollardCandidates++
            if (score > bollardMaxScore) bollardMaxScore = score
        }
        if (rawLogged >= config.diagnosticMaxRawCandidates.coerceAtLeast(0)) return
        rawLogged++
        val box = "[${cx-w/2},${cy-h/2},${cx+w/2},${cy+h/2}]"
        line("[RAW_YOLO] frame=$id candidate=$index classId=$classId className=$label confidence=$score bbox=$box bboxSpace=output_tensor_xyxy rawCxCyWh=[$cx,$cy,$w,$h]")
        if (score < threshold) line("[FILTERED] frame=$id candidate=$index class=$label confidence=$score reason=CONFIDENCE bbox=$box bboxSpace=output_tensor_xyxy")
    }
    fun summary(total: Int) = line("[RAW_YOLO_SUMMARY] frame=$id tensorCandidateCount=$total candidateCount=$qualifying logged=$rawLogged omitted=${qualifying-rawLogged} maxConfidence=$maxScore maxClass=$maxClass bollardMaxConfidence=${if (bollardCandidates > 0) bollardMaxScore else "none"} bollardCandidateCount=$bollardCandidates diagnosticThreshold=${config.diagnosticRawConfidenceThreshold}")
    fun filtered(detection: DetectionResult, reason: String, space: String = "rotated_frame_pixels") {
        if (!config.debugDetectionLogging) return
        if (filteredLogged++ >= 300) { filteredOmitted++; return }
        line("[FILTERED] frame=$id class=${detection.label} confidence=${detection.confidence} reason=$reason bbox=[${detection.left},${detection.top},${detection.right},${detection.bottom}] bboxSpace=$space")
    }
    fun removed(before: List<DetectionResult>, after: List<DetectionResult>, reason: String) {
        if (config.debugDetectionLogging) before.filterNot { it in after }.forEach { filtered(it, reason) }
    }
    fun text(): String = lines.toString() + "[DETAIL_LIMIT] frame=$id filteredOmitted=$filteredOmitted\n"
}
