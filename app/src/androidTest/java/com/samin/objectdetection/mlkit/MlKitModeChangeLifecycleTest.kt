package com.samin.objectdetection.mlkit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.android.odml.image.MlImage
import com.samin.objectdetection.MainActivity
import com.samin.objectdetection.camera.CameraController
import com.samin.objectdetection.camera.FrameRoi
import com.samin.objectdetection.detector.YoloModel
import com.samin.objectdetection.metrics.FrameTrace
import com.samin.objectdetection.metrics.FrameLatencyRecorder
import com.samin.objectdetection.settings.*
import com.samin.objectdetection.ui.MainScreenView
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Controlled Tasks prove resource ordering only; real detection is tested separately. */
class MlKitModeChangeLifecycleTest {
    @Test fun successDrainsLeaseBeforeModeRecreation() = exercise("SUCCESS")
    @Test fun failureDrainsLeaseBeforeModeRecreation() = exercise("ERROR")
    @Test fun cancelledSdkTaskDrainsLeaseBeforeModeRecreation() = exercise("CANCELLED")

    private fun exercise(outcome: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("object_detection_app_settings", Context.MODE_PRIVATE)
        val backup = HashMap(preferences.all)
        val recorder = MlKitDiagnosticLogs.get(context)
        val before = recorder.files().get(10, TimeUnit.SECONDS).map { it.name }.toSet()
        val settings = AppSettings(yolo = YoloSettings(model = YoloModel.YOLO_512, interpreterThreadCount = 2),
            warning = WarningSettings(enableActualVibration = false, enableActualBeep = false, enableActualTts = false))
        val cancel = CancellationTokenSource()
        val task = TaskCompletionSource<List<DetectedObject>>(cancel.token)
        val started = CountDownLatch(1)
        val closes = AtomicInteger()
        val closedBeforeTerminal = AtomicBoolean()
        val closedBeforeRecycle = AtomicBoolean()
        var leasedBitmap: Bitmap? = null
        val fake = object : com.google.mlkit.vision.objects.ObjectDetector {
            override fun process(image: InputImage) = task.task.also { started.countDown() }
            override fun process(image: MlImage) = error("unused")
            override fun process(bitmap: Bitmap, rotation: Int) = error("unused")
            override fun process(image: Image, rotation: Int) = error("unused")
            override fun process(image: Image, rotation: Int, matrix: Matrix) = error("unused")
            override fun process(buffer: ByteBuffer, width: Int, height: Int, rotation: Int, format: Int) = error("unused")
            override fun getDetectorType() = 5
            override fun close() {
                closes.incrementAndGet()
                closedBeforeTerminal.set(!task.task.isComplete)
                closedBeforeRecycle.set(leasedBitmap?.isRecycled != true)
            }
        }
        val source = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            listOf(android.Manifest.permission.CAMERA, android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.POST_NOTIFICATIONS).forEach {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
            }
            recorder.stop().get(10, TimeUnit.SECONDS)
            preferences.edit().clear().putString("applied_settings", AppSettingsCodec.encode(settings)).commit()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            var old: MainActivity? = null
            var cameraStopped: java.util.concurrent.CompletableFuture<Unit>? = null
            scenario.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                old = it
                cameraStopped = (field(it, "cameraController") as CameraController).stopAsync()
            }
            cameraStopped!!.get(20, TimeUnit.SECONDS)
            val previous = checkNotNull(old)
            await { !(field(previous, "isMlKitProcessing") as AtomicBoolean).get() }
            (field(previous, "mlKitDetector") as MlKitObjectDetector).close()
            val oldBuffer = field(previous, "mlKitInputBuffer") as MlKitInputBuffer
            scenario.onActivity {
                setField(it, "mlKitDetector", MlKitObjectDetector(fake, MlKitDetectionMode.STREAM_MODE))
                (field(it, "screen") as MainScreenView).mlKitLogButton.performClick()
            }
            await { recorder.status().phase == MlKitDiagnosticLogRecorder.Phase.RECORDING }
            val logName = checkNotNull(recorder.status().fileName)
            scenario.onActivity {
                MainActivity::class.java.getDeclaredField("lastMlKitDetectionTime").apply { isAccessible = true }.setLong(it, 0)
                val now = android.os.SystemClock.elapsedRealtimeNanos()
                MainActivity::class.java.getDeclaredMethod("maybeRunMlKitDetection", Bitmap::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, FrameTrace::class.java, FrameLatencyRecorder::class.java)
                    .apply { isAccessible = true }.invoke(it, source, 640, 360,
                        FrameTrace("controlled-$outcome", 999, System.currentTimeMillis(), now, true, System.currentTimeMillis(), now), null)
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            leasedBitmap = field(oldBuffer, "bitmap") as Bitmap
            assertThrows(IllegalStateException::class.java) { oldBuffer.acquire(source, FrameRoi(0, 0, 640, 360)) }
            val updated = settings.copy(mlKit = settings.mlKit.copy(mode = MlKitDetectionMode.SINGLE_IMAGE_MODE))
            AppSettingsStore(context).saveApplied(updated)
            // Same restart path invoked by SettingsActivity's applied result, with the Task held pending.
            scenario.onActivity { MainActivity::class.java.getDeclaredMethod("restartForSettings").apply { isAccessible = true }.invoke(it) }
            Thread.sleep(250)
            assertEquals(0, closes.get())
            assertFalse(checkNotNull(leasedBitmap).isRecycled)
            assertFalse(task.task.isComplete)
            when (outcome) {
                "SUCCESS" -> task.setResult(listOf(DetectedObject(Rect(10, 20, 90, 100), 1, emptyList())))
                "ERROR" -> task.setException(IllegalStateException("controlled failure"))
                else -> cancel.cancel()
            }
            var replacement: MainActivity? = null
            await {
                checkNotNull(scenario).onActivity { replacement = it }
                replacement !== previous && (field(checkNotNull(replacement), "mlKitDetector") as MlKitObjectDetector).mode == MlKitDetectionMode.SINGLE_IMAGE_MODE
            }
            assertEquals(1, closes.get())
            assertFalse(closedBeforeTerminal.get()); assertFalse(closedBeforeRecycle.get())
            assertTrue(checkNotNull(leasedBitmap).isRecycled)
            assertNotSame(oldBuffer, field(checkNotNull(replacement), "mlKitInputBuffer"))
            assertThrows(IllegalStateException::class.java) { oldBuffer.acquire(source, FrameRoi(0, 0, 640, 360)) }
            val text = File(context.filesDir, "mlkit_diagnostics/$logName").readText()
            assertTrue(text.contains("sdkMode=STREAM_MODE")); assertTrue(text.contains("outcome=$outcome"))
            assertTrue(text.contains("stage=LEASE_RELEASED")); assertTrue(text.contains("END endedAt="))
            assertFalse(text.contains("mode=SINGLE_IMAGE_MODE"))
            assertEquals(updated, AppSettingsStore(context).loadApplied())
        } finally {
            if (!task.task.isComplete) task.trySetResult(emptyList())
            scenario?.close()
            recorder.stop().get(20, TimeUnit.SECONDS)
            recorder.files().get(10, TimeUnit.SECONDS).filter { it.name !in before }.forEach { recorder.delete(it.name).get(10, TimeUnit.SECONDS) }
            source.recycle()
            val restore = preferences.edit().clear()
            backup.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
                is Float -> restore.putFloat(key, value)
                is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            restore.commit()
        }
    }

    private fun field(target: Any, name: String): Any = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)!!
    private fun setField(target: Any, name: String, value: Any) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    private fun await(check: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (check()) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for SDK drain/mode recreation")
    }
}
