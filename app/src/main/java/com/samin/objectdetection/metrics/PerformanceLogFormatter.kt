package com.samin.objectdetection.metrics

import com.samin.objectdetection.camera.YoloResizeMode
import com.samin.objectdetection.detector.InferenceExecutionInfo
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
    val startedElapsedNs: Long = 0L,
    val systemDiagnosticsEnabled: Boolean = false,
    val systemSampleIntervalMs: Long = 5_000L,
    val execution: InferenceExecutionInfo? = null,
    val batteryDiagnosticsEnabled: Boolean = false,
    val cameraPreviewEnabled: Boolean = true,
    val durationMinutes: Int = 5,
    val roiDescription: String = "FULL_FRAME"
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
    val trace: FrameTrace? = null,
    val elapsedSeconds: Double? = null,
    val execution: InferenceExecutionInfo? = null,
    // Analyzer receipt through successful pipeline completion, including camera conversion/ML input.
    val totalProcessingMs: Long = -1L,
    val cameraToBitmapNs: Long = -1L,
    val rotationNs: Long = -1L,
    val inputBufferMs: Long = -1L,
    val getPixelsNs: Long = -1L,
    val rgbWriteNs: Long = -1L,
    val inputDiagnosticsNs: Long = -1L,
    val outputDiagnosticsNs: Long = -1L,
    val nativeInferenceNs: Long = -1L,
    val mlKitPrepareNs: Long = -1L,
    val mlKitInFlight: Boolean = false,
    val roiDescription: String = "N/A"
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
        appendLine("System Performance Diagnostics: ${header.systemDiagnosticsEnabled}; Sample Interval: ${header.systemSampleIntervalMs}ms")
        appendLine("Battery Thermal Diagnostics: ${header.batteryDiagnosticsEnabled}; Effective: ${header.systemDiagnosticsEnabled && header.batteryDiagnosticsEnabled}; battery polling uses system ticks, minimum 5000ms plus final endpoint")
        if (header.batteryDiagnosticsEnabled && header.systemDiagnosticsEnabled) {
            appendLine("Battery scope=device battery, not app consumption; temperature=battery, not CPU/GPU; signed current mA (+charging/-discharging); estimatedBatteryPowerMw=currentMa*voltageMv/1000, signed estimate, never app power even while charging")
            appendLine("Battery counters: charge=uAh, energy=nWh; unsupported/invalid=N/A; means use valid samples grouped by charging status; delta=last-first; 0% level change does not mean zero consumption")
            appendLine("Battery endpoints=first/final observed samples (see timestamps); max=sampled maximum; changes=observed transitions, between-poll changes may be missed")
            appendLine("Battery RAW=exact getIntProperty result (including sentinels); API_DECLARED_uA is a contract, not device unit verification; *ApiMa=RAW/1000; apiEstimatedBatteryPowerMw=*ApiMa*voltageMv/1000 is unvalidated arithmetic")
            appendLine("batteryCurrentMa/estimatedBatteryPowerMw=N/A until >=60s and >=1mAh paired unplugged discharge show current/counter ratio within [0.1,10]; mismatch/sign conflict latches for the session, no automatic rescaling; consistency is a heuristic, not calibration")
            appendLine("batteryCurrentAverageMa=N/A: hardware averaging window is unknown; batteryCurrentAverageApiMa preserves API conversion. averageBatteryCurrentMa is the sample mean of CURRENT_NOW, not CURRENT_AVERAGE")
            appendLine("Counter interval discharge is positive: (previousUah-currentUah)/1000*3600/seconds; power uses mean endpoint voltage. Unchanged/reset/missing counters, power transitions or gaps >120s=N/A plus warning; short intervals are quantized estimates")
            appendLine("Battery currentValidSamples/powerValidSamples count API arithmetic availability, not verified accuracy; counter totals cover eligible adjacent pairs only; final status means are N/A unless every sample in that status is covered by paired consistency checks")
        }
        appendLine("System metrics unavailable=N/A; processCpuUsage=all app process threads, 100%=one CPU core (may exceed 100%); cpuFrequency=device cpufreq policy, not process scope")
        appendLine("Thermal Headroom: forecastSeconds=0, minimum API call interval=10000ms; cached values include sample time/age, 1.0=SEVERE threshold, not a temperature")
        appendLine("INTERVAL_SUMMARY=[start,end) by successful completion time, 30 seconds plus final partial/empty intervals; P95=nearest rank of actual valid frame samples")
        appendLine("INTERVAL_PREVIEW may miss delayed submissions; final INTERVAL_SUMMARY/CUMULATIVE_SUMMARY are authoritative and include frame data even if file lines were dropped")
        appendLine("Interval system fields=last system sample within that interval (not averages); OFF/no samples=N/A")
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
        appendLine("cameraPreviewEnabled=${header.cameraPreviewEnabled}")
        appendLine("configuredDurationMs=${header.durationMinutes * 60_000L}; timerClock=elapsedRealtimeNanos; completionWindow=[start,deadline)")
        appendLine("Recognition ROI: ${header.roiDescription}; coordinateSpace=rotated_full_frame_pixels; proxyCropApplied=false; MLKit=shared_ROI")
        appendLine("Overlay Draw: ${if (header.cameraPreviewEnabled) "AVAILABLE_WHEN_DRAWN" else "NOT_DISPLAYED_PREVIEW_OFF"}; missing draw events/age (-1) are not inference or frame errors")
        appendLine("resizeMode=${header.resizeMode}")
        appendLine("YOLO Threads: ${header.yoloThreads}")
        appendLine("Inference Execution: ${header.execution?.logFields() ?: "N/A"}")
        appendLine("XNNPACK Requested: ${header.execution?.xnnpackRequested ?: "N/A"} (CPU request only; ON/OFF does not verify native delegation or change backend)")
        appendLine("CPU thread count is the accepted Interpreter option; active worker count/partition coverage are unavailable in Java. Confirm native delegate application with Logcat/profiling.")
        appendLine("Execution settings are fixed for this session. Stop/drain this log before applying settings; the recreated app uses a new session and recorder.")
        appendLine("pipeline=DetectionPipeline.process only; totalProcessing=analyzer receipt through pipeline completion (includes bitmap conversion and ML Kit input preparation; excludes UI draw/warning output)")
        appendLine("Detail timings are overlapping subsets, never summed into aggregates: cameraToBitmap/rotation within bitmap; getPixels/rgbWrite/inputDiagnostics within inputBuffer within preprocess; nativeInvoke within inference; outputDiagnostics within detector/pipeline but outside post. mlKitPrepare includes eligibility check/input resize/dispatch before pipeline; mlKitInFlight is a concurrency hint, not CPU time. Detail milliseconds retain 3 decimals; -1=unavailable, 0=skipped.")
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
            " | elapsedSeconds=${number(frame.elapsedSeconds)}" +
            " | totalProcessing=${frame.totalProcessingMs}ms" +
            " | cameraToBitmap=${detailMs(frame.cameraToBitmapNs)}ms | rotation=${detailMs(frame.rotationNs)}ms" +
            " | inputBuffer=${frame.inputBufferMs}ms | getPixels=${detailMs(frame.getPixelsNs)}ms" +
            " | rgbWrite=${detailMs(frame.rgbWriteNs)}ms | inputDiagnostics=${detailMs(frame.inputDiagnosticsNs)}ms" +
            " | outputDiagnostics=${detailMs(frame.outputDiagnosticsNs)}ms | nativeInvoke=${detailMs(frame.nativeInferenceNs)}ms" +
            " | mlKitPrepare=${detailMs(frame.mlKitPrepareNs)}ms | mlKitInFlight=${frame.mlKitInFlight}" +
            " | ${frame.roiDescription}" +
            " | ${frame.execution?.logFields() ?: "execution=N/A"}" +
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
        appendLine("Scope: legacy metrics collector cumulative snapshot (up to latest 100000 latency samples); exact recorder session statistics follow below")
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

    fun formatSystemSample(header: PerformanceLogHeader, sample: SystemMetricsSample): String =
        "SYSTEM | sessionId=${header.sessionId} elapsedSeconds=${number((sample.elapsedNs - header.startedElapsedNs) / 1e9)} " +
            "sampleUnixMs=${sample.unixMs} sampleElapsedNs=${sample.elapsedNs} ${systemFields(header, sample)} " +
            "collectionDurationMs=${number(sample.collectionDurationMs)}"

    fun formatThermalEvent(header: PerformanceLogHeader, event: ThermalStatusEvent): String =
        "THERMAL_STATUS | sessionId=${header.sessionId} elapsedSeconds=${number((event.elapsedNs - header.startedElapsedNs) / 1e9)} " +
            "observedUnixMs=${event.unixMs} observedElapsedNs=${event.elapsedNs} previousThermalStatus=${thermal(event.previous)} " +
            "thermalStatus=${thermal(event.status)} source=${event.source}"

    fun formatInterval(header: PerformanceLogHeader, value: PerformanceIntervalSummary, recordType: String): String =
        "$recordType | sessionId=${header.sessionId} intervalIndex=${value.index ?: "ALL"} " +
            "startSeconds=${number(value.startSeconds)} endSeconds=${number(value.endSeconds)} elapsedSeconds=${number(value.endSeconds)} " +
            "successfulFrames=${value.successfulFrames} inferenceAvgMs=${number(value.inference.averageMs)} inferenceP95Ms=${value.inference.p95Ms ?: "N/A"} " +
            "preprocessAvgMs=${number(value.preprocess.averageMs)} pipelineAvgMs=${number(value.pipeline.averageMs)} pipelineP95Ms=${value.pipeline.p95Ms ?: "N/A"} " +
            "totalProcessingAvgMs=${number(value.totalProcessing.averageMs)} totalProcessingP95Ms=${value.totalProcessing.p95Ms ?: "N/A"} totalProcessingSamples=${value.totalProcessing.samples} " +
            "${(value.execution ?: header.execution)?.logFields() ?: "execution=N/A"} " +
            "inferenceFps=${number(value.inferenceFps)} inferenceSamples=${value.inference.samples} preprocessSamples=${value.preprocess.samples} pipelineSamples=${value.pipeline.samples} " +
            systemFields(header, value.lastSystemSample)

    private fun systemFields(header: PerformanceLogHeader, sample: SystemMetricsSample?): String =
        "thermalStatus=${thermal(sample?.thermalStatus)} thermalHeadroom=${number(sample?.thermalHeadroom?.toDouble())} " +
            "headroomSampleElapsedSeconds=${number(sample?.headroomSampleElapsedNs?.let { (it - header.startedElapsedNs) / 1e9 })} " +
            "headroomAgeMs=${number(sample?.headroomSampleElapsedNs?.let { (sample.elapsedNs - it) / 1e6 })} " +
            "processCpuUsage=${number(sample?.processCpuUsage)} processCpuScope=process_all_threads_percent_one_core " +
            "cpuUsageWindowMs=${number(sample?.cpuUsageWindowMs)} cpuFrequency=${sample?.cpuFrequency ?: "N/A"} cpuFrequencyScope=device_policy_kHz " +
            "systemSampleElapsedSeconds=${number(sample?.let { (it.elapsedNs - header.startedElapsedNs) / 1e9 })}"

    private fun detailMs(ns: Long): String = if (ns < 0) "-1" else String.format(Locale.US, "%.3f", ns / 1e6)
    private fun number(value: Double?): String = value?.takeIf(Double::isFinite)?.let { String.format(Locale.US, "%.3f", it) } ?: "N/A"
    internal fun thermal(value: Int?): String = when (value) {
        0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"; 3 -> "SEVERE"; 4 -> "CRITICAL"; 5 -> "EMERGENCY"; 6 -> "SHUTDOWN"; else -> "N/A"
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1_000L
        return "%02d:%02d".format(Locale.US, totalSeconds / 60L, totalSeconds % 60L)
    }
}
