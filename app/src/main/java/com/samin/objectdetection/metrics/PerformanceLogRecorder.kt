package com.samin.objectdetection.metrics

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    suspend fun start(header: PerformanceLogHeader): SavedPerformanceLog {
        check(!isRecording) { "Performance logging is already active" }
        val displayName = "perf_${FILE_NAME_FORMAT.get()!!.format(java.util.Date(header.startedAtMs))}.txt"
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
        try { writer.append(PerformanceLogFormatter.formatHeader(header)); writer.flush() }
        catch (error: Throwable) { runCatching { writer.close() }; throw error }
        val logging = PerformanceLogSession(header, writer,
            if (header.systemDiagnosticsEnabled) AndroidSystemMetricSource(context) else null,
            SystemClock::elapsedRealtimeNanos, System::currentTimeMillis, onFailure, MAX_QUEUE_SIZE)
        val session = Session(displayName, relativePath, uri, logging)
        synchronized(lock) {
            check(activeSession == null) { "Performance logging is already active" }
            activeSession = session
        }
        logging.start()
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

    fun recordEvent(trace: FrameTrace) {
        val session = activeSession ?: return
        enqueue(session, "ERROR | ${PerformanceLogFormatter.formatTrace(trace)}")
    }

    fun recordOverlay(trace: FrameTrace, wallMs: Long, elapsedNs: Long, ageMs: Long) {
        val session = activeSession ?: return
        enqueue(session, "OVERLAY | sessionId=${trace.sessionId} frameId=${trace.frameId} drawUnixMs=$wallMs drawElapsedNs=$elapsedNs frameAgeOverlay=${ageMs}ms")
    }

    suspend fun stop(snapshot: PerformanceSnapshot): SavedPerformanceLog? {
        val session = synchronized(lock) {
            val current = activeSession ?: return null
            activeSession = null
            current
        }
        withContext(Dispatchers.IO) {
            session.logging.close(snapshot).get()
        }
        return session.saved()
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
        val logging: PerformanceLogSession
    ) {
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
