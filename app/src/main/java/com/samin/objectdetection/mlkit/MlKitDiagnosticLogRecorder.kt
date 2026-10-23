package com.samin.objectdetection.mlkit

import com.samin.objectdetection.logging.OrderedLogWriter
import java.io.BufferedWriter
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Process-owned recorder. Producers only enqueue; all filesystem work runs on control/writer threads. */
internal class MlKitDiagnosticLogRecorder(
    private val directory: File,
    private val shareDirectory: File,
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val elapsedNs: () -> Long = System::nanoTime,
    private val capacity: Int = 256,
    private val maxFileBytes: Long = 5L * 1024 * 1024,
    private val maxFiles: Int = 20,
    private val maxTotalBytes: Long = 50L * 1024 * 1024,
    private val maxAgeMs: Long = TimeUnit.DAYS.toMillis(7),
    private val maxDurationMs: Long = TimeUnit.MINUTES.toMillis(30),
    private val openWriter: (File) -> BufferedWriter = { it.outputStream().bufferedWriter(Charsets.UTF_8) }
) {
    enum class Phase { IDLE, STARTING, RECORDING, SAVING, SAVED, ERROR }
    data class Status(val phase: Phase = Phase.IDLE, val fileName: String? = null,
        val startedElapsedNs: Long = 0L, val droppedLines: Long = 0L, val error: String? = null)
    data class LogFile(val name: String, val bytes: Long, val modifiedMs: Long)
    private data class Runtime(val source: String, val enabled: Boolean, val settings: String, val detectorMode: String?)
    private class Session(val file: File, val marker: File, val settings: String,
        @Volatile var source: String?, val temporaryEnable: Boolean, val startedWallMs: Long, val startedNs: Long, val logging: OrderedLogWriter,
        var reservedBytes: Long) {
        var timer: ScheduledFuture<*>? = null
        var limitReached = false
        val excluded = AtomicLong()
        val truncated = AtomicLong()
        fun dropped(): Long = logging.droppedLines.get() + excluded.get()
    }

    private val admission = Any()
    private val control = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    @Volatile private var active: Session? = null
    @Volatile private var runtime: Runtime? = null
    @Volatile private var state = Status()

    fun status(): Status = active?.let { state.copy(droppedLines = it.dropped()) } ?: state

    /** Memory-only gate, also safe for callbacks holding an old diagnostics reference. */
    fun isRecordingFor(source: String): Boolean = active?.source == source

    fun runtimeStarted(source: String, enabled: Boolean, settings: String, detectorMode: String? = null) {
        val needsCheck = synchronized(admission) {
            runtime = Runtime(source, enabled, settings, detectorMode)
            active != null || state.phase == Phase.STARTING
        }
        if (!needsCheck) return
        command {
            val current = active
            val latest = runtime
            if (current != null && latest != null && ((!latest.enabled && !current.temporaryEnable) || current.settings != latest.settings ||
                    (current.source != null && current.source != latest.source))) {
                finish(if (current.source != latest.source || current.settings != latest.settings) "RUNTIME_RESTART" else "DIAGNOSTICS_OFF")
            }
        }
    }

    fun sourceEnded(source: String): CompletableFuture<Unit> = command {
        if (active?.source == source) finish("ACTIVITY_DESTROYED")
        synchronized(admission) { if (runtime?.source == source) runtime = null }
    }

    fun start(settings: String, diagnosticsEnabled: Boolean, temporaryEnable: Boolean = false): CompletableFuture<String> = command {
        synchronized(admission) {
            check(diagnosticsEnabled || temporaryEnable) { "ML Kit Diagnostics를 ON으로 적용 및 저장한 뒤 시작하세요." }
            check(active == null) { "이미 기록 중입니다." }
            runtime?.let { check((it.enabled || temporaryEnable) && it.settings == settings) { "적용 설정이 바뀌었습니다. 설정 화면을 다시 열어 주세요." } }
            state = Status(Phase.STARTING)
        }
        try {
            recoverInterrupted()
            prune(directory)
            check(directory.isDirectory || directory.mkdirs()) { "로그 폴더를 만들 수 없습니다." }
            val startedWall = wallMs()
            val startedNs = elapsedNs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(startedWall))
            val file = File(directory, "mlkit_${stamp}_${UUID.randomUUID().toString().take(8)}.txt")
            val marker = File(directory, "${file.name}.open")
            val recordingRuntime = runtime?.takeIf { (it.enabled || temporaryEnable) && it.settings == settings }
            val source = recordingRuntime?.source
            val header = buildString {
                appendLine("ML Kit Diagnostics / UTF-8 / text only")
                appendLine("startedAt=${date(startedWall)} startedUnixMs=$startedWall startedElapsedNs=$startedNs")
                appendLine("sourceSessionId=${source ?: "PENDING"}")
                appendLine("sdkMode=${recordingRuntime?.detectorMode ?: "UNKNOWN_NO_ACTIVE_DETECTOR"}")
                appendLine("recording.temporaryDiagnostics=$temporaryEnable recording.effectiveDiagnostics=true")
                appendLine("[MLKIT_DIAG] session=${source ?: "PENDING"} frame=-1 stage=CONFIG origin=RECORDING_HEADER sdkMode=${recordingRuntime?.detectorMode ?: "UNKNOWN_NO_ACTIVE_DETECTOR"}")
                appendLine("Missing END means an interrupted recording; dropped count then is UNKNOWN.")
                appendLine("--- Applied settings at recording start ---")
                appendLine(settings)
                appendLine("--- Events (same diagnostic messages as Logcat) ---")
            }
            check(header.toByteArray(Charsets.UTF_8).size < maxFileBytes - FOOTER_RESERVE) { "설정 헤더가 너무 큽니다." }
            check(file.createNewFile()) { "로그 파일명 충돌" }
            var writer: BufferedWriter? = null
            try {
                marker.writeText("OPEN\n", Charsets.UTF_8)
                writer = openWriter(file)
                writer.append(header)
                writer.flush()
                val logging = OrderedLogWriter(writer, capacity) { failure ->
                    command {
                        active?.takeIf { it.file == file }?.let {
                            finish("WRITE_ERROR", failure)
                        }
                    }
                }
                val session = Session(file, marker, settings, source, temporaryEnable, startedWall, startedNs, logging,
                    header.toByteArray(Charsets.UTF_8).size.toLong())
                synchronized(admission) { active = session; state = Status(Phase.RECORDING, file.name, startedNs) }
                session.timer = control.schedule({ if (active === session) finish("TIME_LIMIT") }, maxDurationMs, TimeUnit.MILLISECONDS)
                file.name
            } catch (error: Throwable) {
                runCatching { writer?.close() }
                throw error
            }
        } catch (error: Throwable) {
            state = Status(Phase.ERROR, error = error.message ?: error.javaClass.simpleName)
            throw error
        }
    }

    /** No file IO, waiting for disk, settings encoding or new writer when recording is inactive. */
    fun record(source: String, line: String) {
        if (active == null) return
        synchronized(admission) {
            val session = active ?: return
            if (session.source == null) session.source = source
            if (session.source != source || session.limitReached) { session.excluded.incrementAndGet(); return }
            val text = if (line.length > MAX_LINE_CHARS) {
                session.truncated.incrementAndGet(); line.take(MAX_LINE_CHARS) + " [TRUNCATED]"
            } else line
            // UTF-8 takes at most 3 bytes per UTF-16 code unit; reserve conservatively without encoding on producers.
            val reserved = text.length.toLong() * 3 + 128
            if (session.reservedBytes + reserved > maxFileBytes - FOOTER_RESERVE) {
                session.limitReached = true
                session.excluded.incrementAndGet()
                command { if (active === session) finish("SIZE_LIMIT") }
                return
            }
            val wall = wallMs()
            val elapsed = elapsedNs()
            if (session.logging.append { "unixMs=$wall elapsedNs=$elapsed $text" }) session.reservedBytes += reserved
        }
    }

    fun stop(reason: String = "MANUAL"): CompletableFuture<Status> = command { finish(reason); state }

    private fun finish(reason: String, failure: Throwable? = null) {
        val session = synchronized(admission) {
            val current = active ?: return
            active = null
            state = Status(Phase.SAVING, current.file.name, current.startedNs, current.dropped())
            current
        }
        session.timer?.cancel(false)
        val endedWall = wallMs()
        try {
            session.logging.close {
                "END endedAt=${date(endedWall)} endedUnixMs=$endedWall " +
                    "elapsedMs=${(elapsedNs() - session.startedNs).coerceAtLeast(0) / 1_000_000L} " +
                    "reason=$reason droppedLines=${session.dropped()} truncatedLines=${session.truncated.get()}"
            }.get()
            if (failure != null) throw failure
            check(session.marker.delete() || !session.marker.exists()) { "완료 표식을 정리하지 못했습니다." }
            state = Status(Phase.SAVED, session.file.name, session.startedNs, session.dropped())
        } catch (error: Throwable) {
            // Keep the TXT and marker. A later read/start repairs its missing END, never silently deletes it.
            state = Status(Phase.ERROR, session.file.name, session.startedNs, session.dropped(),
                (failure ?: error.cause ?: error).let { it.message ?: it.javaClass.simpleName })
        }
        prune(directory)
    }

    fun files(): CompletableFuture<List<LogFile>> = command {
        recoverInterrupted()
        prune(directory)
        logFiles(directory).filter { it != active?.file }.map { LogFile(it.name, it.length(), it.lastModified()) }
    }

    fun preview(name: String): CompletableFuture<String> = command {
        val file = savedFile(name)
        val lines = file.bufferedReader(Charsets.UTF_8).use { it.lineSequence().take(121).toList() }
        lines.take(120).joinToString("\n") + if (lines.size > 120) "\n… 미리보기는 앞 120줄입니다. 전체 내용은 TXT를 공유하세요." else ""
    }

    fun delete(name: String): CompletableFuture<Unit> = command {
        val file = savedFile(name)
        check(file.delete()) { "파일을 삭제하지 못했습니다." }
        File(directory, "$name.open").delete()
        shareDirectory.listFiles()?.filter { it.isFile && it.name.startsWith("${file.nameWithoutExtension}_share_") }?.forEach { it.delete() }
    }

    /** Stop/drain first; copy a closed TXT to the existing FileProvider cache directory. */
    fun prepareShare(name: String? = null): CompletableFuture<File> = command {
        finish("SHARE")
        recoverInterrupted()
        val file = savedFile(name ?: logFiles(directory).firstOrNull()?.name ?: error("공유할 ML Kit 로그가 없습니다."))
        check(shareDirectory.isDirectory || shareDirectory.mkdirs()) { "공유 폴더를 만들 수 없습니다." }
        shareDirectory.listFiles()?.filter { it.isFile && it.name.startsWith("mlkit_") && it.name.endsWith(".txt.tmp") }?.forEach { it.delete() }
        prune(shareDirectory)
        val target = File(shareDirectory, "${file.nameWithoutExtension}_share_${UUID.randomUUID().toString().take(8)}.txt")
        val temporary = File(shareDirectory, "${target.name}.tmp")
        try {
            file.copyTo(temporary)
            check(temporary.renameTo(target)) { "공유 파일을 완성하지 못했습니다." }
        } finally { temporary.delete() }
        prune(shareDirectory, target)
        target
    }

    private fun savedFile(name: String): File {
        require(name.startsWith("mlkit_") && name.endsWith(".txt") && File(name).name == name) { "잘못된 로그 이름" }
        val file = File(directory, name)
        check(active?.file != file) { "먼저 기록을 중지하고 저장하세요." }
        check(file.isFile) { "로그 파일을 찾을 수 없습니다." }
        return file
    }

    private fun recoverInterrupted() {
        directory.listFiles()?.filter { it.name.startsWith("mlkit_") && it.name.endsWith(".txt.open") }?.forEach { marker ->
            val file = File(directory, marker.name.removeSuffix(".open"))
            if (active?.file != file) {
                if (file.isFile && !hasEnd(file)) file.appendText("\nEND reason=PROCESS_INTERRUPTED_OR_WRITE_ERROR endedUnixMs=UNKNOWN " +
                    "recoveredAt=${date(wallMs())} droppedLines=UNKNOWN\n", Charsets.UTF_8)
                check(marker.delete() || !marker.exists()) { "미완료 기록을 복구하지 못했습니다." }
            }
        }
    }

    // A marker cleanup failure after a successful close must not append a second END.
    private fun hasEnd(file: File): Boolean = RandomAccessFile(file, "r").use { input ->
        val tail = ByteArray(minOf(input.length(), FOOTER_RESERVE).toInt())
        input.seek(input.length() - tail.size)
        input.readFully(tail)
        val text = tail.toString(Charsets.UTF_8)
        text.endsWith('\n') && text.lineSequence().lastOrNull { it.isNotBlank() }?.startsWith("END ") == true
    }

    private fun logFiles(root: File): List<File> = root.listFiles()?.filter {
        it.isFile && it.name.startsWith("mlkit_") && it.extension == "txt"
    }?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name }).orEmpty()

    private fun prune(root: File, protected: File? = active?.file) {
        var kept = 0
        var bytes = 0L
        logFiles(root).forEach { file ->
            val length = file.length()
            if (file != protected && (wallMs() - file.lastModified() > maxAgeMs || kept >= maxFiles || bytes + length > maxTotalBytes)) {
                if (file.delete()) File(root, "${file.name}.open").delete()
            } else { kept++; bytes += length }
        }
    }

    private fun date(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(ms))

    private fun <T> command(action: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        control.execute { try { result.complete(action()) } catch (error: Throwable) { result.completeExceptionally(error) } }
        return result
    }

    /** Only for tests/explicit process-owner disposal, never tied to a settings Activity. */
    fun shutdown(): CompletableFuture<Unit> = command { finish("SHUTDOWN"); control.shutdown(); Unit }

    private companion object {
        const val FOOTER_RESERVE = 2048L
        const val MAX_LINE_CHARS = 8192
    }
}
