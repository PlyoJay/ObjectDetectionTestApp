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
import com.samin.objectdetection.detector.YoloModel
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
            for ((model, threads, enabled) in listOf(Triple(YoloModel.YOLO_640, 4, true),
                Triple(YoloModel.YOLO_512, 4, true), Triple(YoloModel.YOLO_640, 4, true),
                Triple(YoloModel.YOLO_640, 4, false), Triple(YoloModel.YOLO_640, 2, false),
                Triple(YoloModel.YOLO_640, 2, true), Triple(YoloModel.YOLO_640, 4, true))) {
                var activity = requireNotNull(resumedMain())
                val before = field(activity, "appSettings") as AppSettings
                if (before.yolo.interpreterThreadCount != threads || before.yolo.useXnnpack != enabled || before.yolo.model != model) {
                    val oldActivity = activity
                    val oldDetector = field(activity, "detector") as VisionStyleYoloDetector
                    val oldCamera = field(activity, "cameraController") as CameraController
                    // UI persistence/drafts are covered in SettingsActivityTest. Here invoke the
                    // same saved-settings restart callback directly while camera inference runs.
                    AppSettingsStore(context).saveApplied(before.copy(yolo = before.yolo.copy(
                        interpreterThreadCount = threads, useXnnpack = enabled, model = model),
                        camera = before.camera.copy(previewEnabled = model != YoloModel.YOLO_512,
                            recognitionRoi = if (model == YoloModel.YOLO_512) com.samin.objectdetection.camera.RecognitionRoi.PRESET
                                else com.samin.objectdetection.camera.RecognitionRoi()),
                        performanceLog = PerformanceLogSettings(systemDiagnosticsEnabled = true, batteryDiagnosticsEnabled = true, sampleIntervalMs = 1000)))
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
                assertEquals(model.assetName, detector.modelIdentity().assetName)
                assertEquals(model.inputSize, detector.modelIdentity().inputWidth)
                assertEquals(model.inputSize, (field(activity, "detectionConfig") as com.samin.objectdetection.camera.DetectionConfig).inputSize)
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
                java.io.File(context.cacheDir, "model-selection-logs").apply { mkdirs() }.let {
                    java.io.File(it, "${model.name}_${threads}_$enabled.txt").writeText(text)
                }
                val frames = text.lineSequence().filter { it.startsWith("FRAME ") }.toList()
                assertTrue(frames.isNotEmpty())
                frames.forEach { assertTrue(it.contains("cpuThreads=$threads xnnpackRequested=$enabled")) }
                assertTrue(text.contains("sessionId=$sessionId"))
                assertTrue(text.contains("Model: ${model.assetName}"))
                assertTrue(text.contains("Model SHA256: ${detector.modelIdentity().sha256}"))
                assertTrue(text.contains("Input Size: ${model.inputSize}x${model.inputSize}"))
                assertTrue(text.contains("Input Tensor: ${detector.modelIdentity().inputShape}"))
                assertTrue(text.contains("Output Tensor: ${detector.modelIdentity().outputShape}"))
                if (model == YoloModel.YOLO_512) {
                    assertTrue(text.contains("cameraPreviewEnabled=false"))
                    assertTrue(text.contains("EDGE_CROP"))
                    assertTrue(text.contains("SYSTEM"))
                }
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
