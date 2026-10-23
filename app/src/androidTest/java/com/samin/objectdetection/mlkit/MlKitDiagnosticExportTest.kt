package com.samin.objectdetection.mlkit

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.android.odml.image.MlImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.samin.objectdetection.settings.LogShareManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class MlKitDiagnosticExportTest {
    @Test fun sdkAndOverlayMessagesUseSameSinkAndFileProviderCanReadCompletedUtf8Txt(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "mlkit_export_test_${System.nanoTime()}")
        val recorder = MlKitDiagnosticLogRecorder(root, File(context.cacheDir, "shared_logs"))
        val logcatMessages = mutableListOf<String>()
        val logger = MlKitDiagnostics("export-session", android.os.SystemClock::elapsedRealtimeNanos) {
            android.util.Log.i("MlKitDiagnostics", it)
            logcatMessages.add(it)
            recorder.record("export-session", it)
        }
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val detector = MlKitObjectDetector(FakeDetector())
        var shared: File? = null
        try {
            recorder.start("mlKit.enabled=true\ncamera.previewEnabled=true\ncamera.roi.top=0.2", true).get(5, TimeUnit.SECONDS)
            logger.acquired(1)
            val results = detector.detect(bitmap, diagnosticLog = { detail -> logger.event(1, "SDK") { detail } })
            logger.event(1, "MAPPED") { "mappedCount=${results.size} 한글" }
            logger.event(1, "OVERLAY_DRAW") { "drawCalls=${results.size}" }
            logger.released(1)
            shared = recorder.prepareShare().get(5, TimeUnit.SECONDS)
            val send = LogShareManager.mlKitShareIntent(context, shared)
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val uri = checkNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            assertEquals("content", uri.scheme)
            assertEquals(uri, send.clipData!!.getItemAt(0).uri)
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertTrue(text.contains("sdkCount=2")); assertTrue(text.contains("mappedCount=2 한글"))
            assertTrue(text.contains("drawCalls=2")); assertTrue(text.contains("END endedAt="))
            logcatMessages.forEach { assertTrue("Missing same diagnostic message", text.contains(it)) }
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.files", root.listFiles()!!.first { it.extension == "txt" })
            }
        } finally {
            detector.close(); bitmap.recycle(); recorder.shutdown().get(5, TimeUnit.SECONDS)
            root.listFiles()?.forEach { it.delete() }; root.delete(); shared?.delete()
        }
    }

    private class FakeDetector : com.google.mlkit.vision.objects.ObjectDetector {
        override fun process(image: InputImage) = Tasks.forResult(listOf(
            DetectedObject(Rect(1, 2, 100, 200), 1, emptyList()), DetectedObject(Rect(200, 20, 400, 300), 2, emptyList())))
        override fun process(image: MlImage) = error("unused")
        override fun process(bitmap: Bitmap, rotation: Int) = error("unused")
        override fun process(image: Image, rotation: Int) = error("unused")
        override fun process(image: Image, rotation: Int, matrix: Matrix) = error("unused")
        override fun process(buffer: ByteBuffer, width: Int, height: Int, rotation: Int, format: Int) = error("unused")
        override fun getDetectorType() = 5
        override fun close() = Unit
    }
}
