package com.samin.objectdetection.settings

import android.app.AlertDialog
import android.graphics.Typeface
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.samin.objectdetection.mlkit.MlKitDiagnosticLogRecorder
import com.samin.objectdetection.mlkit.MlKitDiagnosticLogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture

/** Small controls in the existing debug section; the process recorder survives this editor. */
internal class MlKitLogControls(
    private val activity: ComponentActivity,
    parent: LinearLayout,
    private val applied: () -> AppSettings,
    private val visible: () -> Boolean
) {
    private val recorder = MlKitDiagnosticLogs.get(activity)
    private val status = TextView(activity).apply { textSize = 13f; setPadding(0, 8, 0, 8) }
    private val start = button(parent, "ML Kit 진단 로그 기록 시작")
    private val stop = button(parent, "기록 중지 및 파일 저장")
    private val recent = button(parent, "최근 ML Kit 기록 보기")
    private val share = button(parent, "최근 ML Kit 로그 공유")
    private var busy = false
    private var files = emptyList<MlKitDiagnosticLogRecorder.LogFile>()
    private var observedCompletion: MlKitDiagnosticLogRecorder.Status? = null

    init {
        parent.addView(status)
        start.setOnClickListener {
            perform({ recorder.start(AppSettingsCodec.snapshotLines(applied()), applied().debug.enableMlKitDiagnostics) }) {
                toast("기록 시작. 설정 목록으로 돌아간 뒤 카메라 화면에서 테스트하세요.")
            }
        }
        stop.setOnClickListener { perform({ recorder.stop() }) { result ->
            toast(if (result.phase == MlKitDiagnosticLogRecorder.Phase.ERROR) "기록 오류가 발생했습니다. 남아 있는 TXT를 확인하세요." else "ML Kit 진단 로그 저장 완료")
        } }
        recent.setOnClickListener { refreshFiles(showList = true) }
        share.setOnClickListener { shareFile(null) }
        refreshFiles()
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (visible()) render()
                    delay(1000)
                }
            }
        }
    }

    private fun render() {
        val state = recorder.status()
        if (!busy && state.phase in listOf(MlKitDiagnosticLogRecorder.Phase.SAVED, MlKitDiagnosticLogRecorder.Phase.ERROR) && state != observedCompletion) {
            observedCompletion = state
            refreshFiles()
            return
        }
        val active = state.phase == MlKitDiagnosticLogRecorder.Phase.RECORDING
        val saving = state.phase in listOf(MlKitDiagnosticLogRecorder.Phase.STARTING, MlKitDiagnosticLogRecorder.Phase.SAVING)
        val seconds = if (active) ((android.os.SystemClock.elapsedRealtimeNanos() - state.startedElapsedNs) / 1_000_000_000L).coerceAtLeast(0) else 0
        status.text = buildString {
            append(when (state.phase) {
                MlKitDiagnosticLogRecorder.Phase.RECORDING -> "기록 중 %02d:%02d · 누락 %d줄".format(seconds / 60, seconds % 60, state.droppedLines)
                MlKitDiagnosticLogRecorder.Phase.STARTING -> "기록 준비 중…"
                MlKitDiagnosticLogRecorder.Phase.SAVING -> "로그 저장 중…"
                MlKitDiagnosticLogRecorder.Phase.ERROR -> "기록 오류: ${state.error} · 확인된 누락 ${state.droppedLines}줄, 오류 구간 손실 미확인 (TXT 보존)"
                MlKitDiagnosticLogRecorder.Phase.SAVED -> "저장 완료 · 누락 ${state.droppedLines}줄"
                else -> "기록 중지 상태"
            })
            append("\n최근 기록: ${files.firstOrNull()?.let { "${it.name} (${it.bytes} bytes)" } ?: "없음"}")
            if (!active && !applied().debug.enableMlKitDiagnostics) append("\n메인 화면에서 설정 변경 없이 기록을 시작할 수 있습니다.")
            if (active) append("\n설정 화면을 나가도 기록합니다. 공유하면 먼저 중지·저장합니다.")
        }
        start.isEnabled = !busy && !active && !saving && applied().debug.enableMlKitDiagnostics
        stop.isEnabled = !busy && active
        recent.isEnabled = !busy && !saving
        share.isEnabled = !busy && !saving && (active || files.isNotEmpty())
    }

    private fun refreshFiles(showList: Boolean = false) {
        perform({ recorder.files() }, refresh = false) { values ->
            files = values
            if (showList) {
                if (values.isEmpty()) toast("저장된 ML Kit 로그가 없습니다.")
                else AlertDialog.Builder(activity).setTitle("ML Kit 진단 기록")
                    .setItems(values.map { "${it.name} · ${it.bytes} bytes" }.toTypedArray()) { _, index -> preview(values[index].name) }
                    .setNegativeButton("닫기", null).show()
            }
        }
    }

    private fun preview(name: String) {
        perform({ recorder.preview(name) }, refresh = false) { text ->
            val content = TextView(activity).apply { this.text = text; textSize = 12f; typeface = Typeface.MONOSPACE; setPadding(16, 16, 16, 16); setTextIsSelectable(true) }
            AlertDialog.Builder(activity).setTitle(name).setView(ScrollView(activity).apply { addView(content) })
                .setPositiveButton("공유") { _, _ -> shareFile(name) }.setNegativeButton("닫기", null)
                .setNeutralButton("삭제") { _, _ ->
                    AlertDialog.Builder(activity).setTitle("기록 삭제").setMessage(name)
                        .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                            perform({ recorder.delete(name) }) { toast("기록을 삭제했습니다.") }
                        }.show()
                }.show()
        }
    }

    private fun shareFile(name: String?) {
        perform({ recorder.prepareShare(name) }) { file -> LogShareManager(activity).shareMlKit(file) }
    }

    private fun <T> perform(operation: () -> CompletableFuture<T>, refresh: Boolean = true, success: (T) -> Unit) {
        if (busy) return
        busy = true
        render()
        activity.lifecycleScope.launch {
            try {
                val value = withContext(Dispatchers.IO) { operation().get() }
                success(value)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                toast(error.cause?.message ?: error.message ?: "로그 작업 실패")
            } finally {
                busy = false
                render()
                if (refresh && !activity.isDestroyed) refreshFiles()
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    private fun button(parent: LinearLayout, title: String): Button = Button(activity).apply {
        text = title
        parent.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
}
