package com.samin.objectdetection.metrics

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.samin.objectdetection.detector.InferenceExecutionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

data class SavedPerformanceLog(
    val displayName: String,
    val displayPath: String,
    val uri: Uri,
    val droppedLogLines: Long
)

class PerformanceLogRecorder(private val context: Context, private val onFailure: (Throwable) -> Unit = {}) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var activeSession: Session? = null

    val isRecording: Boolean get() = activeSession != null
    val remainingMs: Long? get() = activeSession?.window?.remainingMs(SystemClock.elapsedRealtimeNanos())

    suspend fun start(requestedHeader: PerformanceLogHeader,
        onStarted: (PerformanceLogHeader) -> Unit = {},
        snapshotOnAutoStop: (() -> PerformanceSnapshot)? = null,
        onAutoStopped: (Result<SavedPerformanceLog?>) -> Unit = {}
    ): SavedPerformanceLog {
        check(!isRecording) { "Performance logging is already active" }
        require(requestedHeader.durationMinutes in PerformanceTestWindow.ALLOWED_MINUTES)
        val displayName = "perf_${FILE_NAME_FORMAT.get()!!.format(java.util.Date(requestedHeader.startedAtMs))}.txt"
        val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$RELATIVE_DIRECTORY"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        val uri = requireNotNull(
            context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
        ) { "MediaStore insert failed" }
        val writer = try {
            requireNotNull(context.contentResolver.openOutputStream(uri, "w")) {
                "MediaStore output stream is unavailable"
            }.bufferedWriter()
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
        val header = requestedHeader.copy(startedAtMs = System.currentTimeMillis(), startedElapsedNs = SystemClock.elapsedRealtimeNanos())
        try { writer.append(PerformanceLogFormatter.formatHeader(header)); writer.flush() }
        catch (error: Throwable) { runCatching { writer.close() }; throw error }
        val logging = PerformanceLogSession(header, writer,
            if (header.systemDiagnosticsEnabled) AndroidSystemMetricSource(context) else null,
            SystemClock::elapsedRealtimeNanos, System::currentTimeMillis, onFailure, MAX_QUEUE_SIZE)
        val session = Session(displayName, relativePath, uri, logging, PerformanceTestWindow(header.startedElapsedNs, header.durationMinutes))
        synchronized(lock) {
            check(activeSession == null) { "Performance logging is already active" }
            activeSession = session
        }
        onStarted(header)
        logging.start()
        session.timer = scope.launch {
            delay(session.window.remainingMs(SystemClock.elapsedRealtimeNanos()))
            // A separate job owns stop/drain, so cancelling the timer never cancels saving.
            scope.launch {
                val result = runCatching {
                    stopSession(session, { snapshotOnAutoStop?.invoke() ?: DetectionMetricsCollector().stopPerformanceSessionSnapshot(stoppedElapsedNs = SystemClock.elapsedRealtimeNanos()) }, PerformanceStopReason.AUTO_DURATION)
                }
                if (session.stopReason == PerformanceStopReason.AUTO_DURATION) onAutoStopped(result)
            }
        }
        return session.saved()
    }

    fun recordFrame(frame: PerformanceFrameRecord) {
        val session = activeSession ?: return
        session.logging.recordFrame(frame)
    }

    fun recordSummary(summary: String) {
        val session = activeSession ?: return
        enqueue(session, "\n$summary\n")
    }

    fun recordEvent(trace: FrameTrace, execution: InferenceExecutionInfo? = null) {
        val session = activeSession ?: return
        session.logging.recordEvent(trace, execution)
    }

    fun recordOverlay(trace: FrameTrace, wallMs: Long, elapsedNs: Long, ageMs: Long, drawingEnabled: Boolean = true) {
        val session = activeSession ?: return
        if (!session.window.accepts(trace.completedElapsedNs ?: elapsedNs)) return
        enqueue(session, "OVERLAY | sessionId=${trace.sessionId} frameId=${trace.frameId} drawUnixMs=$wallMs drawElapsedNs=$elapsedNs frameAgeOverlay=${ageMs}ms drawingEnabled=$drawingEnabled")
    }

    suspend fun stop(snapshot: PerformanceSnapshot, reason: PerformanceStopReason = PerformanceStopReason.MANUAL): SavedPerformanceLog? {
        val session = activeSession ?: return null
        return stopSession(session, { snapshot }, reason)
    }

    private suspend fun stopSession(session: Session, snapshot: () -> PerformanceSnapshot, reason: PerformanceStopReason): SavedPerformanceLog? {
        val ownsStop = synchronized(lock) {
            if (activeSession !== session || session.stopping) false
            else { session.stopping = true; session.stopReason = reason; true }
        }
        if (!ownsStop) return session.finished.await()
        session.timer?.cancel()
        try {
            withContext(Dispatchers.IO) { session.logging.close(snapshot(), reason).get() }
            return session.saved().also { session.finished.complete(it) }
        } catch (error: Throwable) {
            session.finished.completeExceptionally(error)
            throw error
        } finally {
            synchronized(lock) { if (activeSession === session) activeSession = null }
        }
    }

    fun stopAsync(snapshot: PerformanceSnapshot) {
        scope.launch { runCatching { stop(snapshot) } }
    }

    private fun enqueue(session: Session, line: String) {
        session.logging.append { line }
    }

    private class Session(
        val displayName: String,
        val relativePath: String,
        val uri: Uri,
        val logging: PerformanceLogSession,
        val window: PerformanceTestWindow
    ) {
        var stopping = false
        var stopReason: PerformanceStopReason? = null
        var timer: Job? = null
        val finished = CompletableDeferred<SavedPerformanceLog?>()
        fun saved() = SavedPerformanceLog(displayName, relativePath, uri, logging.droppedLogLines)
    }

    private companion object {
        const val RELATIVE_DIRECTORY = "GOTORO/performance"
        const val MAX_QUEUE_SIZE = 512
        val FILE_NAME_FORMAT = ThreadLocal.withInitial {
            java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        }
    }
}
