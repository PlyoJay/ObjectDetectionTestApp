package com.samin.objectdetection.settings

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import com.samin.objectdetection.camera.SizeFilterMode
import com.samin.objectdetection.camera.YoloResizeMode
import com.samin.objectdetection.camera.RecognitionRoi
import com.samin.objectdetection.detector.ModelAssets
import com.samin.objectdetection.detector.YoloModel
import com.samin.objectdetection.mlkit.MlKitDetectionMode
import com.samin.objectdetection.detector.ModelIdentity
import com.samin.objectdetection.ui.OverlayDebugMode
import com.samin.objectdetection.distance.DistanceSettings
import com.samin.objectdetection.distance.DistanceOpticsMode
import com.samin.objectdetection.distance.ObjectDistanceMethod
import com.samin.objectdetection.distance.DepthInputMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private lateinit var pageTitle: TextView
    private lateinit var backButton: Button
    private val screens = linkedMapOf<String, ScrollView>()
    private val inputs = linkedMapOf<String, View>()
    private val summaries = linkedMapOf<String, TextView>()
    private val restoredScrollOffsets = linkedMapOf<String, Int>()
    private var currentScreen = HOME
    private var selectedPresetName = SettingsPresets.DEFAULT
    private lateinit var modelInfo: TextView
    private lateinit var applyButton: Button
    private var modelInfoJob: Job? = null
    private val modelInspectionMutex = Mutex()
    private val modelDescriptions = mutableMapOf<YoloModel, String>()
    private var describedModel: YoloModel? = null
    private var applying = false

    private enum class Category(val title: String, val description: String, val icon: Int) {
        CAMERA("카메라 및 입력", "해상도, ROI, 탐지 간격과 Resize / Letterbox", android.R.drawable.ic_menu_camera),
        YOLO("YOLO 모델 및 추론", "모델 정보, 스레드 수, XNNPACK, Confidence와 NMS", android.R.drawable.ic_menu_manage),
        FILTERS("탐지 필터 및 경고", "Size / Geometry / Temporal, 추적, ML Kit와 경고 출력", android.R.drawable.ic_dialog_alert),
        PERFORMANCE("성능 측정 및 로그", "시스템 진단, 샘플 간격과 로그 내보내기", android.R.drawable.ic_menu_recent_history),
        DEBUG("디버그 및 고급 설정", "상세 로그, 입력 PNG, 텐서 진단과 Overlay 표시", android.R.drawable.ic_menu_info_details)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        store = AppSettingsStore(this)
        // Recreating this editor must not advance the store's runtime rollback state.
        applied = savedInstanceState?.getString("applied")?.let(AppSettingsCodec::decode) ?: store.loadApplied()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentScreen != HOME) showScreen(HOME) else leave()
            }
        })

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 245, 245))
        }
        ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        backButton = Button(this).apply {
            text = "‹ 설정 목록"
            setOnClickListener { showScreen(HOME) }
        }
        page.addView(backButton, match())
        pageTitle = TextView(this).apply {
            textSize = 24f; setTextColor(Color.BLACK); setPadding(dp(16), dp(8), dp(16), 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            isFocusableInTouchMode = true
        }
        page.addView(pageTitle, match())
        presetStatus = note(page, "").apply { setPadding(dp(16), dp(4), dp(16), dp(8)) }
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        form = Form(host)
        val home = createScreen(host, HOME)
        note(home, "각 카테고리에서 편집한 값은 ‘적용 및 저장’을 누를 때 함께 반영됩니다. 요약은 현재 적용값입니다.")
        Category.entries.forEach { category ->
            buildCategoryCard(home, category)
            val content = createScreen(host, category.name)
            note(content, category.description)
            when (category) {
                Category.CAMERA -> buildCameraSection(content)
                Category.YOLO -> { buildYoloSection(content); buildReadOnlySection(content) }
                Category.FILTERS -> {
                    buildFilterSection(content); buildMotionSection(content)
                    buildMlKitSection(content); buildWarningSection(content)
                }
                Category.PERFORMANCE -> { buildPerformanceLogSection(content); buildShareSection(content) }
                Category.DEBUG -> { buildDebugSection(content); buildOverlaySection(content) }
            }
        }
        buildPresetSection(home)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        applyButton = Button(this).apply { text = "적용 및 저장"; setOnClickListener { applyAndFinish() } }
        actions.addView(applyButton, match())
        val secondary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        secondary.addView(Button(this).apply { text = "초기화"; setOnClickListener { bind(SettingsPresets.byName(SettingsPresets.DEFAULT)!!) } }, weighted())
        secondary.addView(Button(this).apply { text = "취소"; setOnClickListener { leave() } }, weighted())
        actions.addView(secondary, match())
        page.addView(actions)
        setContentView(page)
        bind(applied)
        savedInstanceState?.let { restoreDraft(it) }
        if (savedInstanceState != null) screens.keys.forEach { key ->
            restoredScrollOffsets[key] = savedInstanceState.getInt("scroll.$key")
        }
        showScreen(savedInstanceState?.getString("screen") ?: HOME)
    }

    private fun createScreen(host: LinearLayout, key: String): LinearLayout {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true; visibility = View.GONE
            addView(content, match())
        }
        screens[key] = scroll
        host.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return content
    }

    private fun buildCategoryCard(parent: LinearLayout, category: Category) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(8).toFloat() }
            isFocusable = true
            val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            foreground = attrs.getDrawable(0); attrs.recycle()
            setOnClickListener { showScreen(category.name) }
            contentDescription = "${category.title}. ${category.description}"
        }
        card.addView(ImageView(this).apply {
            setImageResource(category.icon); setColorFilter(Color.rgb(30, 70, 130))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        labels.addView(TextView(this).apply {
            text = category.title; textSize = 18f; setTextColor(Color.rgb(30, 70, 130))
            setTypeface(typeface, Typeface.BOLD)
        }, match())
        note(labels, category.description)
        summaries[category.name] = note(labels, "")
        card.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(card, match().apply { bottomMargin = dp(8) })
    }

    private fun showScreen(key: String) {
        currentFocus?.clearFocus()
        pageTitle.requestFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(pageTitle.windowToken, 0)
        currentScreen = key.takeIf { it in screens } ?: HOME
        screens.forEach { (name, scroll) -> scroll.visibility = if (name == currentScreen) View.VISIBLE else View.GONE }
        pageTitle.text = Category.entries.firstOrNull { it.name == currentScreen }?.title ?: "객체 인식 설정"
        backButton.visibility = if (currentScreen == HOME) View.GONE else View.VISIBLE
        restoredScrollOffsets.remove(currentScreen)?.let { offset ->
            val scroll = screens.getValue(currentScreen)
            scroll.doOnLayout { scroll.scrollTo(0, offset) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("applied", AppSettingsCodec.encode(applied))
        outState.putString("editing", AppSettingsCodec.encode(editing))
        outState.putString("preset", selectedPresetName)
        outState.putString("customName", customName.text.toString())
        outState.putString("screen", currentScreen)
        screens.forEach { (key, scroll) -> outState.putInt("scroll.$key", restoredScrollOffsets[key] ?: scroll.scrollY) }
        inputs.forEach { (key, view) ->
            // Store raw text too: a temporarily empty/invalid field is still an editor draft.
            when (view) {
                is EditText -> outState.putString(key, view.text.toString())
                is Switch -> outState.putBoolean(key, view.isChecked)
                is Spinner -> outState.putString(key, view.selectedItem.toString())
            }
        }
    }

    private fun restoreDraft(state: Bundle) {
        binding = true
        editing = state.getString("editing")?.let(AppSettingsCodec::decode) ?: applied
        selectedPresetName = state.getString("preset") ?: editing.presetName
        select(presetSpinner, selectedPresetName)
        customName.setText(state.getString("customName"))
        inputs.forEach { (key, view) ->
            if (state.containsKey(key)) when (view) {
                is EditText -> view.setText(state.getString(key))
                is Switch -> view.isChecked = state.getBoolean(key)
                is Spinner -> select(view, state.getString(key).orEmpty())
            }
        }
        updateDependentState()
        binding = false
        markDirty()
    }

    private fun buildPresetSection(parent: LinearLayout) {
        section(parent, "프리셋")
        presetSpinner = Spinner(this)
        presetSpinner.isSaveEnabled = false
        parent.addView(presetSpinner, match())
        customName = EditText(this).apply { hint = "사용자 프리셋 이름"; inputType = InputType.TYPE_CLASS_TEXT }
        customName.isSaveEnabled = false
        parent.addView(customName, match())
        note(parent, "프리셋 저장은 현재 편집값을 이름으로 보관합니다. 실행 중인 설정은 ‘적용 및 저장’으로 변경합니다.")
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(Button(this).apply { text = "프리셋 저장"; setOnClickListener { saveCustom() } }, match())
        row.addView(Button(this).apply { text = "이름 변경"; setOnClickListener { renameCustom() } }, match())
        row.addView(Button(this).apply { text = "삭제"; setOnClickListener { deleteCustom() } }, match())
        parent.addView(row)
        refreshPresetSpinner(applied.presetName)
        presetSpinner.onItemSelectedListener = SimpleItemSelectedListener { name ->
            if (binding || name == selectedPresetName) return@SimpleItemSelectedListener
            selectedPresetName = name
            val chosen = SettingsPresets.byName(name) ?: store.loadCustomPreset(name)
            if (chosen != null) bind(chosen)
        }
    }

    private fun buildReadOnlySection(parent: LinearLayout) {
        section(parent, "읽기 전용")
        modelInfo = note(parent, "선택 모델 정보 확인 중…")
        intent.getStringExtra(EXTRA_MODEL_DESCRIPTION)?.let {
            modelDescriptions[applied.yolo.model] = it
        }
    }

    private fun selectedModel(): YoloModel = YoloModel.entries[form.model.selectedItemPosition.coerceAtLeast(0)]

    private fun updateModelInfo() {
        if (!::modelInfo.isInitialized) return
        val model = selectedModel()
        if (describedModel == model) return
        describedModel = model
        modelInfoJob?.cancel()
        modelDescriptions[model]?.let { modelInfo.text = it; return }
        modelInfo.text = "Model: ${model.assetName}\nInput / Output / SHA256: 확인 중…"
        modelInfoJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                modelInspectionMutex.withLock {
                    runCatching { ModelAssets.inspect(this@SettingsActivity, model).description() }
                }
            }
            if (selectedModel() == model) {
                modelInfo.text = result.getOrElse { "Model: ${model.assetName}\n모델 로딩 실패: ${it.message}" }
                result.getOrNull()?.let { modelDescriptions[model] = it }
            }
        }
    }

    private fun ModelIdentity.description() = "Model: $assetName\n입력 크기: ${inputWidth}×$inputHeight\n" +
        "Input: $inputShape ($inputType)\nOutput: $outputShape ($outputType)\n" +
        "클래스 ($classCount): ${com.samin.objectdetection.detector.ModelConfig.labelsFor(assetName).joinToString()}\nSHA256: $sha256"

    private fun buildCameraSection(parent: LinearLayout) {
        section(parent, "카메라 / 전처리")
        form.cameraPreview = toggle(parent, "카메라 화면 표시 (Preview)")
        note(parent, "OFF에서도 카메라 분석·객체 인식·경고·로그는 계속 실행됩니다. 디스플레이 전원 OFF가 아닙니다. 화면 녹화에는 카메라 영상과 BBox가 표시되지 않습니다.")
        form.detectInterval = number(parent, "Detect Interval (ms)", "0..60000")
        form.centerCrop = toggle(parent, "Center Square ROI (OFF = Full Frame)")
        form.resizeMode = choice(parent, "Resize Mode", YoloResizeMode.entries.map { it.name })
        section(parent, "인식 영역 크롭")
        note(parent, "화면 회전 후 영상 기준입니다. 크롭 ON은 중앙 ROI를 해제하고 선택한 YOLO 모델의 Letterbox를 사용합니다. 크롭 비율은 모델 해상도와 독립적이며 YOLO와 ML Kit에 같은 영역을 한 번씩 적용합니다.")
        form.roiEnabled = toggle(parent, "인식 영역 크롭 사용")
        form.roiTop = number(parent, "상단 크롭 (%)", "0..<100", true)
        form.roiBottom = number(parent, "하단 크롭 (%)", "0..<100", true)
        form.roiLeft = number(parent, "왼쪽 크롭 (%)", "0..<100", true)
        form.roiRight = number(parent, "오른쪽 크롭 (%)", "0..<100", true)
        form.roiShow = toggle(parent, "Preview 인식 영역 표시")
        parent.addView(Button(this).apply {
            text = "크롭 프리셋: 상단 10% / 좌우 5% / 하단 0%"
            setOnClickListener {
                form.roiTop.setText("10"); form.roiBottom.setText("0")
                form.roiLeft.setText("5"); form.roiRight.setText("5")
                form.roiEnabled.isChecked = true
                updateDependentState(); markDirty()
            }
        }, match())
        section(parent, "입력 해상도")
        form.cameraResolution = choice(parent, "요청 Camera Resolution", supportedAnalysisResolutions())
        note(parent, "기기가 요청 해상도를 지원하지 않으면 CameraX가 가장 가까운 해상도를 선택합니다. 실제 분석 해상도는 성능 로그에 별도로 기록됩니다.")
    }

    private fun buildYoloSection(parent: LinearLayout) {
        section(parent, "YOLO")
        form.model = choice(parent, "모델 선택", YoloModel.entries.map { it.displayName })
        note(parent, "512 모델은 기존 9종에 cat(9), dog(10)을 추가한 11종입니다. cat/dog은 표시·통계에만 포함하며 경고·음성은 비활성화합니다. 640 모델은 기존 9종입니다.")
        form.confidence = floatSlider(parent, "Confidence Threshold", 0f, 1f)
        form.nms = floatSlider(parent, "NMS Threshold", 0f, 1f)
        form.threads = number(parent, "Interpreter Thread Count", "1..16")
        form.xnnpack = toggle(parent, "XNNPACK 사용")
        note(parent, "CPU 추론 최적화 기능입니다. 기기에 따라 OFF가 더 빠를 수 있으며, 변경 시 YOLO 모델이 다시 초기화됩니다. ON/OFF는 요청 설정이며 실제 위임 여부는 성능 로그와 네이티브 로그로 확인하세요.")
        form.maxCandidates = number(parent, "Max Candidates", "1..10000")
    }

    private fun buildFilterSection(parent: LinearLayout) {
        section(parent, "객체 필터")
        form.sizeMode = choice(parent, "Size Filter Mode", SizeFilterMode.entries.map { it.name })
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
        note(parent, "Min Sample Interval은 객체 추적 간격입니다. 시스템 성능 로그의 Sample Interval은 ‘성능 측정 및 로그’에서 설정합니다.")
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
        form.distanceLabel = toggle(parent, "객체 거리 표시")
        form.distanceMethod = choice(parent, "거리 추정 방식 (ARCORE_DEPTH=ARCore / GROUND_PLANE=지면)", ObjectDistanceMethod.entries.map { it.name })
        form.depthInput = choice(parent, "ARCore Depth 입력 (RAW는 confidence 적용 후 FULL 보조)", DepthInputMode.entries.map { it.name })
        note(parent, "ARCore는 이 메인 화면에서 카메라를 사용하며 장착 높이·각도가 필요 없습니다. 최대 4Hz, 전방 깊이 Z(m)입니다. 입력 해상도는 ARCore SDK가 선택하며 CameraX 요청 해상도는 적용되지 않습니다. 미지원 시 거리 없이 CameraX 인식을 복구하며 원인을 표시합니다. FULL이 기본이며 RAW는 비교 실험용입니다. 아래 장착값은 지면 방식에서만 사용합니다.")
        form.cameraHeight = number(parent, "카메라 설치 높이 (cm)", "1..1000, 미설정은 비워 두기", true)
        form.cameraPitch = number(parent, "카메라 하향 각도 (°)", "0..89, 수평=0, 미설정은 비워 두기", true)
        form.distanceOptics = choice(parent, "거리 화각 설정", DistanceOpticsMode.entries.map { it.name })
        form.manualFov = number(parent, "수동 수직 화각 (°)", "5..150, 회전 전 전체 분석 버퍼 기준", true)
        note(parent, "지면 방식 전용: 고정 설치·평탄한 지면·좌우 기울기 0°를 가정합니다. 지면 방식에서 높이와 하향 각도가 비어 있으면 거리를 표시하지 않습니다. AUTO는 실제 프레임의 Camera2 광학/크롭 정보를 검사하며 신뢰할 수 없으면 계산하지 않습니다. MANUAL은 현재 해상도·줌에서 회전 전 전체 ImageAnalysis 버퍼의 수직 화각을 실측해 입력하세요. ROI나 모델 입력 화각이 아닙니다. 설치 방향·해상도·줌 변경 후 다시 보정하세요. 거리는 경고 판단에 사용하지 않습니다.")
        form.fps = toggle(parent, "FPS 표시")
        form.overlayStale = number(parent, "Overlay Stale Timeout (ms)", "100..60000")
        note(parent, "촬영 후 박스를 표시할 수 있는 최대 시간입니다. 촬영 시각을 비교할 수 없으면 분석 수신 시각을 사용합니다. 경고·TTS·진동의 유효시간에는 영향을 주지 않습니다.")
    }

    private fun buildMlKitSection(parent: LinearLayout) {
        section(parent, "ML Kit")
        form.mlKitEnabled = toggle(parent, "ML Kit Detection")
        form.mlKitMode = choice(parent, "ML Kit Detector Mode", MlKitDetectionMode.entries.map { it.name })
        note(parent, "기본 STREAM_MODE. 모드를 선택하고 적용 및 저장하면 진행 중인 SDK Task와 입력 반환을 기다린 뒤 Detector를 재생성합니다. 비교 시 간격·ROI·Preview 등 다른 조건은 동일하게 유지하세요.")
        form.mlKitInterval = number(parent, "Detection Interval (ms)", "0..60000")
    }

    private fun buildPerformanceLogSection(parent: LinearLayout) {
        section(parent, "Performance Log")
        form.testDuration = choice(parent, "테스트 시간 (분)", listOf("3", "5", "10"))
        note(parent, "기본 5분. 로그 시작과 동시에 측정하며 남은 시간을 표시합니다. 시간이 끝나면 자동 저장하고, 종료 버튼으로 수동 저장할 수 있습니다. 측정·저장 중에는 설정 변경이 잠깁니다.")
        note(parent, "성능 로그 시작/종료 버튼은 기존대로 사용합니다. 시스템 진단은 로그 기록 중에만 수집하며 상세 Debug·PNG 저장을 켜지 않습니다.")
        form.systemDiagnostics = toggle(parent, "System Performance Diagnostics")
        form.batteryDiagnostics = toggle(parent, "배터리·발열 진단")
        note(parent, "시스템 성능 진단 ON + 로그 기록 중에만 배터리 정보를 수집합니다 (기본 OFF, 최소 5초 간격). 배터리 온도는 CPU/GPU 온도와 다르며 전류·추정 전력은 단말 전체 배터리 기준입니다.")
        form.systemSampleInterval = number(parent, "Sample Interval (ms)", "1000..60000 (기본 5000)")
        note(parent, "30초 구간 통계와 전체 누적 통계를 함께 저장합니다. 확인할 수 없는 지표는 N/A입니다. Thermal Headroom은 최소 10초 간격으로 조회합니다.")
    }

    private fun buildDebugSection(parent: LinearLayout) {
        section(parent, "Debug / Logging")
        note(parent, "후보별 로그와 PNG 저장은 성능에 영향을 줄 수 있습니다.")
        form.diagnostics = toggle(parent, "Enable Detector Diagnostics")
        form.mlKitDiagnostics = toggle(parent, "ML Kit Diagnostics")
        note(parent, "기본 OFF. ON을 적용 및 저장한 뒤 파일 기록을 시작하세요. 기록 시작 후 카메라 화면으로 돌아가 테스트하고, 이곳에서 중지·공유합니다. Logcat 출력은 유지하며 이미지 저장·주기·timeout은 변경하지 않습니다.")
        MlKitLogControls(this, parent, { applied }, { currentScreen == Category.DEBUG.name })
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
        selectedPresetName = value.presetName
        select(presetSpinner, value.presetName)
        customName.setText(if (value.presetName in SettingsPresets.builtInNames) "" else value.presetName)
        with(value.camera) {
            form.cameraPreview.isChecked = previewEnabled
            form.roiEnabled.isChecked = recognitionRoi.enabled
            form.roiTop.setText((recognitionRoi.top * 100).toString()); form.roiBottom.setText((recognitionRoi.bottom * 100).toString())
            form.roiLeft.setText((recognitionRoi.left * 100).toString()); form.roiRight.setText((recognitionRoi.right * 100).toString())
            form.roiShow.isChecked = recognitionRoi.showRegion
            form.detectInterval.setText(detectIntervalMs.toString()); form.centerCrop.isChecked = useCenterSquareCrop
            select(form.resizeMode, value.yolo.resizeMode.name); select(form.cameraResolution, "${requestedWidth}x${requestedHeight}")
        }
        with(value.yolo) {
            select(form.model, model.displayName)
            form.confidence.setText(confidenceThreshold.toString()); form.nms.setText(nmsThreshold.toString())
            form.threads.setText(interpreterThreadCount.toString()); form.maxCandidates.setText(maxCandidates.toString())
            form.xnnpack.isChecked = useXnnpack
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
            form.distanceLabel.isChecked = showDistance
            select(form.distanceMethod, distanceMethod.name); select(form.depthInput, depthInputMode.name)
            form.overlayEnabled.isChecked = enabled; select(form.debugMode, debugMode.name); form.yoloBoxes.isChecked = showYoloBoxes
            form.mlKitBoxes.isChecked = showMlKitBoxes; form.confidenceLabel.isChecked = showConfidence; form.fps.isChecked = showFps
            form.overlayStale.setText(staleTimeoutMs.toString())
        }
        with(value.distance) {
            form.cameraHeight.setText(cameraHeightCm?.toString().orEmpty())
            form.cameraPitch.setText(downwardPitchDegrees?.toString().orEmpty())
            select(form.distanceOptics, opticsMode.name)
            form.manualFov.setText(manualVerticalFovDegrees?.toString().orEmpty())
        }
        with(value.mlKit) {
            form.mlKitEnabled.isChecked = enabled; form.mlKitInterval.setText(detectionIntervalMs.toString())
            select(form.mlKitMode, mode.name)
        }
        with(value.performanceLog) {
            select(form.testDuration, durationMinutes.toString())
            form.systemDiagnostics.isChecked = systemDiagnosticsEnabled
            form.batteryDiagnostics.isChecked = batteryDiagnosticsEnabled
            form.systemSampleInterval.setText(sampleIntervalMs.toString())
        }
        with(value.debug) {
            form.diagnostics.isChecked = enableDetectorDiagnostics; form.debugLogging.isChecked = debugDetectionLogging
            form.mlKitDiagnostics.isChecked = enableMlKitDiagnostics
            form.rawConfidence.setText(diagnosticRawConfidenceThreshold.toString()); form.rawCandidates.setText(diagnosticMaxRawCandidates.toString())
            form.saveInput.isChecked = debugSaveInferenceInput; form.saveInterval.setText(debugSaveIntervalMs.toString())
            form.saveOnDetection.isChecked = debugSaveOnDetection; form.legacyDebugImage.isChecked = legacyDebugImageEnabled
        }
        updateDependentState()
        binding = false
        installDirtyListeners(form.container)
        markDirty()
        updateSummaries()
    }

    private fun readForm(presetName: String = SettingsPresets.CUSTOM): AppSettings {
        fun EditText.l() = text.toString().trim().toLong()
        fun EditText.i() = text.toString().trim().toInt()
        fun EditText.f() = text.toString().trim().toFloat()
        fun EditText.optionalFloat(): Float? = text.toString().trim().takeIf { it.isNotEmpty() }?.toFloat()
        val resolution = form.cameraResolution.selectedItem.toString().split('x')
        return AppSettings(
            presetName = presetName,
            camera = CameraSettings(form.detectInterval.l(), form.centerCrop.isChecked, resolution[0].toInt(), resolution[1].toInt(), form.cameraPreview.isChecked,
                RecognitionRoi(form.roiEnabled.isChecked, form.roiTop.f() / 100f, form.roiBottom.f() / 100f,
                    form.roiLeft.f() / 100f, form.roiRight.f() / 100f, form.roiShow.isChecked)),
            yolo = YoloSettings(YoloResizeMode.valueOf(form.resizeMode.selectedItem.toString()), form.confidence.f(), form.nms.f(), form.threads.i(), form.maxCandidates.i(), form.xnnpack.isChecked, selectedModel()),
            filters = FilterSettings(form.geometry.isChecked, SizeFilterMode.valueOf(form.sizeMode.selectedItem.toString()),
                form.minArea.f(), form.minWidth.f(), form.minHeight.f(), form.bollardMinArea.f(), form.bollardMaxAspect.f(),
                form.temporal.isChecked, form.temporalImmediate.f(), form.temporalConfirmation.f(), form.temporalIou.f()),
            motion = MotionSettings(form.maxHistory.i(), form.minHistory.i(), form.matchDistance.f(), form.sampleInterval.l(), form.staleTrack.l(),
                form.absArea.f(), form.relArea.f(), form.absHeight.f(), form.relHeight.f()),
            warning = WarningSettings(form.cooldown.l(), form.ignoreTop.f(), form.maxGuide.i(), form.veryNearHeight.f(), form.nearHeight.f(),
                form.midHeight.f(), form.veryNearArea.f(), form.nearArea.f(), form.midArea.f(), form.vibration.isChecked, form.beep.isChecked, form.tts.isChecked),
            overlay = OverlaySettings(form.overlayEnabled.isChecked, OverlayDebugMode.valueOf(form.debugMode.selectedItem.toString()),
                form.yoloBoxes.isChecked, form.mlKitBoxes.isChecked, form.confidenceLabel.isChecked, form.fps.isChecked, form.overlayStale.l(), form.distanceLabel.isChecked,
                ObjectDistanceMethod.valueOf(form.distanceMethod.selectedItem.toString()), DepthInputMode.valueOf(form.depthInput.selectedItem.toString())),
            distance = DistanceSettings(form.cameraHeight.optionalFloat(), form.cameraPitch.optionalFloat(),
                DistanceOpticsMode.valueOf(form.distanceOptics.selectedItem.toString()), form.manualFov.optionalFloat()),
            mlKit = MlKitSettings(form.mlKitEnabled.isChecked, form.mlKitInterval.l(),
                MlKitDetectionMode.valueOf(form.mlKitMode.selectedItem.toString())),
            performanceLog = PerformanceLogSettings(form.systemDiagnostics.isChecked, form.systemSampleInterval.l(), form.batteryDiagnostics.isChecked, form.testDuration.selectedItem.toString().toInt()),
            debug = DebugSettings(form.diagnostics.isChecked, form.debugLogging.isChecked, form.rawConfidence.f(), form.rawCandidates.i(),
                form.saveInput.isChecked, form.saveInterval.l(), form.saveOnDetection.isChecked, form.legacyDebugImage.isChecked,
                form.mlKitDiagnostics.isChecked)
        )
    }

    private fun applyAndFinish() {
        if (applying) return
        val value = parseOrShow() ?: return
        val finalName = value.presetName.takeIf { selectedPresetMatches(value) } ?: SettingsPresets.CUSTOM
        val finalValue = if (value == applied) applied else value.copy(presetName = finalName)
        applying = true
        applyButton.isEnabled = false
        lifecycleScope.launch {
            // Validate the asset and requested native options before changing durable settings.
            val result = withContext(Dispatchers.IO) {
                modelInspectionMutex.withLock {
                    runCatching { ModelAssets.inspect(this@SettingsActivity, value.yolo.model,
                        value.yolo.interpreterThreadCount, value.yolo.useXnnpack) }
                }
            }
            applying = false
            applyButton.isEnabled = true
            result.onSuccess {
                if (finalValue != applied) store.saveApplied(finalValue)
                setResult(RESULT_OK, Intent().putExtra(EXTRA_APPLIED, true))
                finish()
            }.onFailure { error ->
                AlertDialog.Builder(this@SettingsActivity).setTitle("모델 로딩 실패")
                    .setMessage("${value.yolo.model.assetName}\n${error.message}\n기존 적용 설정을 유지합니다.")
                    .setPositiveButton("확인", null).show()
            }
        }
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
            .onSuccess {
                val name = customName.text.toString().trim()
                refreshPresetSpinner(name)
                bind(requireNotNull(store.loadCustomPreset(name)))
                Toast.makeText(this, "프리셋 저장 완료", Toast.LENGTH_SHORT).show()
            }
            .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
    }

    private fun renameCustom() {
        val old = presetSpinner.selectedItem?.toString().orEmpty()
        runCatching { store.renameCustomPreset(old, customName.text.toString()) }
            .onSuccess {
                val name = customName.text.toString().trim()
                refreshPresetSpinner(name)
                bind(requireNotNull(store.loadCustomPreset(name)))
            }
            .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
    }

    private fun deleteCustom() {
        val name = presetSpinner.selectedItem?.toString().orEmpty()
        if (name in SettingsPresets.builtInNames) { Toast.makeText(this, "기본 프리셋은 삭제할 수 없습니다.", Toast.LENGTH_SHORT).show(); return }
        store.deleteCustomPreset(name); refreshPresetSpinner(SettingsPresets.DEFAULT); bind(AppSettings())
    }

    private fun refreshPresetSpinner(selected: String) {
        val names = SettingsPresets.builtInNames + SettingsPresets.CUSTOM + store.customPresetNames()
        selectedPresetName = selected
        presetSpinner.adapter = wrappingAdapter(names + listOf(selected).filterNot { it in names })
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
        updateModelInfo()
        val sizeEnabled = form.sizeMode.selectedItem?.toString() != SizeFilterMode.DISABLED.name
        listOf(form.minArea, form.minWidth, form.minHeight).forEach { it.isEnabled = sizeEnabled }
        val temporalEnabled = form.temporal.isChecked
        listOf(form.temporalImmediate, form.temporalConfirmation, form.temporalIou).forEach { it.isEnabled = temporalEnabled }
        form.mlKitInterval.isEnabled = form.mlKitEnabled.isChecked
        form.mlKitMode.isEnabled = form.mlKitEnabled.isChecked
        form.systemSampleInterval.isEnabled = form.systemDiagnostics.isChecked
        form.batteryDiagnostics.isEnabled = form.systemDiagnostics.isChecked
        val ground = form.distanceMethod.selectedItem?.toString() == ObjectDistanceMethod.GROUND_PLANE.name
        listOf(form.cameraHeight, form.cameraPitch, form.distanceOptics).forEach { it.isEnabled = ground }
        form.manualFov.isEnabled = ground && form.distanceOptics.selectedItem?.toString() == "MANUAL"
        form.depthInput.isEnabled = !ground
        val cropEnabled = form.roiEnabled.isChecked
        if (cropEnabled) {
            form.centerCrop.isChecked = false
            select(form.resizeMode, YoloResizeMode.LETTERBOX.name)
        }
        form.centerCrop.isEnabled = !cropEnabled
        form.resizeMode.isEnabled = !cropEnabled
        listOf(form.roiTop, form.roiBottom, form.roiLeft, form.roiRight).forEach { it.isEnabled = cropEnabled }
    }

    private fun markDirty() {
        if (!binding) {
            // Compare values, so initial Spinner callbacks, focus changes and edits reverted
            // to their original value cannot turn navigation into a settings change.
            dirty = runCatching { readForm(editing.presetName) != applied }.getOrDefault(true)
            updateStatus()
        }
    }
    private fun updateStatus() { presetStatus.text = "현재 적용: ${applied.presetName}" + if (dirty) "  •  미적용 변경 있음" else "" }

    private fun updateSummaries() {
        fun Boolean.onOff() = if (this) "ON" else "OFF"
        summaries[Category.CAMERA.name]?.text = with(applied) {
            "현재 적용: ${camera.requestedWidth}×${camera.requestedHeight} · ${camera.detectIntervalMs} ms\n" +
                "ROI ${if (camera.recognitionRoi.enabled) "Edge Crop" else if (camera.useCenterSquareCrop) "Center Square" else "Full Frame"} · ${yolo.resizeMode} · Preview ${camera.previewEnabled.onOff()}"
        }
        summaries[Category.YOLO.name]?.text = with(applied.yolo) {
            "현재 적용: ${model.displayName}\nCPU Threads: $interpreterThreadCount · XNNPACK: ${useXnnpack.onOff()} (요청)\n" +
                "Confidence $confidenceThreshold · NMS $nmsThreshold"
        }
        summaries[Category.FILTERS.name]?.text = with(applied) {
            "현재 적용: Size ${filters.sizeFilterMode} · Geometry ${filters.geometryFilterEnabled.onOff()}\n" +
                "Temporal ${filters.adaptiveTemporalEnabled.onOff()} · ML Kit ${mlKit.enabled.onOff()} / ${mlKit.mode}"
        }
        summaries[Category.PERFORMANCE.name]?.text = with(applied.performanceLog) {
            "현재 적용: $durationMinutes 분 · 시스템 진단 ${systemDiagnosticsEnabled.onOff()} · 배터리·발열 ${batteryDiagnosticsEnabled.onOff()} · $sampleIntervalMs ms"
        }
        summaries[Category.DEBUG.name]?.text = with(applied) {
            "현재 적용: 상세 로그 ${debug.debugDetectionLogging.onOff()} · 입력 PNG ${debug.debugSaveInferenceInput.onOff()}\n" +
                "Overlay ${overlay.enabled.onOff()} · ${overlay.debugMode}"
        }
        summaries.values.forEach { label ->
            (label.parent.parent as View).contentDescription =
                Category.entries.first { summaries[it.name] === label }.let { "${it.title}. ${it.description}. ${label.text}" }
        }
    }

    private fun leave() {
        if (!dirty) { finish(); return }
        AlertDialog.Builder(this).setTitle("변경사항 취소").setMessage("아직 적용하지 않은 변경사항을 버릴까요?")
            .setNegativeButton("계속 편집", null).setPositiveButton("버리기") { _, _ -> finish() }.show()
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
            registerInput(label, this)
            parent.addView(this, match())
        }
    }
    private fun toggle(parent: LinearLayout, label: String): Switch = Switch(this).apply {
        text = label; minHeight = dp(48); setPadding(0, dp(8), 0, dp(8))
        registerInput(label, this); parent.addView(this, match())
    }
    private fun choice(parent: LinearLayout, label: String, values: List<String>): Spinner {
        parent.addView(TextView(this).apply { text = label; setTextColor(Color.BLACK) })
        return Spinner(this).apply {
            adapter = wrappingAdapter(values); registerInput(label, this); parent.addView(this, match())
        }
    }

    private fun registerInput(label: String, view: View) {
        check(label !in inputs) { "Duplicate setting label: $label" }
        inputs[label] = view
        view.tag = label
        view.contentDescription = label
        view.isSaveEnabled = false // Draft restoration is explicit, including disabled fields.
    }

    private fun wrappingAdapter(values: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            wrap(super.getView(position, convertView, parent))
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            wrap(super.getDropDownView(position, convertView, parent))
        private fun wrap(view: View): View = view.apply {
            (this as TextView).apply {
                setSingleLine(false); ellipsize = null; minHeight = dp(48)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
        }
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
        return edit
    }
    private fun select(spinner: Spinner, value: String) {
        // Keep saved resolutions even when this device cannot offer them. CameraX still
        // negotiates the actual resolution; visiting this screen must not rewrite it.
        if (spinner === form.cameraResolution && (0 until spinner.adapter.count).none { spinner.adapter.getItem(it).toString() == value }) {
            val existing = (0 until spinner.adapter.count).map { spinner.adapter.getItem(it).toString() }
            spinner.adapter = wrappingAdapter(existing + value)
        }
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

    private inner class Form(val container: ViewGroup) {
        lateinit var model: Spinner
        lateinit var cameraPreview: Switch
        lateinit var xnnpack: Switch
        lateinit var systemDiagnostics: Switch; lateinit var systemSampleInterval: EditText
        lateinit var batteryDiagnostics: Switch
        lateinit var testDuration: Spinner
        lateinit var roiEnabled: Switch; lateinit var roiShow: Switch
        lateinit var roiTop: EditText; lateinit var roiBottom: EditText; lateinit var roiLeft: EditText; lateinit var roiRight: EditText
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
        lateinit var distanceLabel: Switch; lateinit var cameraHeight: EditText; lateinit var cameraPitch: EditText
        lateinit var distanceMethod: Spinner; lateinit var depthInput: Spinner
        lateinit var distanceOptics: Spinner; lateinit var manualFov: EditText
        lateinit var mlKitEnabled: Switch; lateinit var mlKitInterval: EditText; lateinit var mlKitMode: Spinner
        lateinit var diagnostics: Switch; lateinit var debugLogging: Switch; lateinit var rawConfidence: EditText; lateinit var rawCandidates: EditText
        lateinit var mlKitDiagnostics: Switch
        lateinit var saveInput: Switch; lateinit var saveInterval: EditText; lateinit var saveOnDetection: Switch; lateinit var legacyDebugImage: Switch
    }

    companion object {
        const val EXTRA_APPLIED = "settings_applied"
        const val EXTRA_MODEL_DESCRIPTION = "model_description"
        private const val HOME = "home"
        private const val DIRTY_TAG_KEY = 0x1f0b00aa
    }
}

private class SimpleItemSelectedListener(private val onSelected: (String) -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
        onSelected(parent?.getItemAtPosition(position)?.toString().orEmpty())
    }
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
