package com.samin.objectdetection.settings

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.mlkit.MlKitDiagnosticLogRecorder
import com.samin.objectdetection.mlkit.MlKitDiagnosticLogs
import org.hamcrest.CoreMatchers.not
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class MlKitLogControlsTest {
    @Test fun startRecreateStopPreviewAndShareUseExistingDebugSectionWithoutChangingSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("object_detection_app_settings", Context.MODE_PRIVATE)
        val backup = HashMap(preferences.all)
        val value = AppSettings(debug = DebugSettings(enableMlKitDiagnostics = true))
        val encoded = AppSettingsCodec.encode(value)
        val recorder = MlKitDiagnosticLogs.get(context)
        var name: String? = null
        var sharedUri: Uri? = null
        var chooser: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                chooser = intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            recorder.stop().get(5, TimeUnit.SECONDS)
            preferences.edit().clear().putString("applied_settings", encoded).commit()
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                dismissCompatibilityWarning()
                openSettingsCategory("디버그 및 고급 설정")
                awaitSettingsViewLayout(withText("ML Kit 진단 로그 기록 시작"))
                await { onView(withText("ML Kit 진단 로그 기록 시작")).check(matches(isEnabled())); true }
                onView(withText("ML Kit 진단 로그 기록 시작")).perform(scrollTo(), click())
                await { recorder.status().phase == MlKitDiagnosticLogRecorder.Phase.RECORDING }
                name = recorder.status().fileName
                recorder.record("settings-test", "[MLKIT_DIAG] stage=OVERLAY_DRAW sdkCount=2 한글")
                scenario.recreate()
                dismissCompatibilityWarning()
                assertEquals(MlKitDiagnosticLogRecorder.Phase.RECORDING, recorder.status().phase)
                awaitSettingsViewLayout(withText("기록 중지 및 파일 저장"))
                await { onView(withText("기록 중지 및 파일 저장")).check(matches(isEnabled())); true }
                onView(withText("기록 중지 및 파일 저장")).perform(scrollTo(), click())
                await { recorder.status().phase == MlKitDiagnosticLogRecorder.Phase.SAVED }
                await { onView(withText("최근 ML Kit 로그 공유")).check(matches(isEnabled())); true }
                onView(withText("최근 ML Kit 로그 공유")).perform(scrollTo(), click())
                await { chooser != null }
                val send = checkNotNull(chooser!!.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
                assertEquals(Intent.ACTION_SEND, send.action)
                sharedUri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                val text = context.contentResolver.openInputStream(checkNotNull(sharedUri))!!.bufferedReader().use { it.readText() }
                assertTrue(text.contains("sdkCount=2 한글")); assertTrue(text.contains("END endedAt="))
                assertTrue(text.contains("debug.enableMlKitDiagnostics=true"))
                assertEquals(encoded, preferences.getString("applied_settings", null))
                onView(withText("최근 ML Kit 기록 보기")).perform(scrollTo(), click())
                onView(withText("ML Kit 진단 기록")).check(matches(isDisplayed()))
                onView(withText("${name} · ${recorder.files().get(5, TimeUnit.SECONDS).first { it.name == name }.bytes} bytes")).perform(click())
                onView(withText("공유")).check(matches(isDisplayed()))
                onView(withText("닫기")).perform(click())
            }
            // Applied OFF keeps the start button disabled; opening settings only lists saved files.
            preferences.edit().putString("applied_settings", AppSettingsCodec.encode(AppSettings())).commit()
            ActivityScenario.launch(SettingsActivity::class.java).use {
                dismissCompatibilityWarning()
                openSettingsCategory("디버그 및 고급 설정")
                awaitSettingsViewLayout(withText("ML Kit 진단 로그 기록 시작"))
                onView(withText("ML Kit 진단 로그 기록 시작")).check(matches(not(isEnabled())))
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            recorder.stop().get(5, TimeUnit.SECONDS)
            name?.let { recorder.delete(it).get(5, TimeUnit.SECONDS) }
            sharedUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            val restore = preferences.edit().clear()
            backup.forEach { (key, item) -> when (item) {
                is String -> restore.putString(key, item)
                is Boolean -> restore.putBoolean(key, item)
                is Int -> restore.putInt(key, item)
                is Long -> restore.putLong(key, item)
                is Float -> restore.putFloat(key, item)
                is Set<*> -> restore.putStringSet(key, item.filterIsInstance<String>().toSet())
            } }
            restore.commit()
        }
    }

    private fun await(check: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        var failure: Throwable? = null
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            try { if (check()) return } catch (error: AssertionError) { failure = error }
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for log UI/state", failure)
    }

    private fun dismissCompatibilityWarning() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(20) {
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString() == "android" && root.findAccessibilityNodeInfosByText("ELF").isNotEmpty()) {
                val ok = InstrumentationRegistry.getInstrumentation().targetContext.getString(android.R.string.ok)
                if (root.findAccessibilityNodeInfosByText(ok).firstOrNull { it.text?.toString() == ok }
                        ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) return
            }
            Thread.sleep(100)
        }
    }
}
