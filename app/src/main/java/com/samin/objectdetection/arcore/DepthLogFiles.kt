package com.samin.objectdetection.arcore

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

data class DepthLogFile(
    val file: File,
    val modifiedAtMs: Long,
    val createdAtMs: Long,
    val sizeBytes: Long,
    val ready: Boolean
)

/** Sidecars do not change CSV bytes. Legacy, unmarked CSVs remain available. */
class DepthLogFiles(private val directory: File) {
    fun list(): List<DepthLogFile> {
        if (!directory.exists()) return emptyList()
        val files = directory.listFiles() ?: error("측정 로그 폴더를 읽을 수 없습니다.")
        return files.filter { isInternalCsv(it) }.map { file ->
            val created = runCatching { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).creationTime().toMillis() }
                .getOrDefault(file.lastModified()).takeIf { it > 0 } ?: file.lastModified()
            DepthLogFile(file, file.lastModified(), created, file.length(), !incompleteMarker(file).exists())
        }.sortedWith(compareByDescending<DepthLogFile> { it.modifiedAtMs }.thenBy { it.file.name })
    }

    fun requireReady(file: File): File {
        check(isInternalCsv(file) && file.canRead()) { "측정 CSV 파일을 읽을 수 없습니다: ${file.name}" }
        check(!incompleteMarker(file).exists()) { "기록 중이거나 저장이 완료되지 않은 CSV입니다: ${file.name}" }
        return file
    }

    private fun isInternalCsv(file: File): Boolean = file.isFile && file.extension.equals("csv", true) &&
        file.canonicalFile.parentFile == directory.canonicalFile

    companion object {
        fun incompleteMarker(file: File): File = File(file.parentFile, "${file.name}.incomplete")
    }
}

/** Selection is by filename, never an index that could change after refresh/new recordings. */
class DepthLogSelection {
    private val names = linkedSetOf<String>()
    fun toggle(log: DepthLogFile) { if (log.ready && !names.remove(log.file.name)) names.add(log.file.name) }
    fun selectAll(logs: List<DepthLogFile>) { names.clear(); names.addAll(logs.filter { it.ready }.map { it.file.name }) }
    fun clear() = names.clear()
    fun selected(logs: List<DepthLogFile>): List<DepthLogFile> = logs.filter { it.ready && it.file.name in names }
    fun contains(log: DepthLogFile): Boolean = log.ready && log.file.name in names
}
