package com.samin.objectdetection.detector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.camera.SizeFilterMode
import com.samin.objectdetection.camera.YoloResizeMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DetectorGoldenTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun detector(model: String = "best.tflite") = VisionStyleYoloDetector(context,model,0.2f,0.45f,
        sizeFilterMode = SizeFilterMode.DISABLED,resizeMode = YoloResizeMode.LETTERBOX)

    @Test fun actualBestModelReceivesNchwRgbAndFiniteRawOutput() {
        val bitmap = Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val dir = File(context.filesDir,"golden/color_contract")
        val d = detector()
        try {
            d.runSelfTest(bitmap,"solid_red_layout_control",dir)
            val report = JSONObject(File(dir,"report.json").readText())
            assertEquals("NCHW",report.getJSONObject("preprocessing").getString("layout"))
            assertEquals("[1,3,640,640]",report.getJSONObject("input").getJSONArray("shape").toString())
            val bytes = ByteBuffer.wrap(File(dir,"input.bin").readBytes()).order(ByteOrder.nativeOrder())
            repeat(640*640) { assertEquals(1f,bytes.float,0f) }
            repeat(2*640*640) { assertEquals(0f,bytes.float,0f) }
            assertEquals(0,report.getJSONObject("output").getInt("nonFinite"))
            assertEquals(8400,report.getJSONObject("raw").getInt("candidateCount"))
        } finally { d.close(); bitmap.recycle() }
    }

    @Test fun knownPositiveImageRunsWithoutCameraX() {
        val args = InstrumentationRegistry.getArguments()
        val input = args.getString("selfTestImage")
        val asset = "self_test/known.png"
        val hasAsset = context.assets.list("self_test")?.contains("known.png") == true
        assumeTrue("Supply -e selfTestImage <device path> or assets/$asset; no known-positive image was provided", input != null || hasAsset)
        val bitmap = if (input != null) BitmapFactory.decodeFile(input)
        else context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
        requireNotNull(bitmap)
        val dir = File(context.getExternalFilesDir(null),"golden/instrumented_${System.currentTimeMillis()}")
        val d = detector()
        try {
            val detections = d.runSelfTest(bitmap,input ?: asset,dir)
            val minimum = args.getString("selfTestMinDetections")?.toInt() ?: 1
            assertTrue("PC-positive image produced ${detections.size} detections; inspect $dir",detections.size >= minimum)
            assertTrue(detections.any { it.label == "bollard" })
        } finally { d.close(); bitmap.recycle() }
    }

    @Test fun portraitLetterboxUsesActualDimensionsAndRgb114Padding() {
        val bitmap = Bitmap.createBitmap(1080,1440,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val dir = File(context.filesDir,"golden/portrait_contract")
        val d = detector()
        try {
            d.runSelfTest(bitmap,"portrait_red_letterbox_control",dir)
            val p = JSONObject(File(dir,"report.json").readText()).getJSONObject("preprocessing")
            assertEquals(480,p.getInt("scaledWidth")); assertEquals(640,p.getInt("scaledHeight"))
            assertEquals(80,p.getInt("padLeft")); assertEquals(80,p.getInt("padRight"))
            assertEquals(0,p.getInt("padTop")); assertEquals(0,p.getInt("padBottom"))
            val bytes = ByteBuffer.wrap(File(dir,"input.bin").readBytes()).order(ByteOrder.nativeOrder())
            for (channel in 0..2) {
                assertEquals(114/255f,bytes.getFloat((channel*640*640 + 320*640 + 40)*4),0f)
                assertEquals(if (channel == 0) 1f else 0f,bytes.getFloat((channel*640*640 + 320*640 + 320)*4),0f)
            }
        } finally { d.close(); bitmap.recycle() }
    }
}
