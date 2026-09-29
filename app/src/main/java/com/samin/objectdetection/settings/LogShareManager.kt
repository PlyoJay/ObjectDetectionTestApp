package com.samin.objectdetection.settings

import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LogShareManager(private val activity: ComponentActivity) {
    fun shareLatest() {
        activity.lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { createLatestArchive() } }
                .onSuccess { archive ->
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", archive)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(intent, "테스트 로그 공유"))
                }
                .onFailure { Toast.makeText(activity, "공유 파일 생성 실패: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun createLatestArchive(): File {
        val shareDir = File(activity.cacheDir, "shared_logs").apply { mkdirs() }
        val archive = File(shareDir, "object_detection_latest_logs.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            var count = 0
            latestPerformanceLog()?.let { (name, bytes) ->
                zip.putNextEntry(ZipEntry("performance/$name")); zip.write(bytes); zip.closeEntry(); count++
            }
            latestDebugSession()?.let { session ->
                session.walkTopDown().filter(File::isFile).forEach { file ->
                    zip.putNextEntry(ZipEntry("debug/${session.name}/${file.relativeTo(session).invariantSeparatorsPath}"))
                    FileInputStream(file).use { it.copyTo(zip) }; zip.closeEntry(); count++
                }
            }
            latestEvaluationFiles().forEach { file ->
                zip.putNextEntry(ZipEntry("evaluation/${file.parentFile?.name}/${file.name}"))
                FileInputStream(file).use { it.copyTo(zip) }; zip.closeEntry(); count++
            }
            require(count > 0) { "공유할 로그가 없습니다." }
            zip.putNextEntry(ZipEntry("settings.properties"))
            zip.write(AppSettingsCodec.snapshotLines(AppSettingsStore(activity).loadApplied()).toByteArray())
            zip.closeEntry()
        }
        return archive
    }

    private fun latestPerformanceLog(): Pair<String, ByteArray>? {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val path = "${Environment.DIRECTORY_DOCUMENTS}/GOTORO/performance/"
        activity.contentResolver.query(
            collection, projection, "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(path),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getLong(0)
            val name = cursor.getString(1)
            val uri = android.content.ContentUris.withAppendedId(collection, id)
            return name to requireNotNull(activity.contentResolver.openInputStream(uri)).use { it.readBytes() }
        }
        return null
    }

    private fun latestDebugSession(): File? = File(activity.getExternalFilesDir(null), "debug_detection")
        .listFiles()?.filter(File::isDirectory)?.maxByOrNull(File::lastModified)

    private fun latestEvaluationFiles(): List<File> {
        val root = File(activity.getExternalFilesDir(null), "ObjectDetectionTestApp")
        return listOf("recordings", "captures").flatMap { child ->
            File(root, child).listFiles()?.filter { file ->
                file.isFile && file.extension.lowercase() in setOf("json", "jsonl", "txt")
            }.orEmpty()
                .sortedByDescending(File::lastModified).take(3)
        }
    }
}
