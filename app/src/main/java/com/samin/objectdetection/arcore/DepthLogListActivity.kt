package com.samin.objectdetection.arcore

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

class DepthLogListActivity : ComponentActivity() {
    private lateinit var catalog: DepthLogFiles
    private lateinit var exporter: DepthLogExporter
    private lateinit var list: ListView
    private lateinit var status: TextView
    private val selection = DepthLogSelection()
    private var logs = emptyList<DepthLogFile>()
    private var busy = false
    private val actions = mutableListOf<Button>()
    private lateinit var share: Button
    private lateinit var exportSelected: Button
    private lateinit var exportAll: Button
    private var restoredNames = emptySet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredNames = savedInstanceState?.getStringArrayList("selected")?.toSet().orEmpty()
        catalog = DepthLogFiles(File(filesDir, "arcore_depth"))
        exporter = DepthLogExporter(catalog, MediaStoreDepthDownloads(applicationContext.contentResolver))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(dp(12), 0, dp(12), 0) }
        root.addView(TextView(this).apply { text = "측정 로그 목록"; textSize = 22f; setTextColor(Color.BLACK); setPadding(0, dp(12), 0, dp(8)) })
        root.addView(TextView(this).apply {
            text = "내부 CSV 원본은 보존됩니다. 기록 중·저장 미완료 파일은 선택할 수 없습니다.\n내보내기 위치: ${DepthLogExporter.RELATIVE_PATH}"
            setTextColor(Color.DKGRAY); textSize = 13f
        })
        status = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)); setTextColor(Color.BLACK) }; root.addView(status)
        fun row(vararg buttons: Button) = LinearLayout(this).apply {
            buttons.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; textSize = 13f; minWidth = 0; maxLines = 2
            setOnClickListener { if (!busy) action() }; actions.add(this)
        }
        root.addView(row(button("전체 선택") { selection.selectAll(logs); updateChecks() },
            button("선택 해제") { selection.clear(); updateChecks() }, button("새로고침") { refresh() }))
        list = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            setOnItemClickListener { _, _, position, _ -> selection.toggle(logs[position]); updateChecks() }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        share = button("선택 CSV 공유") { shareSelected() }
        exportSelected = button("선택 내보내기") { export(selection.selected(logs)) }
        exportAll = button("전체 내보내기") { export(logs.filter { it.ready }) }
        root.addView(row(share, exportSelected))
        root.addView(row(exportAll, button("이전 화면") { finish() }))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(12), bars.top, dp(12), bars.bottom); insets
        }
        setContentView(root)
    }

    override fun onResume() { super.onResume(); if (!busy) refresh() }

    private fun refresh() {
        busy = true; updateChecks()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { catalog.list() } }
            busy = false
            result.onSuccess { loaded ->
                logs = loaded
                if (restoredNames.isNotEmpty()) {
                    logs.filter { it.ready && it.file.name in restoredNames }.forEach { selection.toggle(it) }
                    restoredNames = emptySet()
                }
                val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                val labels = logs.map { log ->
                    "${log.file.name}\n생성: ${date.format(Date(log.createdAtMs))}\n수정: ${date.format(Date(log.modifiedAtMs))} · ${Formatter.formatFileSize(this@DepthLogListActivity, log.sizeBytes)}" +
                        if (log.ready) "" else "\n기록 중 / 저장 미완료 — 공유·내보내기 제외"
                }
                list.adapter = object : ArrayAdapter<String>(this@DepthLogListActivity, android.R.layout.simple_list_item_multiple_choice, labels) {
                    override fun areAllItemsEnabled() = false
                    override fun isEnabled(position: Int) = logs[position].ready
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                        super.getView(position, convertView, parent).apply {
                            alpha = if (logs[position].ready) 1f else .5f
                            layoutParams = android.widget.AbsListView.LayoutParams(-1, -2)
                            (this as TextView).apply {
                                setSingleLine(false); ellipsize = null; textSize = 14f
                                setPadding(dp(12), dp(10), dp(12), dp(10))
                            }
                        }
                }
            }.onFailure { toast("목록 조회 실패: ${it.message}") }
            updateChecks()
        }
    }

    private fun updateChecks() {
        if (!::list.isInitialized) return
        logs.forEachIndexed { index, log -> list.setItemChecked(index, selection.contains(log)) }
        actions.forEach { it.isEnabled = !busy }
        val selected = selection.selected(logs)
        share.isEnabled = !busy && selected.isNotEmpty()
        exportSelected.isEnabled = !busy && selected.isNotEmpty()
        exportAll.isEnabled = !busy && logs.any { it.ready }
        list.isEnabled = !busy
        status.text = if (busy) "처리 중…" else "CSV ${logs.size}개 · 선택 ${selected.size}개 · 미완료 ${logs.count { !it.ready }}개" +
            if (logs.isEmpty()) "\n저장된 측정 로그가 없습니다." else ""
    }

    private fun shareSelected() {
        runCatching { DepthLogSharing.shareFiles(this, selection.selected(logs).map { it.file }, catalog, multiple = true) }
            .onFailure { toast("공유 실패: ${it.message}") }
    }

    private fun export(selected: List<DepthLogFile>) {
        if (selected.isEmpty()) return
        busy = true; updateChecks()
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { selected.map { exporter.export(it.file) } }
            busy = false; updateChecks()
            val successes = results.mapNotNull { it.published }
            val message = "새 파일 ${results.count { it.published != null && !it.reused }}개 · 이미 내보낸 파일 ${results.count { it.reused }}개 · 실패 ${results.count { it.error != null }}개\n" +
                "${DepthLogExporter.RELATIVE_PATH}\n\n" + results.joinToString("\n\n") { result ->
                    result.error?.let { "${result.file.name}\n실패: $it (내부 원본 보존)" }
                        ?: "${result.published!!.displayName}\n${if (result.reused) "이미 내보낸 동일 내용 파일" else "저장 완료"}"
                }
            AlertDialog.Builder(this@DepthLogListActivity).setTitle("다운로드 내보내기 결과").setMessage(message)
                .setPositiveButton("확인", null).apply {
                    if (successes.isNotEmpty()) setNeutralButton("내보낸 CSV 공유") { _, _ ->
                        runCatching { DepthLogSharing.shareUris(this@DepthLogListActivity, successes.map { Uri.parse(it.id) }.distinct()) }
                            .onFailure { toast("공유 실패: ${it.message}") }
                    }
                }.show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("selected", ArrayList(selection.selected(logs).map { it.file.name }))
        super.onSaveInstanceState(outState)
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
