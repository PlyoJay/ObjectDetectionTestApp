package com.samin.objectdetection.camera

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.settings.SettingsActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real CameraX frames and drained OFF -> ON -> OFF rebinding; no detector or warning outputs. */
class CameraPreviewRuntimeTest {
    @Test fun analysisDeliversFramesWithAndWithoutPreviewAcrossRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val preferences = context.getSharedPreferences("object_detection_app_settings", Context.MODE_PRIVATE)
        val original = preferences.all.toMap()
        var controller: CameraController? = null
        var scenario: ActivityScenario<SettingsActivity>? = null
        try {
            // The host settings Activity must not advance a user's pending rollback marker.
            preferences.edit().clear().commit()
            scenario = ActivityScenario.launch(SettingsActivity::class.java)
            val sizes = mutableListOf<Pair<Int, Int>>()
            for (previewEnabled in listOf(false, true, false)) {
                val frames = CountDownLatch(3)
                val failure = AtomicReference<Throwable?>()
                val size = AtomicReference<Pair<Int, Int>>()
                scenario.onActivity { activity ->
                    val preview = PreviewView(activity)
                    activity.setContentView(preview, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    controller = CameraController(context, activity, preview, 0, false,
                        cameraPreviewEnabled = previewEnabled, distanceMetadataEnabled = true, listener = object : CameraController.Listener {
                            override fun onFrameReceived(frameTimestampMs: Long, analyzerReceivedMs: Long) = Unit
                            override fun onFrameSkipped(reason: CameraController.SkipReason, skippedFrameCount: Long) = Unit
                            override fun onCameraStarted() = Unit
                            override fun onCameraError(error: Throwable) { failure.set(error); repeat(3) { frames.countDown() } }
                            override fun onFrameError(error: Throwable, timing: CameraFrameTiming?) { failure.set(error); repeat(3) { frames.countDown() } }
                            override fun onFrame(bitmap: Bitmap, timing: CameraFrameTiming, rotationDegrees: Int) {
                                val geometry = requireNotNull(timing.distanceGeometry)
                                assertTrue(geometry.automaticIntrinsics != null || !geometry.automaticUnavailableReason.isNullOrBlank())
                                assertEquals(bitmap.width, geometry.width)
                                assertEquals(bitmap.height, geometry.height)
                                val k = requireNotNull(geometry.intrinsics(com.samin.objectdetection.distance.DistanceSettings(
                                    150f, 10f, com.samin.objectdetection.distance.DistanceOpticsMode.MANUAL, 60f)))
                                val bottom = k.cy + k.fy * .1f
                                val estimate = com.samin.objectdetection.distance.GroundPlaneDistanceEstimator(
                                    com.samin.objectdetection.distance.DistanceSettings(150f, 10f,
                                        com.samin.objectdetection.distance.DistanceOpticsMode.MANUAL, 60f)).estimate(
                                    com.samin.objectdetection.detector.DetectionResult("person", .9f, k.cx - 10, k.cy - 10,
                                        k.cx + 10, bottom), geometry)
                                assertNotNull("Distance analysis must continue when preview=$previewEnabled", estimate.meters)
                                size.set(bitmap.width to bitmap.height)
                                frames.countDown()
                            }
                        }).also { it.start() }
                }
                assertTrue("No frames with previewEnabled=$previewEnabled; error=${failure.get()}", frames.await(20, TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError("Camera mode=$previewEnabled", it) }
                assertNotNull(size.get())
                sizes.add(size.get())
                var stopped: CompletableFuture<Unit>? = null
                scenario.onActivity { stopped = controller!!.stopAsync() }
                stopped!!.get(10, TimeUnit.SECONDS)
                controller = null
                Log.i("CameraPreviewRuntimeTest", "preview=$previewEnabled actualRotatedAnalysis=${size.get()}")
            }
            assertEquals("Negotiated analysis dimensions changed between modes", sizes[0], sizes[1])
            assertEquals(sizes[1], sizes[2])
        } finally {
            var stopped: CompletableFuture<Unit>? = null
            scenario?.onActivity { stopped = controller?.stopAsync() }
            stopped?.get(10, TimeUnit.SECONDS)
            scenario?.close()
            val editor = preferences.edit().clear()
            original.forEach { (key, value) -> editor.restore(key, value) }
            editor.commit()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun SharedPreferences.Editor.restore(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value as Set<String>)
        }
    }
}
