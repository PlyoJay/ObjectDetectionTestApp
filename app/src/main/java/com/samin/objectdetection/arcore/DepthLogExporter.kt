package com.samin.objectdetection.arcore

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture

data class PublicDepthLog(val id: String, val displayName: String, val relativePath: String = DepthLogExporter.RELATIVE_PATH)

/** Android adapter owns MediaStore details; pure export logic is also tested against a fake store. */
interface DepthLogDownloadStore {
    fun listPublished(): List<PublicDepthLog>
    fun openRead(log: PublicDepthLog): InputStream
    fun createPending(name: String): PublicDepthLog
    fun openWrite(log: PublicDepthLog): OutputStream
    fun publish(log: PublicDepthLog): PublicDepthLog
    fun deletePending(log: PublicDepthLog)
}

data class DepthLogExportResult(val file: File, val published: PublicDepthLog? = null,
    val reused: Boolean = false, val error: String? = null)

class DepthLogExporter(private val files: DepthLogFiles, private val store: DepthLogDownloadStore) {
    /** Never runs the export (or touches MediaStore) before flush/close/drain succeeds. */
    fun afterClosed(file: File, closed: CompletableFuture<Unit>): CompletableFuture<DepthLogExportResult> =
        closed.handleAsync { _, error ->
            if (error != null) DepthLogExportResult(file, error = "내부 CSV 저장 실패: ${error.cause?.message ?: error.message}")
            else export(file)
        }

    fun export(file: File): DepthLogExportResult = synchronized(exportLock) {
        var pending: PublicDepthLog? = null
        try {
            files.requireReady(file)
            val length = file.length()
            val modified = file.lastModified()
            val digest = file.inputStream().use(::sha256)
            val alternate = file.nameWithoutExtension.take(160) + "__${digest.take(12)}.csv"
            val candidates = store.listPublished().filter { log ->
                log.displayName == file.name || log.displayName == alternate ||
                    log.displayName.startsWith(file.nameWithoutExtension + " (") ||
                    log.displayName.startsWith(alternate.removeSuffix(".csv") + " (")
            }
            for (log in candidates) {
                val existing = try { store.openRead(log).use(::sha256) } catch (_: SecurityException) { continue }
                if (existing == digest) {
                    checkUnchanged(file, length, modified)
                    return@synchronized DepthLogExportResult(file, log, reused = true)
                }
            }
            checkUnchanged(file, length, modified)
            // Only write the URI created by this insert. Never open an existing file for writing.
            pending = store.createPending(if (candidates.isEmpty()) file.name else alternate)
            val copied = MessageDigest.getInstance("SHA-256")
            var copiedBytes = 0L
            store.openWrite(pending).use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count); copied.update(buffer, 0, count); copiedBytes += count
                    }
                }
            }
            check(copiedBytes == length && hex(copied.digest()) == digest) { "내보내기 중 CSV 내용이 변경되었습니다." }
            checkUnchanged(file, length, modified)
            val published = store.publish(pending)
            DepthLogExportResult(file, published)
        } catch (error: Exception) {
            val cleanupError = pending?.let { log -> runCatching { store.deletePending(log) }.exceptionOrNull() }
            DepthLogExportResult(file, error = (error.message ?: error.javaClass.simpleName) +
                (cleanupError?.let { " · 미완성 다운로드 정리 실패: ${it.message}" } ?: ""))
        }
    }

    private fun checkUnchanged(file: File, length: Long, modified: Long) {
        files.requireReady(file)
        check(file.length() == length && file.lastModified() == modified) { "CSV가 변경되었습니다. 기록 종료 후 다시 시도하세요." }
    }

    companion object {
        const val RELATIVE_PATH = "Download/GOTORO/ARCore/"
        private val exportLock = Any() // Also serializes auto-export and another Activity's batch.
        private fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            return hex(digest.digest())
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
