package com.samin.objectdetection.arcore

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object DepthLogSharing {
    fun shareFiles(activity: Activity, files: List<File>, catalog: DepthLogFiles, multiple: Boolean) {
        val uris = files.map { file ->
            FileProvider.getUriForFile(activity, "${activity.packageName}.files", catalog.requireReady(file))
        }
        shareUris(activity, uris, multiple)
    }

    fun shareUris(activity: Activity, uris: List<Uri>, multiple: Boolean = uris.size > 1) {
        require(uris.isNotEmpty()) { "공유할 CSV를 선택하세요." }
        val intent = Intent(if (multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = "text/csv"
            if (multiple) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            else putExtra(Intent.EXTRA_STREAM, uris.single())
            clipData = ClipData(ClipDescription("ARCore CSV", arrayOf("text/csv")), ClipData.Item(uris.first())).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, "ARCore Depth CSV 공유"))
    }
}
