package com.samin.objectdetection.mlkit

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.MainActivity
import com.samin.objectdetection.camera.RecognitionRoi
import com.samin.objectdetection.detector.YoloModel
import com.samin.objectdetection.settings.*
import com.samin.objectdetection.ui.MainScreenView
import org.hamcrest.CoreMatchers.not
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Real MainActivity/camera/SDK, using an isolated test applicationId when run on devices. */
class MlKitMainScreenLoggingTest {
    @Test fun appliedOffOneTouchPreviewOnSettingsAndRecreation() = exercise(preview = true)
    @Test fun appliedOffOneTouchPreviewOffAndPerformanceExclusion() = exercise(preview = false)

    private fun exercise(preview: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("object_detection_app_settings", Context.MODE_PRIVATE)
        val backup = HashMap(preferences.all)
        val recorder = MlKitDiagnosticLogs.get(context)
        val initialNames = recorder.files().get(10, TimeUnit.SECONDS).map { it.name }.toSet()
        val initialPerformance = performanceFiles(context)
        val settings = AppSettings(camera = CameraSettings(previewEnabled = preview,
            recognitionRoi = RecognitionRoi(true, top = .20f, left = .10f, right = .10f)),
            yolo = YoloSettings(model = YoloModel.YOLO_512, interpreterThreadCount = 2),
            warning = WarningSettings(enableActualVibration = false, enableActualBeep = false, enableActualTts = false))
        var chooser: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                chooser = intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            listOf(android.Manifest.permission.CAMERA, android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.POST_NOTIFICATIONS).forEach {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
            }
            recorder.stop().get(10, TimeUnit.SECONDS)
            preferences.edit().clear().putString("applied_settings", AppSettingsCodec.encode(settings)).commit()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            dismissCompatibilityWarning()
            await { onView(withText("ML Kit 로그 시작")).check(matches(isEnabled())); true }
            // Two reentrant requests during preparation must produce one recording.
            scenario.onActivity { activity -> screen(activity).mlKitLogButton.let { it.performClick(); it.performClick() } }
            await { recorder.status().phase == MlKitDiagnosticLogRecorder.Phase.RECORDING }
            val first = checkNotNull(recorder.status().fileName)
            await {
                File(context.filesDir, "mlkit_diagnostics/$first").readText().contains("event=TASK_COMPLETE")
            }
            await {
                var ticking = false
                checkNotNull(scenario).onActivity { ticking = screen(it).mlKitRecordingTextView.text.toString().let { text ->
                    text.startsWith("ML KIT REC ") && !text.contains("00:00")
                } }
                ticking
            }
            onView(withText("성능 로그 시작")).check(matches(not(isEnabled())))
            onView(withText("ML Kit 로그 종료")).perform(scrollTo(), click())
            await { recorder.status().phase == MlKitDiagnosticLogRecorder.Phase.SAVED }
            await { onView(withText("ML Kit 로그 시작")).check(matches(isEnabled())); true }
            val text = File(context.filesDir, "mlkit_diagnostics/$first").readText()
            assertTrue(text.contains("debug.enableMlKitDiagnostics=false"))
            assertTrue(text.contains("recording.temporaryDiagnostics=true"))
            assertTrue(text.contains("camera.previewEnabled=$preview"))
            assertTrue(text.contains("event=PROCESS_REQUEST")); assertTrue(text.contains("sdkCount="))
            assertTrue(text.contains("END endedAt=")); assertTrue(text.contains("droppedLines="))
            await { onView(withText("ML Kit 로그 공유")).check(matches(isEnabled())); true }
            onView(withText("ML Kit 로그 공유")).perform(scrollTo(), click())
            await { chooser != null }
            val send = chooser!!.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
            val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
            assertEquals(text, context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            assertEquals(settings, AppSettingsStore(context).loadApplied())

            // P0 remains usable, and changing file diagnostic overhead during P0 is blocked.
            await { onView(withText("성능 로그 시작")).check(matches(isEnabled())); true }
            onView(withText("성능 로그 시작")).perform(scrollTo(), click())
            await { onView(withText("성능 로그 종료")).check(matches(isEnabled())); true }
            await { onView(withText("ML Kit 로그 시작")).check(matches(not(isEnabled()))); true }
            onView(withText("ML Kit 로그 공유")).check(matches(not(isEnabled())))
            Thread.sleep(2500)
            onView(withText("성능 로그 종료")).perform(scrollTo(), click())
            await { onView(withText("성능 로그 시작")).check(matches(isEnabled())); true }
            val p0 = performanceFiles(context) - initialPerformance
            assertEquals(1, p0.size)
            val p0Text = context.contentResolver.openInputStream(p0.single())!!.bufferedReader().use { it.readText() }
            assertTrue(p0Text.contains("FRAME ")); assertTrue(p0Text.contains("LATENCY |"))

            onView(withText("ML Kit 로그 시작")).perform(scrollTo(), click())
            await { recorder.status().phase == MlKitDiagnosticLogRecorder.Phase.RECORDING }
            assertNotEquals(first, recorder.status().fileName)
            if (preview) {
                onView(withContentDescription("상세 설정")).perform(click())
                dismissCompatibilityWarning()
                onView(withText("디버그 및 고급 설정")).perform(scrollTo(), click())
                await { onView(withText("기록 중지 및 파일 저장")).check(matches(isEnabled())); true }
                onView(withText("ML Kit Diagnostics")).check(matches(not(isChecked())))
                onView(withText("기록 중지 및 파일 저장")).perform(scrollTo(), click())
                await { recorder.status().phase == MlKitDiagnosticLogRecorder.Phase.SAVED }
                pressBack(); pressBack()
                await { onView(withText("ML Kit 로그 시작")).check(matches(isEnabled())); true }
                onView(withText("ML Kit 로그 시작")).perform(scrollTo(), click())
                await { recorder.status().phase == MlKitDiagnosticLogRecorder.Phase.RECORDING }
                scenario.recreate()
                dismissCompatibilityWarning()
                await { recorder.status().phase == MlKitDiagnosticLogRecorder.Phase.SAVED }
                await { onView(withText("ML Kit 로그 시작")).check(matches(isEnabled())); true }
            }
            scenario.close(); scenario = null
            await { recorder.status().phase == MlKitDiagnosticLogRecorder.Phase.SAVED }
            // Relaunching does not restore the temporary opt-in or modify applied OFF.
            scenario = ActivityScenario.launch(MainActivity::class.java)
            dismissCompatibilityWarning()
            await { onView(withText("ML Kit 로그 시작")).check(matches(isEnabled())); true }
            assertEquals(settings, AppSettingsStore(context).loadApplied())
            assertEquals(MlKitDiagnosticLogRecorder.Phase.SAVED, recorder.status().phase)
        } finally {
            scenario?.close()
            recorder.stop().get(20, TimeUnit.SECONDS)
            recorder.files().get(10, TimeUnit.SECONDS).filter { it.name !in initialNames }.forEach { recorder.delete(it.name).get(10, TimeUnit.SECONDS) }
            (performanceFiles(context) - initialPerformance).forEach { context.contentResolver.delete(it, null, null) }
            instrumentation.removeMonitor(monitor)
            val restore = preferences.edit().clear()
            backup.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
                is Float -> restore.putFloat(key, value)
                is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            restore.commit()
        }
    }

    private fun screen(activity: MainActivity) = MainActivity::class.java.getDeclaredField("screen").apply { isAccessible = true }.get(activity) as MainScreenView
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 40_000
        var failure: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try { if (check()) return } catch (error: AssertionError) { failure = error }
            catch (error: androidx.test.espresso.NoMatchingViewException) { failure = error }
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for Main log UI/SDK", failure)
    }
    private fun performanceFiles(context: Context): Set<Uri> = buildSet {
        val collection = android.provider.MediaStore.Files.getContentUri("external")
        context.contentResolver.query(collection, arrayOf("_id"), "relative_path=?", arrayOf("Documents/GOTORO/performance/"), null)?.use { cursor ->
            while (cursor.moveToNext()) add(android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)))
        }
    }
    private fun dismissCompatibilityWarning() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(30) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root?.packageName?.toString() == "android" && root.findAccessibilityNodeInfosByText("ELF").isNotEmpty()) {
                val ok = instrumentation.targetContext.getString(android.R.string.ok)
                if (root.findAccessibilityNodeInfosByText(ok).firstOrNull { it.text?.toString() == ok }
                        ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) return
            }
            Thread.sleep(100)
        }
    }
}
