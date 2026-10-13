package com.samin.objectdetection.metrics

import com.samin.objectdetection.camera.YoloResizeMode
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
    val resizeMode: YoloResizeMode,
    val yoloThreads: Int,
    val mlKitEnabled: Boolean,
    val geometryFilterEnabled: Boolean,
    val adaptiveTemporalEnabled: Boolean,
    val confidenceThreshold: Float,
    val nmsThreshold: Float,
    val presetName: String = "DEFAULT",
    val settingsSchemaVersion: Int = 1,
    val modelSha256: String = "unknown",
    val requestedCameraWidth: Int = cameraWidth,
    val requestedCameraHeight: Int = cameraHeight,
    val appVersion: String = "unknown",
    val settingsSnapshot: String = "",
    val sessionId: String = "unknown",
    val startedElapsedNs: Long = 0L
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
    val fps: Int,
    val analyzerFps: Double = 0.0,
    val inferenceFps: Double = fps.toDouble(),
    val trace: FrameTrace? = null
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
        appendLine("Session ID: ${header.sessionId}")
        appendLine("Start Unix Ms: ${header.startedAtMs}; Start Elapsed Ns: ${header.startedElapsedNs}")
        appendLine("Clock: *AtMs/UnixMs=Unix wall ms; *ElapsedNs=elapsedRealtimeNanos; cameraTimestampNs=camera source")
        appendLine("FPS: fps/Average FPS=successful pipeline completions; analyzerFps=delivered analyzer frames; droppedFrames=explicit app skips")
        appendLine("Unknown camera capture latency=-1; FRAME time is pipeline completion, OVERLAY is a separate draw event")
        appendLine()
        appendLine("Model: ${header.modelName}")
        appendLine("Model SHA256: ${header.modelSha256}")
        appendLine("Detector: ${header.detectorName}")
        appendLine("App Version: ${header.appVersion}")
        appendLine("Preset: ${header.presetName}")
        appendLine("Settings Schema Version: ${header.settingsSchemaVersion}")
        appendLine("Input Size: ${header.inputWidth}x${header.inputHeight}")
        appendLine("Requested Camera Resolution: ${header.requestedCameraWidth}x${header.requestedCameraHeight}")
        appendLine("Camera Resolution: ${header.cameraWidth}x${header.cameraHeight}")
        appendLine("resizeMode=${header.resizeMode}")
        appendLine("YOLO Threads: ${header.yoloThreads}")
        appendLine("ML Kit Enabled: ${header.mlKitEnabled}")
        appendLine("Geometry Filter Enabled: ${header.geometryFilterEnabled}")
        appendLine("Adaptive Temporal Enabled: ${header.adaptiveTemporalEnabled}")
        appendLine("Confidence Threshold: ${header.confidenceThreshold}")
        appendLine("NMS Threshold: ${header.nmsThreshold}")
        if (header.settingsSnapshot.isNotBlank()) {
            appendLine()
            appendLine("--- Applied AppSettings Snapshot ---")
            appendLine(header.settingsSnapshot)
            appendLine("--- End AppSettings Snapshot ---")
        }
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
            "visible=${frame.visibleDetectionCount} | warning=${frame.warningDetectionCount} | fps=${frame.fps}" +
            " | analyzerFps=${frame.analyzerFps} | inferenceFps=${frame.inferenceFps}" +
            (frame.trace?.let { " | ${formatTrace(it)}" } ?: "")

    fun formatTrace(trace: FrameTrace): String = with(trace) {
        "sessionId=$sessionId frameId=$frameId status=$status captureUnixMs=$frameTimestampMs " +
            "cameraTimestampNs=$cameraTimestampNs captureTimestampReliable=$captureTimestampReliable " +
            "analyzerUnixMs=$analyzerReceivedMs analyzerElapsedNs=$analyzerReceivedElapsedNs " +
            "inferenceStartUnixMs=$inferenceStartedAtMs inferenceStartElapsedNs=$inferenceStartedElapsedNs " +
            "completedUnixMs=$completedAtMs completedElapsedNs=$completedElapsedNs analyzerToInferenceMs=$analyzerToInferenceMs " +
            "errorStage=$errorStage error=${error?.replace('\n', ' ')?.replace('\r', ' ')}"
    }

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
        appendLine("Analyzer FPS: ${snapshot.analyzerFps}; Successful Inference FPS: ${snapshot.inferenceFps}")
        appendLine("Successful Frames: ${snapshot.successfulFrames}; Failed Frames: ${snapshot.failedFrames}")
        appendStats("Preprocess", snapshot.preprocess)
        appendStats("Inference", snapshot.inference)
        appendStats("Postprocess", snapshot.postprocess)
        appendStats("Pipeline", snapshot.pipeline)
        appendStats("Frame Age At Overlay", snapshot.frameAgeAtOverlay)
        appendLine("Overlay Latency Samples: ${snapshot.overlayLatencySamples} (0 means unavailable)")
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
