package com.samin.objectdetection.settings

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.samin.objectdetection.camera.SizeFilterMode
import com.samin.objectdetection.camera.YoloResizeMode
import com.samin.objectdetection.ui.OverlayDebugMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale

class SettingsActivity : ComponentActivity() {
    private lateinit var store: AppSettingsStore
    private lateinit var form: Form
    private lateinit var presetSpinner: Spinner
    private lateinit var presetStatus: TextView
    private lateinit var customName: EditText
    private var applied = AppSettings()
    private var editing = AppSettings()
    private var binding = false
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AppSettingsStore(this)
        applied = store.loadApplied()
        editing = applied
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 245, 245))
        }
        ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(scrollContent) }
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        addHeader(scrollContent, "객체 인식 설정", "값은 적용 및 저장을 누르기 전까지 실행 중인 파이프라인에 반영되지 않습니다.")
        buildPresetSection(scrollContent)
        form = Form(scrollContent)
        buildReadOnlySection(scrollContent)
        buildCameraSection(scrollContent)
        buildYoloSection(scrollContent)
        buildFilterSection(scrollContent)
        buildMotionSection(scrollContent)
        buildWarningSection(scrollContent)
        buildOverlaySection(scrollContent)
        buildMlKitSection(scrollContent)
        buildDebugSection(scrollContent)
        buildShareSection(scrollContent)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        actions.addView(Button(this).apply { text = "초기화"; setOnClickListener { bind(SettingsPresets.byName(SettingsPresets.DEFAULT)!!) } }, weighted())
        actions.addView(Button(this).apply { text = "취소"; setOnClickListener { leave() } }, weighted())
        actions.addView(Button(this).apply { text = "적용 및 저장"; setOnClickListener { applyAndFinish() } }, weighted())
        page.addView(actions)
        setContentView(page)
        bind(applied)
    }

    private fun buildPresetSection(parent: LinearLayout) {
        section(parent, "프리셋")
        presetSpinner = Spinner(this)
        parent.addView(presetSpinner, match())
        presetStatus = note(parent, "현재 적용: ${applied.presetName}")
        customName = EditText(this).apply { hint = "사용자 프리셋 이름"; inputType = InputType.TYPE_CLASS_TEXT }
        parent.addView(customName, match())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { text = "저장"; setOnClickListener { saveCustom() } }, weighted())
        row.addView(Button(this).apply { text = "이름 변경"; setOnClickListener { renameCustom() } }, weighted())
        row.addView(Button(this).apply { text = "삭제"; setOnClickListener { deleteCustom() } }, weighted())
        parent.addView(row)
        refreshPresetSpinner(applied.presetName)
        presetSpinner.onItemSelectedListener = SimpleItemSelectedListener { name ->
            if (binding) return@SimpleItemSelectedListener
            val chosen = SettingsPresets.byName(name) ?: store.loadCustomPreset(name)
            if (chosen != null) bind(chosen)
        }
    }

    private fun buildReadOnlySection(parent: LinearLayout) {
        section(parent, "읽기 전용")
        note(parent, "모델: best_float32.tflite\n입력 Tensor: 1×640×640×3 (실제 모델 shape 검증)\nSHA256: 계산 중…").also { label ->
            lifecycleScope.launch {
                val sha = withContext(Dispatchers.IO) {
                    assets.open("best_float32.tflite").use { input ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                }
                label.text = "모델: best_float32.tflite\n입력 Tensor: 1×640×640×3 (실제 모델 shape 검증)\nSHA256: $sha"
            }
        }
    }

    private fun buildCameraSection(parent: LinearLayout) {
        section(parent, "카메라 / 전처리")
        form.detectInterval = number(parent, "Detect Interval (ms)", "0..60000")
        form.centerCrop = toggle(parent, "Center Square ROI (OFF = Full Frame)")
        form.resizeMode = choice(parent, "Resize Mode", YoloResizeMode.entries.map { it.name })
        form.cameraResolution = choice(parent, "요청 Camera Resolution", supportedAnalysisResolutions())
        note(parent, "기기가 요청 해상도를 지원하지 않으면 CameraX가 가장 가까운 해상도를 선택합니다. 실제 분석 해상도는 성능 로그에 별도로 기록됩니다.")
    }

    private fun buildYoloSection(parent: LinearLayout) {
        section(parent, "YOLO")
        form.confidence = floatSlider(parent, "Confidence Threshold", 0f, 1f)
        form.nms = floatSlider(parent, "NMS Threshold", 0f, 1f)
        form.threads = number(parent, "Interpreter Thread Count", "1..16")
        form.maxCandidates = number(parent, "Max Candidates", "1..10000")
        form.sizeMode = choice(parent, "Size Filter Mode", SizeFilterMode.entries.map { it.name })
    }

    private fun buildFilterSection(parent: LinearLayout) {
        section(parent, "객체 필터")
        form.geometry = toggle(parent, "Geometry Filter")
        form.minArea = number(parent, "Min Box Area Ratio", "0..1", decimal = true)
        form.minWidth = number(parent, "Min Box Width Ratio", "0..1", decimal = true)
        form.minHeight = number(parent, "Min Box Height Ratio", "0..1", decimal = true)
        form.bollardMinArea = number(parent, "Bollard Min Area Ratio", "0..1", decimal = true)
        form.bollardMaxAspect = number(parent, "Bollard Max Width/Height", "> 0", decimal = true)
        form.temporal = toggle(parent, "Adaptive Temporal")
        form.temporalImmediate = number(parent, "Temporal Immediate Confidence", "0..1", decimal = true)
        form.temporalConfirmation = number(parent, "Temporal Confirmation Confidence", "0..Immediate", decimal = true)
        form.temporalIou = number(parent, "Temporal Match IoU", "0..1", decimal = true)
    }

    private fun buildMotionSection(parent: LinearLayout) {
        section(parent, "Motion / Tracking")
        form.maxHistory = number(parent, "Max History Size", "1..100")
        form.minHistory = number(parent, "Min History Size", "1..Max")
        form.matchDistance = number(parent, "Max Match Distance Ratio", "0..1", true)
        form.sampleInterval = number(parent, "Min Sample Interval (ms)", ">= 0")
        form.staleTrack = number(parent, "Stale Track Timeout (ms)", ">= sample interval")
        form.absArea = number(parent, "Min Absolute Area Change", ">= 0", true)
        form.relArea = number(parent, "Min Relative Area Change", ">= 0", true)
        form.absHeight = number(parent, "Min Absolute Height Change", ">= 0", true)
        form.relHeight = number(parent, "Min Relative Height Change", ">= 0", true)
    }

    private fun buildWarningSection(parent: LinearLayout) {
        section(parent, "Warning / Feedback")
        form.cooldown = number(parent, "Warning Cooldown (ms)", "0..3600000")
        form.ignoreTop = number(parent, "Ignore Top Ratio", "0..1", true)
        form.maxGuide = number(parent, "Max Guide Object Count", "1..100")
        form.veryNearHeight = number(parent, "Very Near Height Ratio", "0..1", true)
        form.nearHeight = number(parent, "Near Height Ratio", "0..1", true)
        form.midHeight = number(parent, "Mid Height Ratio", "0..1", true)
        form.veryNearArea = number(parent, "Very Near Area Ratio", "0..1", true)
        form.nearArea = number(parent, "Near Area Ratio", "0..1", true)
        form.midArea = number(parent, "Mid Area Ratio", "0..1", true)
        form.vibration = toggle(parent, "실제 진동")
        form.beep = toggle(parent, "실제 Beep")
        form.tts = toggle(parent, "실제 TTS")
        note(parent, "출력을 꺼도 검출과 경고 후보 계산은 계속됩니다. 하나라도 꺼져 있으면 카메라 화면에 TEST OUTPUT 상태가 표시됩니다.")
    }

    private fun buildOverlaySection(parent: LinearLayout) {
        section(parent, "Overlay / Display")
        form.overlayEnabled = toggle(parent, "Overlay")
        form.debugMode = choice(parent, "Overlay Debug Mode", OverlayDebugMode.entries.map { it.name })
        form.yoloBoxes = toggle(parent, "YOLO bbox")
        form.mlKitBoxes = toggle(parent, "ML Kit bbox")
        form.confidenceLabel = toggle(parent, "Confidence 표시")
        form.fps = toggle(parent, "FPS 표시")
        form.overlayStale = number(parent, "Overlay Stale Timeout (ms)", "100..60000")
    }

    private fun buildMlKitSection(parent: LinearLayout) {
        section(parent, "ML Kit")
        form.mlKitEnabled = toggle(parent, "ML Kit Detection")
        form.mlKitInterval = number(parent, "Detection Interval (ms)", "0..60000")
    }

    private fun buildDebugSection(parent: LinearLayout) {
        section(parent, "Debug / Logging")
        note(parent, "후보별 로그와 PNG 저장은 성능에 영향을 줄 수 있습니다.")
        form.diagnostics = toggle(parent, "Enable Detector Diagnostics")
        form.debugLogging = toggle(parent, "Debug Detection Logging")
        form.rawConfidence = number(parent, "Diagnostic Raw Confidence Threshold", "0..1", true)
        form.rawCandidates = number(parent, "Diagnostic Max Raw Candidates", ">= 0")
        form.saveInput = toggle(parent, "Debug Save Inference Input")
        form.saveInterval = number(parent, "Debug Save Interval (ms)", ">= 1")
        form.saveOnDetection = toggle(parent, "Debug Save On Detection")
        form.legacyDebugImage = toggle(parent, "기존 호환 Debug Image")
    }

    private fun buildShareSection(parent: LinearLayout) {
        section(parent, "로그 내보내기")
        parent.addView(Button(this).apply {
            text = "최근 로그 및 입력 PNG를 ZIP으로 공유"
            setOnClickListener { LogShareManager(this@SettingsActivity).shareLatest() }
        }, match())
    }

    private fun bind(value: AppSettings) {
        if (!::form.isInitialized) return
        binding = true
        editing = value
        val expectedDirty = value != applied
        select(presetSpinner, value.presetName)
        customName.setText(if (value.presetName in SettingsPresets.builtInNames) "" else value.presetName)
        with(value.camera) {
            form.detectInterval.setText(detectIntervalMs.toString()); form.centerCrop.isChecked = useCenterSquareCrop
            select(form.resizeMode, value.yolo.resizeMode.name); select(form.cameraResolution, "${requestedWidth}x${requestedHeight}")
        }
        with(value.yolo) {
            form.confidence.setText(confidenceThreshold.toString()); form.nms.setText(nmsThreshold.toString())
            form.threads.setText(interpreterThreadCount.toString()); form.maxCandidates.setText(maxCandidates.toString())
            select(form.sizeMode, value.filters.sizeFilterMode.name)
        }
        with(value.filters) {
            form.geometry.isChecked = geometryFilterEnabled; form.minArea.setText(minBoxAreaRatio.toString())
            form.minWidth.setText(minBoxWidthRatio.toString()); form.minHeight.setText(minBoxHeightRatio.toString())
            form.bollardMinArea.setText(bollardMinAreaRatio.toString()); form.bollardMaxAspect.setText(bollardMaxWidthToHeightRatio.toString())
            form.temporal.isChecked = adaptiveTemporalEnabled; form.temporalImmediate.setText(temporalImmediateConfidence.toString())
            form.temporalConfirmation.setText(temporalConfirmationConfidence.toString()); form.temporalIou.setText(temporalMatchIouThreshold.toString())
        }
        with(value.motion) {
            form.maxHistory.setText(maxHistorySize.toString()); form.minHistory.setText(minHistorySize.toString())
            form.matchDistance.setText(maxMatchDistanceRatio.toString()); form.sampleInterval.setText(minSampleIntervalMs.toString())
            form.staleTrack.setText(staleTrackTimeoutMs.toString()); form.absArea.setText(minAbsoluteAreaChange.toString())
            form.relArea.setText(minRelativeAreaChangeRatio.toString()); form.absHeight.setText(minAbsoluteHeightChange.toString())
            form.relHeight.setText(minRelativeHeightChangeRatio.toString())
        }
        with(value.warning) {
            form.cooldown.setText(cooldownMs.toString()); form.ignoreTop.setText(ignoreTopRatio.toString()); form.maxGuide.setText(maxGuideObjectCount.toString())
            form.veryNearHeight.setText(veryNearHeightRatio.toString()); form.nearHeight.setText(nearHeightRatio.toString()); form.midHeight.setText(midHeightRatio.toString())
            form.veryNearArea.setText(veryNearAreaRatio.toString()); form.nearArea.setText(nearAreaRatio.toString()); form.midArea.setText(midAreaRatio.toString())
            form.vibration.isChecked = enableActualVibration; form.beep.isChecked = enableActualBeep; form.tts.isChecked = enableActualTts
        }
        with(value.overlay) {
            form.overlayEnabled.isChecked = enabled; select(form.debugMode, debugMode.name); form.yoloBoxes.isChecked = showYoloBoxes
            form.mlKitBoxes.isChecked = showMlKitBoxes; form.confidenceLabel.isChecked = showConfidence; form.fps.isChecked = showFps
            form.overlayStale.setText(staleTimeoutMs.toString())
        }
        with(value.mlKit) { form.mlKitEnabled.isChecked = enabled; form.mlKitInterval.setText(detectionIntervalMs.toString()) }
        with(value.debug) {
            form.diagnostics.isChecked = enableDetectorDiagnostics; form.debugLogging.isChecked = debugDetectionLogging
            form.rawConfidence.setText(diagnosticRawConfidenceThreshold.toString()); form.rawCandidates.setText(diagnosticMaxRawCandidates.toString())
            form.saveInput.isChecked = debugSaveInferenceInput; form.saveInterval.setText(debugSaveIntervalMs.toString())
            form.saveOnDetection.isChecked = debugSaveOnDetection; form.legacyDebugImage.isChecked = legacyDebugImageEnabled
        }
        updateDependentState()
        binding = false
        dirty = expectedDirty
        updateStatus()
        installDirtyListeners(form.container)
        // Spinner listeners receive an initial selection callback after binding; it is not a user edit.
        presetStatus.post {
            if (editing == value) {
                dirty = expectedDirty
                updateStatus()
            }
        }
    }

    private fun readForm(presetName: String = SettingsPresets.CUSTOM): AppSettings {
        fun EditText.l() = text.toString().trim().toLong()
        fun EditText.i() = text.toString().trim().toInt()
        fun EditText.f() = text.toString().trim().toFloat()
        val resolution = form.cameraResolution.selectedItem.toString().split('x')
        return AppSettings(
            presetName = presetName,
            camera = CameraSettings(form.detectInterval.l(), form.centerCrop.isChecked, resolution[0].toInt(), resolution[1].toInt()),
            yolo = YoloSettings(YoloResizeMode.valueOf(form.resizeMode.selectedItem.toString()), form.confidence.f(), form.nms.f(), form.threads.i(), form.maxCandidates.i()),
            filters = FilterSettings(form.geometry.isChecked, SizeFilterMode.valueOf(form.sizeMode.selectedItem.toString()),
                form.minArea.f(), form.minWidth.f(), form.minHeight.f(), form.bollardMinArea.f(), form.bollardMaxAspect.f(),
                form.temporal.isChecked, form.temporalImmediate.f(), form.temporalConfirmation.f(), form.temporalIou.f()),
            motion = MotionSettings(form.maxHistory.i(), form.minHistory.i(), form.matchDistance.f(), form.sampleInterval.l(), form.staleTrack.l(),
                form.absArea.f(), form.relArea.f(), form.absHeight.f(), form.relHeight.f()),
            warning = WarningSettings(form.cooldown.l(), form.ignoreTop.f(), form.maxGuide.i(), form.veryNearHeight.f(), form.nearHeight.f(),
                form.midHeight.f(), form.veryNearArea.f(), form.nearArea.f(), form.midArea.f(), form.vibration.isChecked, form.beep.isChecked, form.tts.isChecked),
            overlay = OverlaySettings(form.overlayEnabled.isChecked, OverlayDebugMode.valueOf(form.debugMode.selectedItem.toString()),
                form.yoloBoxes.isChecked, form.mlKitBoxes.isChecked, form.confidenceLabel.isChecked, form.fps.isChecked, form.overlayStale.l()),
            mlKit = MlKitSettings(form.mlKitEnabled.isChecked, form.mlKitInterval.l()),
            debug = DebugSettings(form.diagnostics.isChecked, form.debugLogging.isChecked, form.rawConfidence.f(), form.rawCandidates.i(),
                form.saveInput.isChecked, form.saveInterval.l(), form.saveOnDetection.isChecked, form.legacyDebugImage.isChecked)
        )
    }

    private fun applyAndFinish() {
        val value = parseOrShow() ?: return
        val finalName = value.presetName.takeIf { selectedPresetMatches(value) } ?: SettingsPresets.CUSTOM
        val finalValue = value.copy(presetName = finalName)
        store.saveApplied(finalValue)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_APPLIED, true))
        finish()
    }

    private fun selectedPresetMatches(value: AppSettings): Boolean {
        val selected = presetSpinner.selectedItem?.toString() ?: return false
        val preset = SettingsPresets.byName(selected) ?: store.loadCustomPreset(selected) ?: return false
        return value.copy(presetName = preset.presetName) == preset
    }

    private fun parseOrShow(name: String = presetSpinner.selectedItem?.toString() ?: SettingsPresets.CUSTOM): AppSettings? = try {
        readForm(name).also { value ->
            val errors = value.validationErrors()
            if (errors.isNotEmpty()) throw IllegalArgumentException(errors.joinToString("\n"))
        }
    } catch (error: Exception) {
        AlertDialog.Builder(this).setTitle("설정값 확인").setMessage(error.message ?: "숫자 입력값을 확인하세요.").setPositiveButton("확인", null).show()
        null
    }

    private fun saveCustom() {
        val value = parseOrShow(SettingsPresets.CUSTOM) ?: return
        runCatching { store.saveCustomPreset(customName.text.toString(), value) }
            .onSuccess { refreshPresetSpinner(customName.text.toString().trim()); Toast.makeText(this, "프리셋 저장 완료", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
    }

    private fun renameCustom() {
        val old = presetSpinner.selectedItem?.toString().orEmpty()
        runCatching { store.renameCustomPreset(old, customName.text.toString()) }
            .onSuccess { refreshPresetSpinner(customName.text.toString().trim()) }
            .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
    }

    private fun deleteCustom() {
        val name = presetSpinner.selectedItem?.toString().orEmpty()
        if (name in SettingsPresets.builtInNames) { Toast.makeText(this, "기본 프리셋은 삭제할 수 없습니다.", Toast.LENGTH_SHORT).show(); return }
        store.deleteCustomPreset(name); refreshPresetSpinner(SettingsPresets.DEFAULT); bind(AppSettings())
    }

    private fun refreshPresetSpinner(selected: String) {
        val names = SettingsPresets.builtInNames + SettingsPresets.CUSTOM + store.customPresetNames()
        presetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        select(presetSpinner, selected)
    }

    private fun installDirtyListeners(view: View) {
        if (view.getTag(DIRTY_TAG_KEY) == true) return
        view.setTag(DIRTY_TAG_KEY, true)
        when (view) {
            is EditText -> view.doAfterTextChanged { markDirty() }
            is Switch -> view.setOnCheckedChangeListener { _, _ -> updateDependentState(); markDirty() }
            is Spinner -> if (view !== presetSpinner) view.onItemSelectedListener = SimpleItemSelectedListener { updateDependentState(); markDirty() }
            is ViewGroup -> (0 until view.childCount).forEach { installDirtyListeners(view.getChildAt(it)) }
        }
    }

    private fun updateDependentState() {
        if (!::form.isInitialized) return
        val sizeEnabled = form.sizeMode.selectedItem?.toString() != SizeFilterMode.DISABLED.name
        listOf(form.minArea, form.minWidth, form.minHeight).forEach { it.isEnabled = sizeEnabled }
        val temporalEnabled = form.temporal.isChecked
        listOf(form.temporalImmediate, form.temporalConfirmation, form.temporalIou).forEach { it.isEnabled = temporalEnabled }
        form.mlKitInterval.isEnabled = form.mlKitEnabled.isChecked
    }

    private fun markDirty() { if (!binding) { dirty = true; updateStatus() } }
    private fun updateStatus() { presetStatus.text = "현재 적용: ${applied.presetName}" + if (dirty) "  •  미적용 변경 있음" else "" }

    private fun leave() {
        if (!dirty) { finish(); return }
        AlertDialog.Builder(this).setTitle("변경사항 취소").setMessage("아직 적용하지 않은 변경사항을 버릴까요?")
            .setNegativeButton("계속 편집", null).setPositiveButton("버리기") { _, _ -> finish() }.show()
    }

    private fun addHeader(parent: LinearLayout, title: String, description: String) {
        parent.addView(TextView(this).apply { text = title; textSize = 24f; setTextColor(Color.BLACK) })
        note(parent, description)
    }
    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply { text = title; textSize = 19f; setTextColor(Color.rgb(30, 70, 130)); setPadding(0, dp(24), 0, dp(6)) })
    }
    private fun note(parent: LinearLayout, value: String): TextView = TextView(this).apply {
        text = value; textSize = 13f; setTextColor(Color.DKGRAY); setPadding(0, dp(4), 0, dp(8)); parent.addView(this, match())
    }
    private fun number(parent: LinearLayout, label: String, hintText: String, decimal: Boolean = false): EditText {
        parent.addView(TextView(this).apply { text = label; setTextColor(Color.BLACK) })
        return EditText(this).apply {
            hint = hintText; inputType = InputType.TYPE_CLASS_NUMBER or if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
            parent.addView(this, match())
        }
    }
    private fun toggle(parent: LinearLayout, label: String): Switch = Switch(this).apply { text = label; parent.addView(this, match()) }
    private fun choice(parent: LinearLayout, label: String, values: List<String>): Spinner {
        parent.addView(TextView(this).apply { text = label; setTextColor(Color.BLACK) })
        return Spinner(this).apply { adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, values); parent.addView(this, match()) }
    }
    private fun floatSlider(parent: LinearLayout, label: String, min: Float, max: Float): EditText {
        val edit = number(parent, label, "$min..$max", true)
        val seek = SeekBar(this).apply { this.max = 1000 }
        parent.addView(seek, match())
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) edit.setText(String.format(Locale.US, "%.3f", min + (max - min) * progress / 1000f)) }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) { markDirty() }
        })
        edit.doAfterTextChanged { value ->
            value?.toString()?.toFloatOrNull()?.let {
                seek.progress = (((it - min) / (max - min)) * 1000).toInt().coerceIn(0, 1000)
            }
        }
        edit.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) { edit.text.toString().toFloatOrNull()?.let { seek.progress = (((it - min) / (max - min)) * 1000).toInt().coerceIn(0, 1000) }; markDirty() } }
        return edit
    }
    private fun select(spinner: Spinner, value: String) {
        val index = (0 until spinner.adapter.count).firstOrNull { spinner.adapter.getItem(it).toString() == value } ?: 0
        spinner.setSelection(index)
    }
    private fun supportedAnalysisResolutions(): List<String> = runCatching {
        val manager = getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.first { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        val map = requireNotNull(
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        )
        map.getOutputSizes(ImageFormat.YUV_420_888)
            .filter { it.width >= 320 && it.height >= 240 }
            .distinctBy { it.width to it.height }
            .sortedBy { it.width.toLong() * it.height }
            .map { "${it.width}x${it.height}" }
    }.getOrElse { listOf("1280x720") }
        .ifEmpty { listOf("1280x720") }
    private fun match() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class Form(val container: LinearLayout) {
        lateinit var detectInterval: EditText; lateinit var centerCrop: Switch; lateinit var resizeMode: Spinner; lateinit var cameraResolution: Spinner
        lateinit var confidence: EditText; lateinit var nms: EditText; lateinit var threads: EditText; lateinit var maxCandidates: EditText; lateinit var sizeMode: Spinner
        lateinit var geometry: Switch; lateinit var minArea: EditText; lateinit var minWidth: EditText; lateinit var minHeight: EditText
        lateinit var bollardMinArea: EditText; lateinit var bollardMaxAspect: EditText; lateinit var temporal: Switch
        lateinit var temporalImmediate: EditText; lateinit var temporalConfirmation: EditText; lateinit var temporalIou: EditText
        lateinit var maxHistory: EditText; lateinit var minHistory: EditText; lateinit var matchDistance: EditText; lateinit var sampleInterval: EditText
        lateinit var staleTrack: EditText; lateinit var absArea: EditText; lateinit var relArea: EditText; lateinit var absHeight: EditText; lateinit var relHeight: EditText
        lateinit var cooldown: EditText; lateinit var ignoreTop: EditText; lateinit var maxGuide: EditText
        lateinit var veryNearHeight: EditText; lateinit var nearHeight: EditText; lateinit var midHeight: EditText
        lateinit var veryNearArea: EditText; lateinit var nearArea: EditText; lateinit var midArea: EditText
        lateinit var vibration: Switch; lateinit var beep: Switch; lateinit var tts: Switch
        lateinit var overlayEnabled: Switch; lateinit var debugMode: Spinner; lateinit var yoloBoxes: Switch; lateinit var mlKitBoxes: Switch
        lateinit var confidenceLabel: Switch; lateinit var fps: Switch; lateinit var overlayStale: EditText
        lateinit var mlKitEnabled: Switch; lateinit var mlKitInterval: EditText
        lateinit var diagnostics: Switch; lateinit var debugLogging: Switch; lateinit var rawConfidence: EditText; lateinit var rawCandidates: EditText
        lateinit var saveInput: Switch; lateinit var saveInterval: EditText; lateinit var saveOnDetection: Switch; lateinit var legacyDebugImage: Switch
    }

    companion object {
        const val EXTRA_APPLIED = "settings_applied"
        private const val DIRTY_TAG_KEY = 0x1f0b00aa
    }
}

private class SimpleItemSelectedListener(private val onSelected: (String) -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
        onSelected(parent?.getItemAtPosition(position)?.toString().orEmpty())
    }
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
