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

/** Preserves applied settings/presets; the preview integration test also starts real inference. */
class SettingsActivityTest {
    @Test fun modelChoiceSurvivesDraftRecreationApplyReopenAndCustomPreset() {
        launch()
        openCategory("YOLO 모델 및 추론")
        val model = com.samin.objectdetection.detector.YoloModel.YOLO_512
        field("모델 선택").perform(scrollTo(), click())
        onData(`is`(model.displayName)).perform(click())
        recreate()
        field("모델 선택").check(matches(withSpinnerText(model.displayName)))
        onView(withText("적용 및 저장")).perform(click())
        awaitEditorFinished()
        val saved = loadHealthy()
        assertEquals(model, saved.yolo.model)
        val store = AppSettingsStore(context)
        store.saveCustomPreset("MODEL 512", saved)
        assertEquals(model, AppSettingsStore(context).loadCustomPreset("MODEL 512")!!.yolo.model)
        launch()
        openCategory("YOLO 모델 및 추론")
        field("모델 선택").check(matches(withSpinnerText(model.displayName)))
    }

    @Test fun schemaFivePresetsAndFailedModelApplyRestoreEntirePreviousConfiguration() {
        val previous = AppSettings(presetName = "OLD MODEL", yolo = YoloSettings(confidenceThreshold = .31f))
        val old = AppSettingsCodec.encode(previous).lineSequence().filterNot { it.startsWith("yolo.model=") }
            .joinToString("\n").replace("schemaVersion=$APP_SETTINGS_SCHEMA_VERSION", "schemaVersion=5")
        preferences.edit().putString("applied_settings", old).putString("custom_preset.OLD MODEL", old)
            .putStringSet("custom_preset_names", setOf("OLD MODEL")).commit()
        val store = AppSettingsStore(context)
        assertEquals(previous, store.loadApplied())
        assertEquals(previous, store.loadCustomPreset("OLD MODEL"))
        val next = previous.copy(yolo = previous.yolo.copy(model = com.samin.objectdetection.detector.YoloModel.YOLO_512))
        store.saveApplied(next)
        assertEquals(next, store.loadApplied())
        assertEquals(previous, store.restorePreviousApplied())
        assertEquals(previous, store.loadApplied())
        assertEquals(previous, store.loadCustomPreset("OLD MODEL"))
        assertNull(store.restorePreviousApplied())
    }

    @Test fun roiPresetValidationAndDurationStayInTheirCategoriesAndPersist() {
        seed(AppSettings(camera = CameraSettings(useCenterSquareCrop = true), yolo = YoloSettings(resizeMode = YoloResizeMode.STRETCH)))
        launch()
        openCategory("카메라 및 입력")
        onView(withText("크롭 프리셋: 상단 10% / 좌우 5% / 하단 0%")).perform(scrollTo(), click())
        field("인식 영역 크롭 사용").check(matches(isChecked()))
        field("Center Square ROI (OFF = Full Frame)").check(matches(not(isEnabled()))).check(matches(not(isChecked())))
        field("Resize Mode").check(matches(not(isEnabled()))).check(matches(withSpinnerText("LETTERBOX")))
        field("상단 크롭 (%)").perform(scrollTo(), replaceText("99"), closeSoftKeyboard())
        field("하단 크롭 (%)").perform(scrollTo(), replaceText("2"), closeSoftKeyboard())
        onView(withText("적용 및 저장")).perform(click())
        onView(withText("설정값 확인")).check(matches(isDisplayed()))
        onView(withText("확인")).perform(click())
        field("상단 크롭 (%)").perform(scrollTo(), replaceText("10"), closeSoftKeyboard())
        field("하단 크롭 (%)").perform(scrollTo(), replaceText("0"), closeSoftKeyboard())
        pressBack()
        openCategory("성능 측정 및 로그")
        field("테스트 시간 (분)").perform(scrollTo(), click())
        onData(`is`("3")).perform(click())
        onView(withText("적용 및 저장")).perform(click())
        val restored = loadHealthy()
        assertEquals(com.samin.objectdetection.camera.RecognitionRoi.PRESET, restored.camera.recognitionRoi)
        assertEquals(3, restored.performanceLog.durationMinutes)
        assertFalse(restored.camera.useCenterSquareCrop)
        assertEquals(YoloResizeMode.LETTERBOX, restored.yolo.resizeMode)
    }

    @Test fun threeMinuteAutoStopSavesAndUnlocksForBothPreviewAndThreadModes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        listOf(android.Manifest.permission.CAMERA, android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.POST_NOTIFICATIONS).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        val collection = android.provider.MediaStore.Files.getContentUri("external")
        fun logs(): Set<android.net.Uri> = buildSet {
            context.contentResolver.query(collection, arrayOf("_id"), "relative_path=?",
                arrayOf("Documents/GOTORO/performance/"), null)?.use { cursor ->
                while (cursor.moveToNext()) add(android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)))
            }
        }
        val modes = when (InstrumentationRegistry.getArguments().getString("timerRoiPreview")) {
            "off" -> listOf(false to 4)
            "on" -> listOf(true to 1)
            else -> listOf(true to 1, false to 4)
        }
        for ((preview, threads) in modes) {
            val before = logs()
            seed(AppSettings(camera = CameraSettings(previewEnabled = preview,
                recognitionRoi = com.samin.objectdetection.camera.RecognitionRoi.PRESET),
                yolo = YoloSettings(interpreterThreadCount = threads),
                warning = WarningSettings(enableActualVibration = false, enableActualBeep = false, enableActualTts = false),
                performanceLog = PerformanceLogSettings(!preview, 1000, !preview, 3)))
            val main = ActivityScenario.launch(com.samin.objectdetection.MainActivity::class.java)
            try {
                main.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                dismissNativeCompatibilityWarning()
                onView(withText("성능 로그 시작")).perform(click())
                val deadline = android.os.SystemClock.elapsedRealtime() + 200_000
                var completed = false
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    android.os.SystemClock.sleep(1000)
                    main.onActivity { activity ->
                        val buttons = mutableListOf<android.widget.Button>()
                        fun visit(view: View) {
                            if (view is android.widget.Button) buttons.add(view)
                            if (view is ViewGroup) (0 until view.childCount).forEach { visit(view.getChildAt(it)) }
                        }
                        visit(activity.window.decorView)
                        completed = buttons.any { it.text == "성능 로그 시작" && it.isEnabled }
                        if (!completed) {
                            assertFalse(buttons.single { it.contentDescription == "상세 설정" }.isEnabled)
                            assertFalse(buttons.single { it.text == "빠른 설정" }.isEnabled)
                        }
                    }
                    if (completed) break
                }
                assertTrue("Timed session did not finish", completed)
                val created = logs() - before
                assertEquals(1, created.size)
                val text = context.contentResolver.openInputStream(created.single())!!.bufferedReader().use { it.readText() }
                File(context.cacheDir, "timer-roi-auto-$preview-$threads.txt").writeText(text)
                assertTrue(text.contains("configuredDurationMs=180000"))
                assertTrue(text.contains("stopReason=AUTO_DURATION"))
                val actual = Regex("actualDurationMs=(\\d+)").find(text)!!.groupValues[1].toLong()
                android.util.Log.i("TimerRoiValidation", "preview=$preview threads=$threads actualDurationMs=$actual " +
                    text.lineSequence().first { it.startsWith("TEST_SESSION |") })
                assertTrue("actual=$actual", actual in 180_000L..190_000L)
                assertTrue(text.contains("cameraPreviewEnabled=$preview"))
                assertTrue(text.contains("YOLO Threads: $threads"))
                assertTrue(text.contains("mode=EDGE_CROP")); assertTrue(text.contains("camera.roi.top=0.1"))
                assertTrue(text.lineSequence().count { it.startsWith("FRAME ") && it.contains("roi=[") } >= 3)
                assertFalse(text.contains("ERROR |"))
                if (!preview) { assertTrue(text.contains("BATTERY_SUMMARY")); assertTrue(text.contains("SYSTEM |")) }
                onView(withContentDescription("상세 설정")).check(matches(isEnabled()))
            } finally {
                main.close()
                (logs() - before).forEach { context.contentResolver.delete(it, null, null) }
            }
        }
    }

    @Test fun previewModesKeepRealInferenceDiagnosticsAndPerformanceLogSettingsLock() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        listOf(android.Manifest.permission.CAMERA, android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.POST_NOTIFICATIONS).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        val collection = android.provider.MediaStore.Files.getContentUri("external")
        fun performanceLogs(): Set<android.net.Uri> = buildSet {
            context.contentResolver.query(collection, arrayOf("_id"), "relative_path=?",
                arrayOf("Documents/GOTORO/performance/"), null)?.use { cursor ->
                while (cursor.moveToNext()) add(android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)))
            }
        }
        fun awaitControl(label: String) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            var last: Throwable? = null
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try { onView(withText(label)).check(matches(isEnabled())); return }
                catch (error: AssertionError) { last = error }
                catch (error: androidx.test.espresso.NoMatchingViewException) { last = error }
                android.os.SystemClock.sleep(200)
            }
            throw AssertionError("Control unavailable: $label", last)
        }
        for (previewEnabled in listOf(false, true)) {
            val before = performanceLogs()
            seed(AppSettings(camera = CameraSettings(previewEnabled = previewEnabled),
                warning = WarningSettings(enableActualVibration = false, enableActualBeep = false, enableActualTts = false),
                overlay = OverlaySettings(debugMode = com.samin.objectdetection.ui.OverlayDebugMode.FULL),
                performanceLog = PerformanceLogSettings(true, 1000, true)))
            val main = ActivityScenario.launch(com.samin.objectdetection.MainActivity::class.java)
            try {
                dismissNativeCompatibilityWarning()
                onView(withText("성능 로그 시작")).perform(click())
                awaitControl("성능 로그 종료")
                onView(withContentDescription("상세 설정")).perform(click())
                onView(withText("객체 인식 설정")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                onView(withText("빠른 설정")).perform(click())
                onView(withHint("Confidence (0..1)")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                main.onActivity { assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, it.lifecycle.currentState) }
                android.os.SystemClock.sleep(6500)
                if (!previewEnabled) onView(withText("화면 표시 OFF — 객체 인식 실행 중")).check(matches(isDisplayed()))
                onView(withText("성능 로그 종료")).perform(click())
                awaitControl("성능 로그 시작")
                val created = performanceLogs() - before
                assertEquals(1, created.size)
                val text = context.contentResolver.openInputStream(created.single())!!.bufferedReader().use { it.readText() }
                File(context.cacheDir, "preview-validation-$previewEnabled.txt").writeText(text)
                assertTrue(text.contains("cameraPreviewEnabled=$previewEnabled"))
                assertTrue(text.contains("configuredDurationMs=300000"))
                assertTrue(text.contains("stopReason=MANUAL"))
                assertTrue(text.lineSequence().count { it.startsWith("FRAME ") && it.contains("status=SUCCESS") } >= 3)
                assertTrue(text.contains("SYSTEM |")); assertTrue(text.contains("BATTERY_SAMPLE |"))
                assertTrue(text.contains("Failed Frames: 0"))
                assertFalse(text.contains("ERROR |"))
                if (!previewEnabled) {
                    assertTrue(text.contains("NOT_DISPLAYED_PREVIEW_OFF"))
                    assertTrue(text.contains("Overlay Latency Samples: 0"))
                    assertFalse(text.contains("OVERLAY |"))
                } else assertTrue(text.contains("OVERLAY |"))
                // The same settings entry works after the performance session has drained.
                onView(withContentDescription("상세 설정")).perform(click())
                dismissNativeCompatibilityWarning()
                onView(withText("객체 인식 설정")).check(matches(isDisplayed()))
                pressBack()
            } finally {
                main.close()
                (performanceLogs() - before).forEach { context.contentResolver.delete(it, null, null) }
            }
        }
    }

    @Test fun previewVisibilitySuppressesDebugAndBoxesWhileKeepingRecordingAndLogControls() {
        for (enabled in listOf(false, true)) {
            launch()
            scenario!!.onActivity { activity ->
                var recordings = 0; var logs = 0
                val screen = com.samin.objectdetection.ui.MainScreenView(activity,
                    com.samin.objectdetection.ui.OverlayDebugMode.FULL, "TEST", "Model", true, false,
                    {}, { recordings++ }, { logs++ }, {}, {}, cameraPreviewEnabled = enabled)
                activity.setContentView(screen.root)
                assertEquals(if (enabled) View.VISIBLE else View.GONE, screen.previewView.visibility)
                assertEquals(if (enabled) View.VISIBLE else View.GONE, screen.overlayView.visibility)
                assertEquals(if (enabled) View.VISIBLE else View.GONE, screen.debugTextView.visibility)
                assertEquals(if (enabled) View.VISIBLE else View.GONE, screen.presetTextView.visibility)
                assertEquals(if (enabled) View.GONE else View.VISIBLE, screen.previewStatusTextView.visibility)
                assertEquals(View.GONE, screen.warningMessageTextView.visibility)
                screen.updatePreviewStatus("객체 인식 실행 중")
                if (!enabled) assertEquals("화면 표시 OFF — 객체 인식 실행 중", screen.previewStatusTextView.text.toString())
                screen.recordingButton.performClick(); screen.performanceLogButton.performClick()
                assertEquals(1, recordings); assertEquals(1, logs)
                assertTrue(screen.recordingButton.isEnabled); assertTrue(screen.performanceLogButton.isEnabled)
                fun buttons(view: View): List<android.widget.Button> =
                    (if (view is android.widget.Button) listOf(view) else emptyList()) +
                        (if (view is ViewGroup) (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) } else emptyList())
                val overlay = buttons(screen.root).first { it.text == "Overlay ON" }
                assertEquals(enabled, overlay.isEnabled)
                if (enabled) {
                    overlay.performClick()
                    assertEquals("Overlay OFF", overlay.text.toString())
                    assertEquals(View.VISIBLE, screen.previewView.visibility)
                    overlay.performClick()
                    assertEquals("Overlay ON", overlay.text.toString())
                }
            }
        }
    }

    @Test fun previewSwitchSurvivesRecreationApplyReopenAndCustomPresetWithoutChangingOverlay() {
        for (enabled in listOf(false, true)) {
            launch()
            openCategory("카메라 및 입력")
            field("카메라 화면 표시 (Preview)").perform(scrollTo(), click())
            recreate()
            field("카메라 화면 표시 (Preview)").check(matches(if (enabled) isChecked() else not(isChecked())))
            onView(withText("적용 및 저장")).perform(click())
            val saved = loadHealthy()
            assertEquals(enabled, saved.camera.previewEnabled)
            assertTrue(saved.overlay.enabled)
            assertEquals(AppSettings().toDetectionConfig(), saved.toDetectionConfig())
            val store = AppSettingsStore(context)
            store.saveCustomPreset("PREVIEW", saved)
            assertEquals(enabled, AppSettingsStore(context).loadCustomPreset("PREVIEW")!!.camera.previewEnabled)
            launch()
            openCategory("카메라 및 입력")
            field("카메라 화면 표시 (Preview)").check(matches(if (enabled) isChecked() else not(isChecked())))
            onView(withText("취소")).perform(click())
        }
    }

    @Test fun schemaThreeAppliedAndCustomSettingsMigratePreviewOnAndKeepOtherValues() {
        val old = AppSettings(presetName = "OLD PREVIEW", camera = CameraSettings(detectIntervalMs = 123),
            overlay = OverlaySettings(enabled = false))
        val encoded = AppSettingsCodec.encode(old).lineSequence().filterNot { it.startsWith("camera.previewEnabled=") }
            .joinToString("\n").replace("schemaVersion=$APP_SETTINGS_SCHEMA_VERSION", "schemaVersion=3")
        preferences.edit().putString("applied_settings", encoded).putString("custom_preset.OLD PREVIEW", encoded)
            .putStringSet("custom_preset_names", setOf("OLD PREVIEW")).commit()
        val store = AppSettingsStore(context)
        assertEquals(old, store.loadApplied())
        assertEquals(old, store.loadCustomPreset("OLD PREVIEW"))
        assertEquals(APP_SETTINGS_SCHEMA_VERSION, AppSettingsCodec.storedSchemaVersion(preferences.getString("applied_settings", "")!!))
    }

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
        assertEquals(70, CATEGORY_FIELDS.values.sumOf { it.size })
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
            val before = loadHealthy(awaitFinish = false)
            field("Interpreter Thread Count").perform(scrollTo(), replaceText(threads.toString()), closeSoftKeyboard())
            if (before.yolo.useXnnpack != enabled) field("XNNPACK 사용").perform(scrollTo(), click())
            recreate()
            field("XNNPACK 사용").check(matches(if (enabled) isChecked() else not(isChecked())))
            field("Interpreter Thread Count").check(matches(withText(threads.toString())))
            onView(withText("적용 및 저장")).perform(click())
            awaitEditorFinished()
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
        awaitVisiblePageLayout()
    }

    private fun recreate() {
        scenario!!.recreate()
        dismissNativeCompatibilityWarning()
        awaitVisiblePageLayout()
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

    private fun awaitEditorFinished() {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (scenario?.state != androidx.lifecycle.Lifecycle.State.DESTROYED && android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(25)
        }
        assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario?.state)
    }

    private fun loadHealthy(awaitFinish: Boolean = true): AppSettings = AppSettingsStore(context).let { store ->
        // Asset/options validation runs on IO before the Activity saves and finishes.
        if (scenario != null && awaitFinish) awaitEditorFinished()
        store.loadApplied().also { store.markAppliedHealthy() }
    }

    private fun openCategory(title: String) {
        onView(withText(title)).perform(scrollTo(), click())
        awaitVisiblePageLayout()
    }

    private fun awaitVisiblePageLayout() {
        // Showing a previously GONE category requests its first layout. Espresso can become
        // idle before that traversal; scrollTo must wait for the newly visible page's size.
        val deadline = android.os.SystemClock.elapsedRealtime() + 3000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario?.onActivity { activity ->
                fun laidOutPage(view: View): Boolean {
                    if (view is ScrollView && view.isShown) {
                        fun childrenReady(child: View): Boolean =
                            (!child.isShown || (child.width > 0 && child.height > 0 && !child.isLayoutRequested)) &&
                                (child !is ViewGroup || (0 until child.childCount).all { childrenReady(child.getChildAt(it)) })
                        return childrenReady(view)
                    }
                    return view is ViewGroup && (0 until view.childCount).any { laidOutPage(view.getChildAt(it)) }
                }
                ready = laidOutPage(activity.window.decorView)
            }
            if (ready) return
            android.os.SystemClock.sleep(16)
        }
        fail("Visible settings page was not laid out")
    }
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
            "카메라 및 입력" to listOf("카메라 화면 표시 (Preview)", "Detect Interval (ms)", "Center Square ROI (OFF = Full Frame)", "Resize Mode", "요청 Camera Resolution",
                "인식 영역 크롭 사용", "상단 크롭 (%)", "하단 크롭 (%)", "왼쪽 크롭 (%)", "오른쪽 크롭 (%)", "Preview 인식 영역 표시"),
            "YOLO 모델 및 추론" to listOf("모델 선택", "Confidence Threshold", "NMS Threshold", "Interpreter Thread Count", "XNNPACK 사용", "Max Candidates"),
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
            "성능 측정 및 로그" to listOf("System Performance Diagnostics", "배터리·발열 진단", "Sample Interval (ms)", "테스트 시간 (분)"),
            "디버그 및 고급 설정" to listOf(
                "Enable Detector Diagnostics", "Debug Detection Logging", "Diagnostic Raw Confidence Threshold",
                "Diagnostic Max Raw Candidates", "Debug Save Inference Input", "Debug Save Interval (ms)",
                "Debug Save On Detection", "기존 호환 Debug Image", "Overlay", "Overlay Debug Mode", "YOLO bbox",
                "ML Kit bbox", "Confidence 표시", "FPS 표시", "Overlay Stale Timeout (ms)"
            )
        )
    }
}
