package com.samin.objectdetection.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainScreenView(
    activity: ComponentActivity,
    debugMode: OverlayDebugMode,
    presetName: String,
    overlayInitiallyEnabled: Boolean,
    outputTestMode: Boolean,
    onCapture: () -> Unit,
    onToggleRecording: () -> Unit,
    onTogglePerformanceLogging: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenQuickSettings: () -> Unit
) {
    val previewView = PreviewView(activity).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    val overlayView = BoundingBoxOverlay(activity).apply { setDebugMode(debugMode) }
    val debugTextView = TextView(activity).apply {
        text = "대기 중"
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(170, 0, 0, 0))
        setPadding(24, 24, 24, 24)
        visibility = if (debugMode == OverlayDebugMode.FULL) View.VISIBLE else View.GONE
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
    val presetTextView = TextView(activity).apply {
        text = "Preset: $presetName" + if (outputTestMode) "  •  TEST OUTPUT" else ""
        textSize = 13f
        setTextColor(if (outputTestMode) Color.YELLOW else Color.WHITE)
        setBackgroundColor(Color.argb(170, 0, 0, 0))
        setPadding(16, 10, 16, 10)
    }
    val root: View

    init {
        var overlayEnabled = overlayInitiallyEnabled
        val toggleButton = Button(activity).apply {
            text = if (overlayEnabled) "Overlay ON" else "Overlay OFF"
            setOnClickListener {
                overlayEnabled = !overlayEnabled
                overlayView.setDrawingEnabled(overlayEnabled)
                text = if (overlayEnabled) "Overlay ON" else "Overlay OFF"
            }
        }
        val captureButton = Button(activity).apply {
            text = "캡쳐"
            setOnClickListener { onCapture() }
        }
        val controlRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(toggleButton)
            addView(captureButton)
            addView(recordingButton)
        }
        val performanceRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(performanceLogButton)
            addView(performanceRecordingTextView)
            addView(Button(activity).apply { text = "빠른 설정"; setOnClickListener { onOpenQuickSettings() } })
        }
        val settingsButton = Button(activity).apply { text = "⚙"; contentDescription = "상세 설정"; setOnClickListener { onOpenSettings() } }
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(controlRow)
            addView(performanceRow)
        }
        val debugParams = fullWidthAt(Gravity.TOP, 20, 40, 20, 0)
        val presetParams = wrapAt(Gravity.TOP or Gravity.START, 20, 20, 20, 0)
        val settingsParams = wrapAt(Gravity.TOP or Gravity.END, 20, 12, 20, 0)
        val controlsParams = wrapAt(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 20, 20, 20, 40)
        val warningParams = wrapAt(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 20, 20, 20, 190)
        root = FrameLayout(activity).apply {
            addView(previewView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(overlayView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(debugTextView, debugParams)
            addView(presetTextView, presetParams)
            addView(settingsButton, settingsParams)
            addView(controls, controlsParams)
            addView(warningMessageTextView, warningParams)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            presetParams.topMargin = bars.top + dp(activity, 8)
            settingsParams.topMargin = bars.top + dp(activity, 4)
            debugParams.topMargin = bars.top + dp(activity, 68)
            controlsParams.bottomMargin = bars.bottom + dp(activity, 12)
            warningParams.bottomMargin = bars.bottom + dp(activity, 116)
            root.requestLayout()
            insets
        }
        overlayView.bringToFront()
        debugTextView.bringToFront()
        warningMessageTextView.bringToFront()
        controls.bringToFront()
        presetTextView.bringToFront()
        settingsButton.bringToFront()
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
