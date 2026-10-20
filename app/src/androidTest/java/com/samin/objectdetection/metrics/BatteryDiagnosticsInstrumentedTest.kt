package com.samin.objectdetection.metrics

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.camera.YoloResizeMode
import com.samin.objectdetection.detector.VisionStyleYoloDetector
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import java.io.File

class BatteryDiagnosticsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realBatterySamplesAndRepeatedStopCleanUpWithoutDuplicateHeadroomQueries() {
        repeat(4) { trial ->
            val ready = CountDownLatch(if (trial == 0) 3 else 1)
            val count = AtomicInteger()
            val diagnostics = SystemPerformanceDiagnostics(AndroidSystemMetricSource(context), 5000,
                SystemClock::elapsedRealtimeNanos, System::currentTimeMillis, {}, {}, {},
                batteryEnabled = true, onBattery = { sample ->
                    val r = sample.reading
                    assertTrue(r.levelPct == null || r.levelPct in 0.0..100.0)
                    assertTrue(r.voltageMv == null || r.voltageMv > 0)
                    assertTrue(r.currentMa == null || r.currentMa.isFinite())
                    assertEquals(BatteryUnits.currentMa(r.currentNowRaw), r.currentMa)
                    assertEquals(BatteryUnits.currentMa(r.currentAverageRaw), r.currentAverageMa)
                    Log.i("BatteryDiagnosticsTest", "trial=$trial reading=$r estimatedBatteryPowerMw=${r.estimatedPowerMw} batteryCollectionDurationMs=${sample.collectionDurationMs}")
                    count.incrementAndGet(); ready.countDown()
                })
            diagnostics.start()
            try { assertTrue(ready.await(20, TimeUnit.SECONDS)) }
            finally { diagnostics.stop().get(5, TimeUnit.SECONDS) }
            val stoppedCount = count.get()
            diagnostics.stop().get(5, TimeUnit.SECONDS)
            assertEquals(stoppedCount, count.get())
            assertTrue(stoppedCount >= if (trial == 0) 4 else 2) // includes final endpoint
        }
    }

    @Test fun mediaStoreOnOffOnPreservesLegacySummariesAndFinalBatteryEndpoint() {
        for ((index, enabled) in listOf(true, false, true).withIndex()) {
            val recorder = PerformanceLogRecorder(context)
            val now = SystemClock.elapsedRealtimeNanos()
            val header = PerformanceLogHeader(System.currentTimeMillis(), "best.tflite", "battery-api-validation",
                640, 640, 1280, 720, YoloResizeMode.LETTERBOX, 4, false, false, false, 0.2f, 0.45f,
                sessionId = "battery-media-$index", startedElapsedNs = now,
                systemDiagnosticsEnabled = true, batteryDiagnosticsEnabled = enabled)
            val saved = runBlocking {
                recorder.start(header)
                recorder.stop(DetectionMetricsCollector().stopPerformanceSessionSnapshot(
                    stoppedElapsedNs = SystemClock.elapsedRealtimeNanos()))!!
            }
            try {
                val text = context.contentResolver.openInputStream(saved.uri)!!.bufferedReader().use { it.readText() }
                assertTrue(text.contains("INTERVAL_SUMMARY |")); assertTrue(text.contains("CUMULATIVE_SUMMARY |"))
                assertEquals(enabled, text.contains("BATTERY_SAMPLE |"))
                assertEquals(enabled, text.contains("BATTERY_SUMMARY |"))
                assertEquals(enabled, text.contains("BATTERY_STATUS_SUMMARY |"))
                if (enabled) {
                    assertTrue(text.contains("batteryEnergyCounterNwh="))
                    assertTrue(text.contains("batteryCurrentNowRaw="))
                    assertTrue(text.contains("batteryCurrentNowApiMa="))
                    assertTrue(text.contains("batteryCurrentReliability="))
                    assertTrue(text.contains("counterAverageDischargeCurrentMa="))
                    assertTrue(text.contains("batteryWarnings="))
                    assertTrue(text.contains("temperature=battery_not_cpu_gpu"))
                }
                assertEquals(0L, saved.droppedLogLines)
                File(context.cacheDir, "battery-validation-$index.txt").writeText(text)
            } finally { context.contentResolver.delete(saved.uri, null, null) }
        }
    }

    @Test fun fixedImageYoloResultsStayIdenticalWithBatteryOnOffOn() {
        val bitmap = context.assets.open("self_test/known.png").use { BitmapFactory.decodeStream(it) }
        val detector = VisionStyleYoloDetector(context, confidenceThreshold = 0.2f, nmsThreshold = 0.45f,
            resizeMode = YoloResizeMode.LETTERBOX)
        try {
            val baseline = detector.detect(bitmap).map { it.copy(frameTimestampMs = 0) }
            assertTrue("Known image must exercise actual detections", baseline.isNotEmpty())
            for (enabled in listOf(true, false, true)) {
                val sampled = CountDownLatch(1)
                val diagnostics = SystemPerformanceDiagnostics(AndroidSystemMetricSource(context), 5000,
                    SystemClock::elapsedRealtimeNanos, System::currentTimeMillis, {}, {}, {},
                    batteryEnabled = enabled, onBattery = { sampled.countDown() })
                diagnostics.start()
                try {
                    if (enabled) assertTrue(sampled.await(5, TimeUnit.SECONDS))
                    repeat(3) { assertEquals(baseline, detector.detect(bitmap).map { it.copy(frameTimestampMs = 0) }) }
                } finally { diagnostics.stop().get(5, TimeUnit.SECONDS) }
            }
        } finally { detector.close(); bitmap.recycle() }
    }
}
