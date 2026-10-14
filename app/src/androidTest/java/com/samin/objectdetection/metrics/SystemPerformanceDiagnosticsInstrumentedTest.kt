package com.samin.objectdetection.metrics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.camera.SizeFilterMode
import com.samin.objectdetection.camera.YoloResizeMode
import com.samin.objectdetection.detector.VisionStyleYoloDetector
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

class SystemPerformanceDiagnosticsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun publicApisAndRestrictedFrequencyAccessCollectSafelyAndStop() {
        val samples = mutableListOf<SystemMetricsSample>()
        val events = mutableListOf<ThermalStatusEvent>()
        val ready = CountDownLatch(3)
        val diagnostics = SystemPerformanceDiagnostics(AndroidSystemMetricSource(context), 5000,
            SystemClock::elapsedRealtimeNanos, System::currentTimeMillis,
            { synchronized(samples) { samples += it }; ready.countDown() }, { events += it }, {})
        diagnostics.start()
        try { assertTrue("Expected samples at approximately 0/5/10 seconds", ready.await(20, TimeUnit.SECONDS)) }
        finally { diagnostics.stop().get(5, TimeUnit.SECONDS) }
        assertEquals(3, samples.size)
        assertNull(samples.first().processCpuUsage)
        samples.forEach { value ->
            assertTrue(value.thermalStatus == null || value.thermalStatus in 0..6)
            assertTrue(value.thermalHeadroom == null || value.thermalHeadroom.isFinite())
            assertTrue(value.processCpuUsage == null || value.processCpuUsage >= 0)
            Log.i(TAG, "DEVICE_SAMPLE thermal=${value.thermalStatus} headroom=${value.thermalHeadroom} processCpuUsage=${value.processCpuUsage} frequency=${value.cpuFrequency ?: "N/A"} collectionMs=${value.collectionDurationMs}")
        }
        assertTrue(events.isNotEmpty()) // initial observed status, including N/A
    }

    @Test fun realYoloDiagnosticsOffOnComparisonAndMediaStoreStopDrain() {
        val original = context.assets.open("self_test/known.png").use { BitmapFactory.decodeStream(it) }
        val bitmap = Bitmap.createScaledBitmap(original, 1080, 1440, true)
        if (bitmap !== original) original.recycle()
        val detector = VisionStyleYoloDetector(context, confidenceThreshold = 0.2f, nmsThreshold = 0.45f,
            sizeFilterMode = SizeFilterMode.DISABLED, resizeMode = YoloResizeMode.LETTERBOX)
        val report = StringBuilder("Fixed-image detector comparison; excludes CameraX/UI/recording; no speedup assertion.\n")
        try {
            repeat(5) { detector.detect(bitmap) }
            for (trial in 0..2) {
                // Alternate order to reduce bias from monotonic thermal drift; small experiment, not a field benchmark.
                for (enabled in if (trial % 2 == 0) listOf(false, true) else listOf(true, false)) {
                    val recorder = PerformanceLogRecorder(context)
                    val collector = DetectionMetricsCollector()
                    val startNs = SystemClock.elapsedRealtimeNanos(); val startWall = System.currentTimeMillis()
                    val id = "diagnostics-validation-$trial-$enabled"
                    val header = PerformanceLogHeader(startWall, "best.tflite", "VisionStyleYoloDetector", 640, 640,
                        1080, 1440, YoloResizeMode.LETTERBOX, 4, false, false, false, 0.2f, 0.45f,
                        sessionId = id, startedElapsedNs = startNs, systemDiagnosticsEnabled = enabled)
                    runBlocking { recorder.start(header) }
                    collector.startPerformanceSession(startWall, startNs)
                    val inference = mutableListOf<Long>(); val total = mutableListOf<Long>()
                    try {
                        repeat(15) { i ->
                            val frameStart = SystemClock.elapsedRealtimeNanos()
                            detector.detect(bitmap)
                            val completed = SystemClock.elapsedRealtimeNanos()
                            val timing = detector.frameDiagnostics()!!
                            val elapsed = (completed - frameStart) / 1_000_000L
                            inference += timing.inferenceTimeMs; total += elapsed
                            collector.recordInferenceSucceeded(completed)
                            collector.recordPerformance(timing.preprocessTimeMs, timing.inferenceTimeMs, timing.postprocessTimeMs, elapsed, -1, 0)
                            recorder.recordFrame(PerformanceFrameRecord(System.currentTimeMillis(), bitmapConversionMs = 0,
                                preprocessMs = timing.preprocessTimeMs, cropMs = 0, resizeMs = timing.resizeTimeMs,
                                inferenceMs = timing.inferenceTimeMs, outputParsingMs = timing.outputCopyTimeMs + timing.candidateScanTimeMs,
                                nmsMs = timing.nmsTimeMs, postprocessMs = timing.postprocessTimeMs, pipelineMs = elapsed,
                                frameAgeAtInferenceStartMs = -1, frameAgeAtOverlayMs = -1, detectionCount = 0,
                                visibleDetectionCount = 0, warningDetectionCount = 0, fps = 0,
                                trace = FrameTrace(id, i.toLong(), startWall, 0, false, startWall, frameStart,
                                    completedAtMs = System.currentTimeMillis(), completedElapsedNs = completed)))
                        }
                    } finally {
                        val summary = collector.stopPerformanceSessionSnapshot(stoppedElapsedNs = SystemClock.elapsedRealtimeNanos())
                        val saved = runBlocking { recorder.stop(summary) }!!
                        val text = context.contentResolver.openInputStream(saved.uri)!!.bufferedReader().use { it.readText() }
                        assertEquals(15, text.lineSequence().count { it.startsWith("FRAME ") })
                        assertTrue(text.contains("CUMULATIVE_SUMMARY")); assertTrue(text.contains("INTERVAL_SUMMARY"))
                        assertEquals(enabled, text.contains("SYSTEM |"))
                        assertEquals(0, saved.droppedLogLines.toInt())
                        report.appendLine("trial=$trial enabled=$enabled inferenceAvgMs=${inference.average()} inferenceP95Ms=${inference.sorted()[(inference.size * 95 + 99) / 100 - 1]} detectorAvgMs=${total.average()} measuredCompletionsPerSecond=${summary.inferenceFps}")
                        report.appendLine(text.lineSequence().first { it.startsWith("CUMULATIVE_SUMMARY") })
                        // Test-created MediaStore files are removed after content validation; user logs are untouched.
                        context.contentResolver.delete(saved.uri, null, null)
                    }
                }
            }
        } finally { detector.close(); bitmap.recycle() }
        File(context.getExternalFilesDir(null), "diagnostics_validation.txt").writeText(report.toString())
        Log.i(TAG, report.toString())
    }

    companion object { private const val TAG = "SystemDiagnosticsTest" }
}
