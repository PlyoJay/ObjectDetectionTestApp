package com.samin.objectdetection.arcore

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.io.InputStream
import java.io.OutputStream

/** Downloads API requires Android 10+. This app's minSdk is 33; no storage permission is needed. */
class MediaStoreDepthDownloads(private val resolver: ContentResolver) : DepthLogDownloadStore {
    private val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun listPublished(): List<PublicDepthLog> = buildList {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val cursor = resolver.query(collection, projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
            arrayOf(DepthLogExporter.RELATIVE_PATH), null) ?: error("다운로드 목록 조회에 실패했습니다.")
        cursor.use {
            val id = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (it.moveToNext()) add(PublicDepthLog(ContentUris.withAppendedId(collection, it.getLong(id)).toString(), it.getString(name)))
        }
    }

    override fun openRead(log: PublicDepthLog): InputStream =
        resolver.openInputStream(Uri.parse(log.id)) ?: error("기존 공용 CSV를 읽을 수 없습니다.")

    override fun createPending(name: String): PublicDepthLog {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DepthLogExporter.RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("다운로드 파일을 만들 수 없습니다.")
        return PublicDepthLog(uri.toString(), name)
    }

    override fun openWrite(log: PublicDepthLog): OutputStream =
        resolver.openOutputStream(Uri.parse(log.id), "w") ?: error("다운로드 파일 쓰기를 시작할 수 없습니다.")

    override fun publish(log: PublicDepthLog): PublicDepthLog {
        val uri = Uri.parse(log.id)
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
            "완성된 다운로드 파일을 공개하지 못했습니다."
        }
        // Publication can resolve a filesystem name collision; report the final provider name.
        val actual = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            check(it.moveToFirst()) { "다운로드 파일명을 확인할 수 없습니다." }
            it.getString(0)
        } ?: error("다운로드 파일명을 확인할 수 없습니다.")
        return log.copy(displayName = actual)
    }

    override fun deletePending(log: PublicDepthLog) {
        check(resolver.delete(Uri.parse(log.id), null, null) == 1) { "미완성 다운로드를 정리하지 못했습니다." }
    }
}
