package com.samin.objectdetection.settings

import android.app.AlertDialog
import android.content.Context
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast

/** Test-app quick editor; the host owns persistence, session guards and camera restart. */
internal fun showQuickSettingsDialog(context: Context, settings: AppSettings, onApply: (AppSettings) -> Unit) {
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(40, 12, 40, 0)
    }
    val mode = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
            com.samin.objectdetection.camera.YoloResizeMode.entries.map { it.name })
        setSelection(com.samin.objectdetection.camera.YoloResizeMode.entries.indexOf(settings.yolo.resizeMode))
        isEnabled = !settings.camera.recognitionRoi.enabled
    }
    val confidence = EditText(context).apply {
        hint = "Confidence (0..1)"
        setText(settings.yolo.confidenceThreshold.toString())
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    val geometry = Switch(context).apply { text = "Geometry Filter"; isChecked = settings.filters.geometryFilterEnabled }
    val mlKit = Switch(context).apply { text = "ML Kit"; isChecked = settings.mlKit.enabled }
    content.addView(android.widget.TextView(context).apply { text = "현재 프리셋: ${settings.presetName}" })
    content.addView(mode); content.addView(confidence); content.addView(geometry); content.addView(mlKit)
    content.addView(android.widget.TextView(context).apply { text = "적용을 누르기 전에는 실행 중 설정이 바뀌지 않습니다." })
    AlertDialog.Builder(context)
        .setTitle("빠른 설정")
        .setView(content)
        .setNegativeButton("취소", null)
        .setPositiveButton("적용") { _, _ ->
            val threshold = confidence.text.toString().toFloatOrNull()
            if (threshold == null || threshold !in 0f..1f) {
                Toast.makeText(context, "Confidence는 0~1이어야 합니다.", Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            val updated = settings.copy(
                presetName = SettingsPresets.CUSTOM,
                yolo = settings.yolo.copy(
                    resizeMode = com.samin.objectdetection.camera.YoloResizeMode.valueOf(mode.selectedItem.toString()),
                    confidenceThreshold = threshold
                ),
                filters = settings.filters.copy(geometryFilterEnabled = geometry.isChecked),
                mlKit = settings.mlKit.copy(enabled = mlKit.isChecked)
            )
            onApply(updated)
        }
        .show()
}
