package com.samin.objectdetection.metrics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PerformanceLogHeader(
    val startedAtMs: Long,
    val modelName: String,
    val detectorName: String,
    val inputWidth: Int,
    val inputHeight: Int,
    val cameraWidth: Int,
    val cameraHeight: Int,
    val yoloThreads: Int,
    val mlKitEnabled: Boolean,
    val geometryFilterEnabled: Boolean,
    val adaptiveTemporalEnabled: Boolean,
    val confidenceThreshold: Float,
    val nmsThreshold: Float
)

data class PerformanceFrameRecord(
    val timestampMs: Long,
    val frameIndex: Long = 0L,
    val bitmapConversionMs: Long,
    val preprocessMs: Long,
    val cropMs: Long,
    val resizeMs: Long,
    val inferenceMs: Long,
    val outputParsingMs: Long,
    val nmsMs: Long,
    val postprocessMs: Long,
    val pipelineMs: Long,
    val frameAgeAtInferenceStartMs: Long,
    val frameAgeAtOverlayMs: Long,
    val detectionCount: Int,
    val visibleDetectionCount: Int,
    val warningDetectionCount: Int,
    val fps: Int
)

object PerformanceLogFormatter {
    private val dateTimeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
    private val frameTimeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    fun formatHeader(header: PerformanceLogHeader): String = buildString {
        appendLine("========================================")
        appendLine("GOTORO Object Detection Performance Log")
        appendLine("========================================")
        appendLine()
        appendLine("Start Time: ${dateTimeFormat.get()!!.format(Date(header.startedAtMs))}")
        appendLine()
        appendLine("Model: ${header.modelName}")
        appendLine("Detector: ${header.detectorName}")
        appendLine("Input Size: ${header.inputWidth}x${header.inputHeight}")
        appendLine("Camera Resolution: ${header.cameraWidth}x${header.cameraHeight}")
        appendLine("YOLO Threads: ${header.yoloThreads}")
        appendLine("ML Kit Enabled: ${header.mlKitEnabled}")
        appendLine("Geometry Filter Enabled: ${header.geometryFilterEnabled}")
        appendLine("Adaptive Temporal Enabled: ${header.adaptiveTemporalEnabled}")
        appendLine("Confidence Threshold: ${header.confidenceThreshold}")
        appendLine("NMS Threshold: ${header.nmsThreshold}")
        appendLine()
        appendLine("========================================")
    }

    fun formatFrame(frame: PerformanceFrameRecord): String =
        "FRAME ${frame.frameIndex.toString().padStart(6, '0')} | " +
            "time=${frameTimeFormat.get()!!.format(Date(frame.timestampMs))} | " +
            "bitmap=${frame.bitmapConversionMs}ms | preprocess=${frame.preprocessMs}ms | " +
            "crop=${frame.cropMs}ms | resize=${frame.resizeMs}ms | " +
            "inference=${frame.inferenceMs}ms | outputParsing=${frame.outputParsingMs}ms | " +
            "nms=${frame.nmsMs}ms | post=${frame.postprocessMs}ms | pipeline=${frame.pipelineMs}ms | " +
            "frameAgeInference=${frame.frameAgeAtInferenceStartMs}ms | " +
            "frameAgeOverlay=${frame.frameAgeAtOverlayMs}ms | detect=${frame.detectionCount} | " +
            "visible=${frame.visibleDetectionCount} | warning=${frame.warningDetectionCount} | fps=${frame.fps}"

    fun formatFinalSummary(snapshot: PerformanceSnapshot, droppedLogLines: Long): String = buildString {
        appendLine()
        appendLine("========================================")
        appendLine("FINAL SUMMARY")
        appendLine("========================================")
        appendLine("Duration: ${formatDuration(snapshot.durationMs)}")
        appendLine("Received Frames: ${snapshot.receivedFrames}")
        appendLine("Processed Frames: ${snapshot.processedFrames}")
        appendLine("Dropped Frames: ${snapshot.droppedFrames}")
        appendLine("Dropped Log Lines: $droppedLogLines")
        appendLine("Average FPS: ${snapshot.averageFps}")
        appendStats("Preprocess", snapshot.preprocess)
        appendStats("Inference", snapshot.inference)
        appendStats("Postprocess", snapshot.postprocess)
        appendStats("Pipeline", snapshot.pipeline)
        appendStats("Frame Age At Overlay", snapshot.frameAgeAtOverlay)
        appendLine("YOLO Detections: ${snapshot.yoloDetections}")
        appendLine("Visible Detections: ${snapshot.visibleDetections}")
        appendLine("Warnings: ${snapshot.warnings}")
        appendLine("ML Kit Average: ${snapshot.mlKitAverageMs} ms")
        appendLine("========================================")
    }

    private fun StringBuilder.appendStats(name: String, stats: PerformanceMetricStats) {
        appendLine("Average $name: ${stats.averageMs} ms")
        appendLine("P95 $name: ${stats.p95Ms} ms")
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1_000L
        return "%02d:%02d".format(Locale.US, totalSeconds / 60L, totalSeconds % 60L)
    }
}
