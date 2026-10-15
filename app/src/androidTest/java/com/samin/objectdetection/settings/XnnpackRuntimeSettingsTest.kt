package com.samin.objectdetection.settings

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.samin.objectdetection.MainActivity
import com.samin.objectdetection.camera.CameraController
import com.samin.objectdetection.detector.VisionStyleYoloDetector
import com.samin.objectdetection.metrics.PerformanceLogRecorder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Real CameraX lifecycle/logging smoke test. No images saved, FPS improvement is not asserted. */
class XnnpackRuntimeSettingsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun field(target: Any, name: String): Any = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)!!
    private fun call(target: Any, name: String) = instrumentation.runOnMainSync {
        target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target)
    }
    private fun resumedMain(): MainActivity? {
        var result: MainActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().singleOrNull()
            result?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        return result
    }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timed out: $description (unlock device for UI tests)")
    }

    @Test fun cameraDrainAndInterpreterClosePrecedeOnOffOnRecreationAndNewLogSessions() {
        assumeTrue("Requires existing camera permission", context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        val preferences = context.getSharedPreferences("object_detection_app_settings", Context.MODE_PRIVATE)
        val original = preferences.all.toMap()
        val testUris = mutableListOf<Uri>()
        var scenario: ActivityScenario<MainActivity>? = null
        var lastCamera: CameraController? = null
        try {
            // Keep detection defaults and ML Kit; silence physical outputs during automation.
            val settings = AppSettings(warning = WarningSettings(enableActualTts = false, enableActualBeep = false, enableActualVibration = false))
            preferences.edit().clear().putString("applied_settings", AppSettingsCodec.encode(settings)).commit()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            await("initial camera inference") { resumedMain()?.let { (field(it, "detector") as VisionStyleYoloDetector).executionInfo().lastInference == "SUCCESS" } == true }
            val sessionIds = mutableSetOf<String>()
            for ((threads, enabled) in listOf(4 to true, 4 to false, 2 to false, 2 to true, 4 to true)) {
                var activity = requireNotNull(resumedMain())
                val before = field(activity, "appSettings") as AppSettings
                if (before.yolo.interpreterThreadCount != threads || before.yolo.useXnnpack != enabled) {
                    val oldActivity = activity
                    val oldDetector = field(activity, "detector") as VisionStyleYoloDetector
                    val oldCamera = field(activity, "cameraController") as CameraController
                    // UI persistence/drafts are covered in SettingsActivityTest. Here invoke the
                    // same saved-settings restart callback directly while camera inference runs.
                    AppSettingsStore(context).saveApplied(before.copy(yolo = before.yolo.copy(
                        interpreterThreadCount = threads, useXnnpack = enabled)))
                    call(activity, "restartForSettings")
                    await("new Activity with old Interpreter closed") { resumedMain()?.let { it !== oldActivity } == true }
                    activity = requireNotNull(resumedMain())
                    assertTrue("Old detector closed before replacement", field(oldDetector, "closed") as Boolean)
                    instrumentation.runOnMainSync { assertTrue(oldCamera.stopAsync().isDone) }
                    assertNotSame(oldDetector, field(activity, "detector"))
                    assertThrows(IllegalStateException::class.java) {
                        val bitmap = android.graphics.Bitmap.createBitmap(640, 640, android.graphics.Bitmap.Config.ARGB_8888)
                        try { oldDetector.detect(bitmap) } finally { bitmap.recycle() }
                    }
                }
                val detector = field(activity, "detector") as VisionStyleYoloDetector
                lastCamera = field(activity, "cameraController") as CameraController
                await("camera inference threads=$threads xnnpack=$enabled") { detector.executionInfo().lastInference == "SUCCESS" }
                assertEquals(threads, detector.executionInfo().cpuThreads)
                assertEquals(enabled, detector.executionInfo().xnnpackRequested)
                assertEquals("CPU", detector.executionInfo().requestedBackend)
                assertEquals("CPU", detector.executionInfo().appliedBackend)
                assertEquals(0L, detector.executionInfo().inferenceErrors)
                val sessionId = field(activity, "sessionId") as String
                assertTrue("New settings require a new app session", sessionIds.add(sessionId))
                call(activity, "togglePerformanceLogging")
                val recorder = field(activity, "performanceLogRecorder") as PerformanceLogRecorder
                await("log start") { recorder.isRecording }
                val active = field(recorder, "activeSession")
                val uri = field(active, "uri") as Uri
                testUris.add(uri)
                val recorded = field(field(active, "logging"), "sequence") as AtomicLong
                await("three camera frames during log") { recorded.get() >= 3L }
                call(activity, "openSettings")
                assertSame("Settings blocked during active log", activity, resumedMain())
                call(activity, "togglePerformanceLogging")
                await("log drain") { !recorder.isRecording && !(field(activity, "performanceJob") as kotlinx.coroutines.Job).isActive }
                val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                val frames = text.lineSequence().filter { it.startsWith("FRAME ") }.toList()
                assertTrue(frames.isNotEmpty())
                frames.forEach { assertTrue(it.contains("cpuThreads=$threads xnnpackRequested=$enabled")) }
                assertTrue(text.contains("sessionId=$sessionId"))
                assertTrue(text.contains("delegateVerification=UNAVAILABLE_JAVA_API"))
                assertTrue(text.contains("CUMULATIVE_SUMMARY"))
                for (key in listOf("inferenceAvgMs=", "inferenceP95Ms=", "preprocessAvgMs=", "pipelineAvgMs=", "totalProcessingAvgMs=", "inferenceFps=", "getPixels=", "rgbWrite=")) assertTrue(key, text.contains(key))
                assertFalse(text.contains("status=ERROR"))
            }
        } finally {
            scenario?.close()
            lastCamera?.stopAsync()?.get(30, TimeUnit.SECONDS)
            testUris.forEach { context.contentResolver.delete(it, null, null) }
            val editor = preferences.edit().clear()
            original.forEach { (key, value) -> editor.restore(key, value) }
            editor.commit()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun SharedPreferences.Editor.restore(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value); is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value); is Long -> putLong(key, value); is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value as Set<String>)
        }
    }
}
