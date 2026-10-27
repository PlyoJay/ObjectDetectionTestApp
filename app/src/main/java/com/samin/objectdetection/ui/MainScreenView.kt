package com.samin.objectdetection.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainScreenView(
    activity: ComponentActivity,
    debugMode: OverlayDebugMode,
    presetName: String,
    modelDescription: String,
    overlayInitiallyEnabled: Boolean,
    outputTestMode: Boolean,
    onCapture: () -> Unit,
    onToggleRecording: () -> Unit,
    onTogglePerformanceLogging: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    private val cameraPreviewEnabled: Boolean = true,
    onToggleMlKitLogging: () -> Unit = {},
    onShareMlKitLog: () -> Unit = {},
    onOpenArCore: () -> Unit = {},
    onOpenArCoreYolo: () -> Unit = {}
) {
    val previewView = PreviewView(activity).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    val overlayView = BoundingBoxOverlay(activity).apply { setDebugMode(debugMode) }
    val debugTextView = TextView(activity).apply {
        text = "대기 중"
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(170, 0, 0, 0))
        setPadding(24, 24, 24, 24)
        visibility = if (cameraPreviewEnabled && debugMode == OverlayDebugMode.FULL) View.VISIBLE else View.GONE
    }
    val warningMessageTextView = TextView(activity).apply {
        id = View.generateViewId()
        visibility = View.GONE
        textSize = 18f
        maxLines = 2
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(190, 0, 0, 0))
        setPadding(32, 20, 32, 20)
    }
    val recordingButton = Button(activity).apply {
        text = "녹화 시작"
        setOnClickListener { onToggleRecording() }
    }
    val performanceLogButton = Button(activity).apply {
        text = "성능 로그 시작"
        setOnClickListener { onTogglePerformanceLogging() }
    }
    val performanceRecordingTextView = TextView(activity).apply {
        text = "PERF REC"
        textSize = 12f
        setTextColor(Color.RED)
        visibility = View.GONE
        setPadding(12, 0, 12, 0)
    }
    val mlKitLogButton = Button(activity).apply {
        text = "ML Kit 로그 시작"
        setOnClickListener { onToggleMlKitLogging() }
    }
    val mlKitShareButton = Button(activity).apply {
        text = "ML Kit 로그 공유"
        setOnClickListener { onShareMlKitLog() }
    }
    val mlKitRecordingTextView = TextView(activity).apply {
        text = "ML Kit 로그 중지"
        textSize = 12f
        setTextColor(Color.DKGRAY)
        setPadding(12, 0, 12, 4)
    }
    val presetTextView = TextView(activity).apply {
        visibility = if (cameraPreviewEnabled) View.VISIBLE else View.GONE
        text = "Preset: $presetName" + (if (outputTestMode) "  •  TEST OUTPUT" else "") + "\n$modelDescription"
        textSize = 13f
        setTextColor(if (outputTestMode) Color.YELLOW else Color.WHITE)
        setBackgroundColor(Color.argb(170, 0, 0, 0))
        setPadding(16, 10, 16, 10)
    }
    val previewStatusTextView = TextView(activity).apply {
        text = "화면 표시 OFF — 카메라 시작 대기 중"
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(24, 24, 24, 24)
        visibility = if (cameraPreviewEnabled) View.GONE else View.VISIBLE
    }
    val root: View
    val arCoreButton = Button(activity).apply {
        text = "ARCore 거리 측정"
        setOnClickListener { onOpenArCore() }
    }
    val arCoreYoloButton = Button(activity).apply {
        text = "YOLO + ARCore 거리 표시"
        setOnClickListener { onOpenArCoreYolo() }
    }
    private val measurementControls = mutableListOf<View>()

    fun setMeasurementLocked(locked: Boolean) {
        measurementControls.forEach { it.isEnabled = !locked }
        // Preview OFF already suppresses this control.
        measurementControls.firstOrNull()?.isEnabled = !locked && cameraPreviewEnabled
    }

    init {
        var overlayEnabled = overlayInitiallyEnabled
        val toggleButton = Button(activity).apply {
            text = if (overlayEnabled) "Overlay ON" else "Overlay OFF"
            // Preserve the independent Overlay preference while Preview suppresses all drawing.
            isEnabled = cameraPreviewEnabled
            setOnClickListener {
                overlayEnabled = !overlayEnabled
                overlayView.setDrawingEnabled(overlayEnabled)
                text = if (overlayEnabled) "Overlay ON" else "Overlay OFF"
            }
        }
        measurementControls.add(toggleButton)
        val captureButton = Button(activity).apply {
            text = "캡쳐"
            setOnClickListener { onCapture() }
        }
        fun LinearLayout.addButton(button: Button) {
            button.textSize = 13f
            button.minWidth = 0
            button.maxLines = 2
            button.setPadding(dp(activity, 4), 0, dp(activity, 4), 0)
            addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val controlRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addButton(toggleButton)
            addButton(captureButton)
            addButton(recordingButton)
        }
        val performanceRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addButton(performanceLogButton)
            addButton(Button(activity).apply { text = "빠른 설정"; setOnClickListener { onOpenQuickSettings() }; measurementControls.add(this) })
        }
        val mlKitRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addButton(mlKitLogButton)
            addButton(mlKitShareButton)
        }
        val settingsButton = Button(activity).apply { text = "⚙"; contentDescription = "상세 설정"; setOnClickListener { onOpenSettings() } }
        measurementControls.add(settingsButton)
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(controlRow)
            addView(performanceRow)
            addView(performanceRecordingTextView)
            addView(LinearLayout(activity).apply {
                addButton(arCoreButton); addButton(arCoreYoloButton)
            })
            addView(mlKitRow)
            addView(mlKitRecordingTextView)
            setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 4))
        }
        measurementControls.addAll(listOf(mlKitLogButton, mlKitShareButton))
        measurementControls.add(arCoreButton)
        measurementControls.add(arCoreYoloButton)
        val debugParams = fullWidthAt(Gravity.TOP, 20, 40, 20, 0)
        val presetParams = wrapAt(Gravity.TOP or Gravity.START, 20, 20, 20, 0)
        val settingsParams = wrapAt(Gravity.TOP or Gravity.END, 20, 12, 20, 0)
        val warningParams = fullWidthAt(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 20, 20, 20, dp(activity, 8))
        val viewport = FrameLayout(activity).apply {
            if (!cameraPreviewEnabled) setBackgroundColor(Color.BLACK)
            addView(previewView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(overlayView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(debugTextView, debugParams)
            addView(presetTextView, presetParams)
            addView(settingsButton, settingsParams)
            addView(warningMessageTextView, warningParams)
            addView(previewStatusTextView, fullWidthAt(Gravity.CENTER, 20, 0, 20, 0))
        }
        // A separate footer reserves camera/warning space. On short/large-font windows all
        // controls stay reachable by scrolling, rather than overlapping or clipping buttons.
        val footer = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = (View.MeasureSpec.getSize(heightMeasureSpec) / 2).coerceAtLeast(1)
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(controls); setBackgroundColor(Color.WHITE) }
        root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(viewport, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(footer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        previewView.visibility = if (cameraPreviewEnabled) View.VISIBLE else View.GONE
        overlayView.visibility = if (cameraPreviewEnabled) View.VISIBLE else View.GONE
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(0, bars.top, 0, bars.bottom)
            presetParams.topMargin = dp(activity, 8)
            settingsParams.topMargin = dp(activity, 4)
            debugParams.topMargin = dp(activity, 100)
            // Reserve the settings button's width when a model asset has a long name.
            presetParams.width = (activity.resources.displayMetrics.widthPixels - dp(activity, 100)).coerceAtLeast(1)
            root.requestLayout()
            insets
        }
        overlayView.bringToFront()
        debugTextView.bringToFront()
        warningMessageTextView.bringToFront()
        presetTextView.bringToFront()
        settingsButton.bringToFront()
    }

    fun updatePreviewStatus(status: String) {
        if (cameraPreviewEnabled) return
        val message = "화면 표시 OFF — $status"
        if (previewStatusTextView.text.toString() != message) previewStatusTextView.text = message
    }

    private fun fullWidthAt(gravity: Int, left: Int, top: Int, right: Int, bottom: Int) =
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            this.gravity = gravity
            setMargins(left, top, right, bottom)
        }

    private fun wrapAt(gravity: Int, left: Int, top: Int, right: Int, bottom: Int) =
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            this.gravity = gravity
            setMargins(left, top, right, bottom)
        }

    private fun dp(activity: ComponentActivity, value: Int) =
        (value * activity.resources.displayMetrics.density).toInt()
}
