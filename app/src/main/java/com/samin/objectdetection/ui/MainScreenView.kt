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

class MainScreenView(
    activity: ComponentActivity,
    debugMode: OverlayDebugMode,
    onCapture: () -> Unit,
    onToggleRecording: () -> Unit
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
    val root: View

    init {
        var overlayEnabled = true
        val toggleButton = Button(activity).apply {
            text = "Overlay ON"
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
        root = FrameLayout(activity).apply {
            addView(previewView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(overlayView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(debugTextView, fullWidthAt(Gravity.TOP, 20, 40, 20, 0))
            addView(controlRow, wrapAt(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 20, 20, 20, 60))
            addView(warningMessageTextView, wrapAt(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 20, 20, 20, 150))
        }
        overlayView.bringToFront()
        debugTextView.bringToFront()
        warningMessageTextView.bringToFront()
        controlRow.bringToFront()
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
}
