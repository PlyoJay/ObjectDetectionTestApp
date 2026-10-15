package com.samin.objectdetection.settings

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.camera.YoloResizeMode
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.not
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import android.view.accessibility.AccessibilityNodeInfo

/** Uses only the settings Activity: no camera, recording or inference pipeline is started. */
class SettingsActivityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("object_detection_app_settings", Context.MODE_PRIVATE)
    private lateinit var original: Map<String, *>
    private var scenario: ActivityScenario<SettingsActivity>? = null

    @Before fun setUp() {
        original = preferences.all.toMap()
        preferences.edit().clear().commit()
    }

    @After fun tearDown() {
        scenario?.close()
        // Preserve the connected device's applied settings, presets and rollback markers.
        val editor = preferences.edit().clear()
        original.forEach { (key, value) -> editor.restore(key, value) }
        editor.commit()
    }

    @Test fun everyExistingControlIsReachableInItsCategory() {
        launch()
        assertEquals(61, CATEGORY_FIELDS.values.sumOf { it.size })
        scenario!!.onActivity { activity ->
            fun labels(view: View): List<String> = listOfNotNull(view.tag as? String) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { labels(view.getChildAt(it)) } else emptyList()
            assertEquals(CATEGORY_FIELDS.values.flatten().sorted(), labels(activity.window.decorView).sorted())
        }
        screenshot("home")
        CATEGORY_FIELDS.forEach { (category, fields) ->
            openCategory(category)
            screenshot(category)
            fields.forEach { label -> field(label).perform(scrollTo()).check(matches(isDisplayed())) }
            pressBack()
            onView(withText("객체 인식 설정")).check(matches(isDisplayed()))
        }
        onView(withText("취소")).perform(click())
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun diagnosticsAndIntervalSurviveNavigationRecreationApplyAndReopen() {
        launch()
        openCategory("성능 측정 및 로그")
        field("System Performance Diagnostics").perform(scrollTo(), click())
        field("배터리·발열 진단").perform(scrollTo(), click())
        field("Sample Interval (ms)").perform(scrollTo(), replaceText("7000"), closeSoftKeyboard())
        pressBack()
        openCategory("YOLO 모델 및 추론")
        field("Interpreter Thread Count").perform(scrollTo(), replaceText("5"), closeSoftKeyboard())
        pressBack()
        openCategory("성능 측정 및 로그")
        recreate()
        field("System Performance Diagnostics").check(matches(isChecked()))
        field("Sample Interval (ms)").check(matches(withText("7000")))
        onView(withText("적용 및 저장")).perform(click())
        val saved = loadHealthy()
        assertEquals(PerformanceLogSettings(true, 7000, true), saved.performanceLog)
        assertEquals(5, saved.yolo.interpreterThreadCount)
        launch()
        openCategory("성능 측정 및 로그")
        field("System Performance Diagnostics").check(matches(isChecked())).perform(scrollTo(), click())
        field("Sample Interval (ms)").check(matches(not(isEnabled()))).check(matches(withText("7000")))
        onView(withText("적용 및 저장")).perform(click())
        assertEquals(PerformanceLogSettings(false, 7000, true), loadHealthy().performanceLog)
        launch()
        openCategory("성능 측정 및 로그")
        field("System Performance Diagnostics").check(matches(not(isChecked())))
        field("배터리·발열 진단").check(matches(isChecked())).check(matches(not(isEnabled())))
        field("Sample Interval (ms)").check(matches(withText("7000")))
    }

    @Test fun xnnpackSwitchPersistsOffOnAndThreadSettingsAcrossEditorAndStoreRecreation() {
        for ((threads, enabled) in listOf(4 to false, 2 to false, 2 to true, 4 to true)) {
            launch()
            openCategory("YOLO 모델 및 추론")
            val before = loadHealthy()
            field("Interpreter Thread Count").perform(scrollTo(), replaceText(threads.toString()), closeSoftKeyboard())
            if (before.yolo.useXnnpack != enabled) field("XNNPACK 사용").perform(scrollTo(), click())
            recreate()
            field("XNNPACK 사용").check(matches(if (enabled) isChecked() else not(isChecked())))
            field("Interpreter Thread Count").check(matches(withText(threads.toString())))
            onView(withText("적용 및 저장")).perform(click())
            val restored = AppSettingsStore(context).loadApplied()
            assertEquals(enabled, restored.yolo.useXnnpack)
            assertEquals(threads, restored.yolo.interpreterThreadCount)
            assertEquals(enabled, restored.toDetectionConfig().useXnnpack)
            AppSettingsStore(context).markAppliedHealthy()
            launch()
            onView(withText(org.hamcrest.CoreMatchers.containsString("CPU Threads: $threads · XNNPACK: ${if (enabled) "ON" else "OFF"}")))
                .check(matches(isDisplayed()))
            openCategory("YOLO 모델 및 추론")
            field("XNNPACK 사용").check(matches(if (enabled) isChecked() else not(isChecked())))
            onView(withText("취소")).perform(click())
        }
    }

    @Test fun schemaTwoAndCustomPresetsDefaultXnnpackOnWithoutLosingExistingValues() {
        val old = AppSettings(presetName = "OLD", yolo = YoloSettings(interpreterThreadCount = 2, confidenceThreshold = 0.31f))
        val encoded = AppSettingsCodec.encode(old).lineSequence().filterNot { it.startsWith("yolo.useXnnpack=") }
            .joinToString("\n").replace("schemaVersion=$APP_SETTINGS_SCHEMA_VERSION", "schemaVersion=2")
        preferences.edit().putString("applied_settings", encoded).putString("custom_preset.OLD", encoded)
            .putStringSet("custom_preset_names", setOf("OLD")).commit()
        val store = AppSettingsStore(context)
        assertEquals(old, store.loadApplied())
        assertTrue(store.loadCustomPreset("OLD")!!.yolo.useXnnpack)
        assertEquals(APP_SETTINGS_SCHEMA_VERSION, AppSettingsCodec.storedSchemaVersion(preferences.getString("applied_settings", "")!!))
        val off = old.copy(yolo = old.yolo.copy(useXnnpack = false))
        store.saveCustomPreset("OFF", off)
        assertFalse(AppSettingsStore(context).loadCustomPreset("OFF")!!.yolo.useXnnpack)
        store.saveApplied(off)
        assertEquals(off, AppSettingsStore(context).loadApplied()) // pending -> attempting
        assertEquals(old, AppSettingsStore(context).loadApplied()) // unhealthy runtime -> backup
    }

    @Test fun incompleteDraftSurvivesRecreationAndCancelDoesNotSave() {
        launch()
        openCategory("카메라 및 입력")
        field("Detect Interval (ms)").perform(scrollTo(), replaceText(""), closeSoftKeyboard())
        pressBack()
        openCategory("디버그 및 고급 설정")
        recreate()
        pressBack()
        openCategory("카메라 및 입력")
        field("Detect Interval (ms)").check(matches(withText("")))
        onView(withText("적용 및 저장")).perform(click())
        onView(withText("설정값 확인")).check(matches(isDisplayed()))
        onView(withText("확인")).perform(click())
        onView(withText("취소")).perform(click())
        onView(withText("계속 편집")).perform(click())
        field("Detect Interval (ms)").check(matches(withText("")))
        onView(withText("취소")).perform(click())
        onView(withText("버리기")).perform(click())
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun browsingFocusingAndRevertingAnEditDoNotWriteSettings() {
        val applied = AppSettings(presetName = "SAVED", camera = CameraSettings(requestedWidth = 321, requestedHeight = 241))
        seed(applied)
        val before = preferences.all.toMap()
        launch()
        CATEGORY_FIELDS.keys.forEach { category -> openCategory(category); pressBack() }
        openCategory("YOLO 모델 및 추론")
        field("Confidence Threshold").perform(scrollTo(), click(), closeSoftKeyboard())
        field("Interpreter Thread Count").perform(scrollTo(), replaceText("8"), closeSoftKeyboard())
        field("Interpreter Thread Count").perform(replaceText("4"), closeSoftKeyboard())
        pressBack()
        recreate()
        onView(withText("취소")).perform(click()) // No discard dialog for unchanged settings.
        assertEquals(before, preferences.all)
        launch()
        openCategory("카메라 및 입력")
        field("요청 Camera Resolution").check(matches(withSpinnerText("321x241")))
        onView(withText("적용 및 저장")).perform(click())
        assertEquals(before, preferences.all) // Also no CUSTOM rename or pending apply marker.
    }

    @Test fun builtInAndNamedPresetsStillApplyWithoutChangingOtherValues() {
        val custom = AppSettings(presetName = "UI TEST", performanceLog = PerformanceLogSettings(true, 9000))
        AppSettingsStore(context).saveCustomPreset(custom.presetName, custom)
        launch()
        choosePreset(SettingsPresets.FIELD_TEST_STRETCH)
        openCategory("카메라 및 입력")
        field("Resize Mode").check(matches(withSpinnerText(YoloResizeMode.STRETCH.name)))
        onView(withText("적용 및 저장")).perform(click())
        assertEquals(SettingsPresets.fieldTest(YoloResizeMode.STRETCH), loadHealthy())
        launch()
        choosePreset(custom.presetName)
        openCategory("성능 측정 및 로그")
        field("System Performance Diagnostics").check(matches(isChecked()))
        field("Sample Interval (ms)").check(matches(withText("9000")))
        onView(withText("적용 및 저장")).perform(click())
        assertEquals(custom, loadHealthy())
    }

    @Test fun legacyMigrationPreservesCustomPresetAndRecreationDoesNotAdvanceRollback() {
        val legacyDefault = AppSettings(yolo = YoloSettings(resizeMode = YoloResizeMode.STRETCH))
        val legacy = AppSettingsCodec.encode(legacyDefault).replace("schemaVersion=$APP_SETTINGS_SCHEMA_VERSION", "schemaVersion=1")
        val store = AppSettingsStore(context)
        val custom = legacyDefault.copy(presetName = "LEGACY", performanceLog = PerformanceLogSettings(true, 10000))
        store.saveCustomPreset(custom.presetName, custom)
        preferences.edit().putString("applied_settings", legacy).commit()
        launch()
        assertEquals(AppSettings(), AppSettingsCodec.decode(preferences.getString("applied_settings", "")!!))
        assertEquals(custom, store.loadCustomPreset(custom.presetName))
        assertEquals(APP_SETTINGS_SCHEMA_VERSION, AppSettingsCodec.storedSchemaVersion(preferences.getString("applied_settings", "")!!))
        scenario!!.close(); scenario = null
        store.saveApplied(custom)
        launch() // pending -> attempting, as with the existing store contract
        val before = preferences.all.toMap()
        recreate()
        assertEquals(before, preferences.all)
        openCategory("성능 측정 및 로그")
        field("Sample Interval (ms)").check(matches(withText("10000")))
    }

    @Test fun customPresetSaveRenameDeleteAndResetKeepTheirExistingMeaning() {
        launch()
        openCategory("카메라 및 입력")
        field("Detect Interval (ms)").perform(scrollTo(), replaceText("123"), closeSoftKeyboard())
        pressBack()
        onView(withHint("사용자 프리셋 이름")).perform(scrollTo(), replaceText("UI PRESET"), closeSoftKeyboard())
        onView(withText("프리셋 저장")).perform(scrollTo(), click())
        val store = AppSettingsStore(context)
        assertEquals(123L, store.loadCustomPreset("UI PRESET")!!.camera.detectIntervalMs)
        assertFalse(preferences.contains("applied_settings"))
        onView(withHint("사용자 프리셋 이름")).perform(scrollTo(), replaceText("RENAMED"), closeSoftKeyboard())
        onView(withText("이름 변경")).perform(scrollTo(), click())
        assertNull(store.loadCustomPreset("UI PRESET"))
        assertEquals(123L, store.loadCustomPreset("RENAMED")!!.camera.detectIntervalMs)
        onView(withText("삭제")).perform(scrollTo(), click())
        assertTrue(store.customPresetNames().isEmpty())
        onView(withText("카메라 및 입력")).perform(scrollTo(), click())
        field("Detect Interval (ms)").check(matches(withText("0")))
        field("Detect Interval (ms)").perform(scrollTo(), replaceText("200"), closeSoftKeyboard())
        onView(withText("초기화")).perform(click())
        field("Detect Interval (ms)").check(matches(withText("0")))
        onView(withText("취소")).perform(click())
        assertFalse(preferences.contains("applied_settings"))
    }

    @Test fun categoryScrollIsRetainedAcrossNavigationAndRecreation() {
        launch()
        openCategory("탐지 필터 및 경고")
        field("실제 TTS").perform(scrollTo())
        val offset = scrollOffset("실제 TTS")
        assertTrue(offset > 0)
        pressBack()
        openCategory("YOLO 모델 및 추론")
        recreate() // Filter page is hidden when its scroll position is saved.
        pressBack()
        openCategory("탐지 필터 및 경고")
        field("실제 TTS").check(matches(isDisplayed()))
        assertEquals(offset, scrollOffset("실제 TTS"))
        recreate()
        field("실제 TTS").check(matches(isDisplayed()))
        assertEquals(offset, scrollOffset("실제 TTS"))
    }

    private fun scrollOffset(label: String): Int {
        var offset = 0
        field(label).check { view, error ->
            if (error != null) throw error
            var parent = view.parent
            while (parent !is ScrollView) parent = parent.parent
            offset = parent.scrollY
        }
        return offset
    }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("settingsScreenshots") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(context.cacheDir, "settings-ui-test-screenshots").apply { mkdirs() }
        val filename = if (name == "home") "home" else "category-${CATEGORY_FIELDS.keys.indexOf(name) + 1}"
        File(directory, "$filename.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun launch() {
        scenario?.close()
        scenario = ActivityScenario.launch(SettingsActivity::class.java)
        scenario!!.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        dismissNativeCompatibilityWarning()
    }

    private fun recreate() {
        scenario!!.recreate()
        dismissNativeCompatibilityWarning()
    }

    /** Confirm only Android's known native-library alignment warning during UI tests.
     * No permanent suppression, dependency upgrades or runtime compatibility changes. */
    private fun dismissNativeCompatibilityWarning() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(20) {
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString() == "android" &&
                root.findAccessibilityNodeInfosByText("ELF").isNotEmpty()) {
                val ok = context.getString(android.R.string.ok)
                val confirm = root.findAccessibilityNodeInfosByText(ok).firstOrNull { it.text?.toString() == ok }
                if (confirm?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            }
            Thread.sleep(100)
        }
    }

    private fun seed(value: AppSettings) {
        preferences.edit().putString("applied_settings", AppSettingsCodec.encode(value)).commit()
    }

    private fun loadHealthy(): AppSettings = AppSettingsStore(context).let { store ->
        store.loadApplied().also { store.markAppliedHealthy() }
    }

    private fun openCategory(title: String) { onView(withText(title)).perform(scrollTo(), click()) }
    private fun field(label: String) = onView(withTagValue(`is`(label as Any)))

    private fun choosePreset(name: String) {
        onView(allOf(isAssignableFrom(android.widget.Spinner::class.java), withEffectiveVisibility(Visibility.VISIBLE)))
            .perform(scrollTo(), click())
        onData(`is`(name)).perform(click())
    }

    @Suppress("UNCHECKED_CAST")
    private fun SharedPreferences.Editor.restore(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value as Set<String>)
        }
    }

    companion object {
        private val CATEGORY_FIELDS = linkedMapOf(
            "카메라 및 입력" to listOf("Detect Interval (ms)", "Center Square ROI (OFF = Full Frame)", "Resize Mode", "요청 Camera Resolution"),
            "YOLO 모델 및 추론" to listOf("Confidence Threshold", "NMS Threshold", "Interpreter Thread Count", "XNNPACK 사용", "Max Candidates"),
            "탐지 필터 및 경고" to listOf(
                "Size Filter Mode", "Geometry Filter", "Min Box Area Ratio", "Min Box Width Ratio", "Min Box Height Ratio",
                "Bollard Min Area Ratio", "Bollard Max Width/Height", "Adaptive Temporal", "Temporal Immediate Confidence",
                "Temporal Confirmation Confidence", "Temporal Match IoU", "Max History Size", "Min History Size",
                "Max Match Distance Ratio", "Min Sample Interval (ms)", "Stale Track Timeout (ms)", "Min Absolute Area Change",
                "Min Relative Area Change", "Min Absolute Height Change", "Min Relative Height Change", "ML Kit Detection",
                "Detection Interval (ms)", "Warning Cooldown (ms)", "Ignore Top Ratio", "Max Guide Object Count",
                "Very Near Height Ratio", "Near Height Ratio", "Mid Height Ratio", "Very Near Area Ratio", "Near Area Ratio",
                "Mid Area Ratio", "실제 진동", "실제 Beep", "실제 TTS"
            ),
            "성능 측정 및 로그" to listOf("System Performance Diagnostics", "배터리·발열 진단", "Sample Interval (ms)"),
            "디버그 및 고급 설정" to listOf(
                "Enable Detector Diagnostics", "Debug Detection Logging", "Diagnostic Raw Confidence Threshold",
                "Diagnostic Max Raw Candidates", "Debug Save Inference Input", "Debug Save Interval (ms)",
                "Debug Save On Detection", "기존 호환 Debug Image", "Overlay", "Overlay Debug Mode", "YOLO bbox",
                "ML Kit bbox", "Confidence 표시", "FPS 표시", "Overlay Stale Timeout (ms)"
            )
        )
    }
}
