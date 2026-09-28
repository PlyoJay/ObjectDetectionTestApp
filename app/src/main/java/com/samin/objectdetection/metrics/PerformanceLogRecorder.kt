package com.samin.objectdetection.metrics

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.util.concurrent.atomic.AtomicLong

data class SavedPerformanceLog(
    val displayName: String,
    val displayPath: String,
    val uri: Uri,
    val droppedLogLines: Long
)

class PerformanceLogRecorder(private val context: Context) {
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
        val channel = Channel<String>(capacity = MAX_QUEUE_SIZE)
        val session = Session(displayName, relativePath, uri, writer, channel)
        session.worker = scope.async {
            try {
                writer.append(PerformanceLogFormatter.formatHeader(header))
                writer.flush()
                var linesSinceFlush = 0
                for (line in channel) {
                    writer.appendLine(line)
                    linesSinceFlush++
                    if (linesSinceFlush >= FLUSH_BATCH_SIZE) {
                        writer.flush()
                        linesSinceFlush = 0
                    }
                }
                writer.flush()
            } finally {
                writer.close()
            }
        }
        synchronized(lock) {
            check(activeSession == null) { "Performance logging is already active" }
            activeSession = session
        }
        return session.saved()
    }

    fun recordFrame(frame: PerformanceFrameRecord) {
        val session = activeSession ?: return
        val indexed = frame.copy(frameIndex = session.frameIndex.incrementAndGet())
        enqueue(session, PerformanceLogFormatter.formatFrame(indexed))
    }

    fun recordSummary(summary: String) {
        val session = activeSession ?: return
        enqueue(session, "\n$summary\n")
    }

    suspend fun stop(snapshot: PerformanceSnapshot): SavedPerformanceLog? {
        val session = synchronized(lock) {
            val current = activeSession ?: return null
            activeSession = null
            current
        }
        session.channel.send(PerformanceLogFormatter.formatFinalSummary(snapshot, session.droppedLines.get()))
        session.channel.close()
        var failure: Throwable? = null
        try {
            session.worker.await()
        } catch (error: Throwable) {
            failure = error
        }
        failure?.let { throw it }
        return session.saved()
    }

    fun stopAsync(snapshot: PerformanceSnapshot) {
        scope.launch { runCatching { stop(snapshot) } }
    }

    private fun enqueue(session: Session, line: String) {
        if (session.channel.trySend(line).isFailure) session.droppedLines.incrementAndGet()
    }

    private class Session(
        val displayName: String,
        val relativePath: String,
        val uri: Uri,
        val writer: BufferedWriter,
        val channel: Channel<String>
    ) {
        lateinit var worker: kotlinx.coroutines.Deferred<Unit>
        val frameIndex = AtomicLong(0L)
        val droppedLines = AtomicLong(0L)
        fun saved() = SavedPerformanceLog(displayName, relativePath, uri, droppedLines.get())
    }

    private companion object {
        const val RELATIVE_DIRECTORY = "GOTORO/performance"
        const val MAX_QUEUE_SIZE = 512
        const val FLUSH_BATCH_SIZE = 50
        val FILE_NAME_FORMAT = ThreadLocal.withInitial {
            java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        }
    }
}
