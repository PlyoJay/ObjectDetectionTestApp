package com.samin.objectdetection.evaluation

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.metrics.FrameTrace
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class RecordingLogTest {
    @Test fun startHandshakeRetainsFramesAndKeepsTimelineEventsOutOfLegacyDetectionFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = EvaluationDataRecorder(context)
        val stem = "test_${System.nanoTime()}"
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val file = recorder.startRecordingLog(stem, "recording-test")
        val base = 1_000_000_000L
        fun trace(id: Long, completed: Long) = FrameTrace("app-test", id, 1000, 0, false, 1000, base,
            completedAtMs = 1100, completedElapsedNs = base + completed * 1_000_000)
        fun frame(id: Long, completed: Long) = DetectionFrameSnapshot(bitmap, emptyList(), frameTimestampMs = 1000,
            imageWidth = 4, imageHeight = 4, roiApplied = false, roi = null, trace = trace(id, completed))
        try {
            recorder.appendRecordingDetections(frame(1, 10)) // Before video start: explicitly excluded.
            recorder.appendRecordingDetections(frame(2, 30)) // After start, before its broadcast: retained.
            recorder.markRecordingStarted("recording-test", 1020, base + 20_000_000)
            recorder.appendRecordingDetections(frame(3, 40))
            recorder.recordError(trace(4, 50).copy(status = "ERROR", errorStage = "INFERENCE", error = "test failure"), 4, 4)
            recorder.recordOverlay(trace(3, 40), 1060, base + 60_000_000, true)
            recorder.stopRecordingLog("FINALIZED", 1070, base + 70_000_000).get(5, TimeUnit.SECONDS)
            val rows = file.readLines().map(::JSONObject)
            assertEquals(listOf(2L, 3L, 4L), rows.map { it.getLong("frameId") })
            assertEquals(listOf(10L, 20L, 30L), rows.map { it.getLong("videoElapsedMs") })
            assertTrue(rows.all { it.getString("recordType") == "FRAME" && it.has("detections") })
            assertEquals("SUCCESS", rows[0].getString("status")); assertEquals("ERROR", rows[2].getString("status"))
            val timeline = File(recorder.recordingsDir, "${stem}_timeline.jsonl").readLines().map(::JSONObject)
            assertEquals(listOf("RECORDING_STARTED", "OVERLAY_DRAW", "RECORDING_STOPPED"), timeline.map { it.getString("recordType") })
            assertEquals(1L, timeline.last().getLong("preStartEventsExcluded"))
        } finally {
            recorder.close().get(5, TimeUnit.SECONDS)
            bitmap.recycle()
            file.delete(); File(recorder.recordingsDir, "${stem}_timeline.jsonl").delete()
        }
    }
}
