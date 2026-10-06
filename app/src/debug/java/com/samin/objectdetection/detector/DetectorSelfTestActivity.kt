package com.samin.objectdetection.detector

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.samin.objectdetection.camera.SizeFilterMode
import com.samin.objectdetection.camera.YoloResizeMode
import java.io.File
import java.util.concurrent.Executors

/** Camera-free debug entry point; never shares an Interpreter with MainActivity. */
class DetectorSelfTestActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { setPadding(32, 32, 32, 32); text = "SELF TEST running…" }
        setContentView(status)
        val inputPath = intent.getStringExtra("input")
        val asset = intent.getStringExtra("asset") ?: "self_test/known.png"
        val model = intent.getStringExtra("model") ?: "best.tflite"
        worker.execute {
            val result = runCatching {
                val bitmap = if (inputPath != null) BitmapFactory.decodeFile(inputPath)
                else assets.open(asset).use { BitmapFactory.decodeStream(it) }
                requireNotNull(bitmap) { "Cannot decode self-test image: ${inputPath ?: asset}" }
                val dir = File(getExternalFilesDir(null) ?: filesDir, "golden/self_test_${System.currentTimeMillis()}")
                try {
                    val detector = VisionStyleYoloDetector(this, model, 0.2f, 0.45f,
                        sizeFilterMode = SizeFilterMode.DISABLED, resizeMode = YoloResizeMode.LETTERBOX)
                    try {
                        val detections = detector.runSelfTest(bitmap, inputPath ?: asset, dir)
                        "SELF TEST\ndetections=${detections.size}\n$dir\n" +
                            detections.joinToString("\n") { "${it.label} ${it.confidence} [${it.left},${it.top},${it.right},${it.bottom}]" }
                    } finally { detector.close() }
                } finally { bitmap.recycle() }
            }.getOrElse {
                Log.e("DetectorSelfTest", "SELF TEST failed", it)
                "SELF TEST ERROR\n$it\nPass --es input <device path>, or use assets/self_test/known.png."
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) status.text = result }
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
