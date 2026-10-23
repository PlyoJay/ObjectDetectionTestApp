package com.samin.objectdetection.mlkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.samin.objectdetection.camera.FrameRoi
import com.samin.objectdetection.camera.RecognitionRoi
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real SDK comparison. Errors are recorded and fail the test, never treated as successful zeroes. */
class MlKitModeComparisonTest {
    @Test fun officialPositiveImageAcrossOriginalRoiStretchAndStream() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val output = File(instrumentation.targetContext.filesDir, "mlkit_mode_comparison").apply { mkdirs() }
        val source = instrumentation.context.assets.open("mlkit_validation/640px-shoes.jpg").use {
            checkNotNull(BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }))
        }
        // Explicit comparison ROI from the reported phone setup, without changing any app default.
        val roi = FrameRoi.calculate(source.width, source.height,
            RecognitionRoi(true, top = .20f, left = .10f, right = .10f), false)
        val cropped = Bitmap.createBitmap(source, roi.left, roi.top, roi.width, roi.height)
        val buffer = MlKitInputBuffer()
        val lease = buffer.acquire(source, roi)
        val rows = mutableListOf("condition\titeration\tinput\toutcome\tsdkCount\telapsedMs\tbboxes_or_error")
        rows.add("# source=${source.width}x${source.height} roi=$roi SDK=object-detection:17.0.2 rotation=0 multi=true classification=false")
        val errors = mutableListOf<String>()
        var positiveOriginal = false
        try {
            val conditions = listOf("A_SINGLE_ORIGINAL" to source, "B_SINGLE_ROI_ASPECT" to cropped,
                "C_SINGLE_ROI_640x360" to lease.bitmap, "D_STREAM_ROI_640x360" to lease.bitmap)
            conditions.forEach { (name, image) ->
                // Only this explicit instrumentation flag writes input images, never the app default path.
                if (args.getString("mlkitSaveInputs") == "true") {
                    File(output, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                }
                val streaming = name.startsWith("D_")
                val detector = ObjectDetection.getClient(ObjectDetectorOptions.Builder()
                    .setDetectorMode(if (streaming) ObjectDetectorOptions.STREAM_MODE else ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                    .enableMultipleObjects().build())
                try {
                    repeat(if (streaming) 8 else 3) { index ->
                        if (streaming && index > 0) Thread.sleep(1500)
                        val start = SystemClock.elapsedRealtimeNanos()
                        val row = try {
                            val results = Tasks.await(detector.process(InputImage.fromBitmap(image, 0)))
                            if (name.startsWith("A_") && results.isNotEmpty()) positiveOriginal = true
                            "$name\t$index\t${image.width}x${image.height}\tSUCCESS\t${results.size}\t" +
                                "${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0}\t" +
                                results.joinToString { "${it.boundingBox.toShortString()} trackingId=${it.trackingId}" }
                        } catch (error: Exception) {
                            val detail = "${error.cause ?: error}".replace('\n', ' ')
                            errors.add("$name $detail")
                            "$name\t$index\t${image.width}x${image.height}\tERROR\t-1\t" +
                                "${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0}\t$detail"
                        }
                        rows.add(row); Log.i("MlKitModeComparison", row)
                        File(output, "results.tsv").writeText(rows.joinToString("\n") + "\n", Charsets.UTF_8)
                    }
                } finally { detector.close() }
            }
            assertTrue("Real SDK errors: $errors", errors.isEmpty())
            assertTrue("Published positive fixture was not detected locally; inspect results.tsv. Zeroes do not prove SDK recognition works.", positiveOriginal)
        } finally {
            lease.close(); buffer.close(); cropped.recycle(); source.recycle()
        }
    }
}
