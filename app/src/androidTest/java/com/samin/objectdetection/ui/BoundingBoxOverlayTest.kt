package com.samin.objectdetection.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.metrics.FrameTrace
import com.samin.objectdetection.settings.SettingsActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Canvas pixels and real View callbacks; no camera, model, warning output or settings writes. */
class BoundingBoxOverlayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val box = listOf(DetectionResult("box", 1f, 20f, 20f, 80f, 80f))

    private fun trace(id: Long, ageMs: Long = 0, reliable: Boolean = true): FrameTrace {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        return FrameTrace("overlay-test", id, 0, nowNs - ageMs * 1_000_000L, reliable,
            0, nowNs - ageMs * 1_000_000L, completedElapsedNs = nowNs)
    }

    private fun overlay(timeoutMs: Long = 1500) = BoundingBoxOverlay(instrumentation.targetContext).apply {
        setDebugMode(OverlayDebugMode.NONE)
        configure(true, true, true, false, false, timeoutMs)
        layout(0, 0, 200, 200)
    }

    private fun render(view: BoundingBoxOverlay): Bitmap =
        Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    private fun hasPixels(view: BoundingBoxOverlay): Boolean {
        val bitmap = render(view)
        return try {
            val pixels = IntArray(200 * 200)
            bitmap.getPixels(pixels, 0, 200, 0, 0, 200, 200)
            pixels.any { Color.alpha(it) > 0 }
        } finally { bitmap.recycle() }
    }

    @Test fun freshDrawsAndContinuouslyArrivingStaleResultsDoNotDraw() = instrumentation.runOnMainSync {
        val view = overlay()
        view.updateDetections(box, 200, 200, 1, 1, trace(1))
        assertTrue(hasPixels(view))
        for (id in 2L..5L) {
            view.updateDetections(box, 200, 200, 1, 1, trace(id, 1600))
            assertFalse(hasPixels(view))
        }
        view.clearDetections()
    }

    @Test fun delayedMainThreadCannotDrawExpiredFrameEvenBeforeTimerRuns() = instrumentation.runOnMainSync {
        val view = overlay(100)
        var drawn = true
        view.onFrameDrawn = { _, _, _, enabled -> drawn = enabled }
        view.updateDetections(box, 200, 200, 1, 1, trace(1))
        // Deliberately keep the UI thread busy across the deadline; no timer can run here.
        SystemClock.sleep(160)
        assertFalse(hasPixels(view))
        assertFalse(drawn)
        view.clearDetections()
    }

    @Test fun detectorDimensionsAndExpirationAreIndependent() = instrumentation.runOnMainSync {
        val view = overlay()
        view.updateDetections(box, 200, 200, 1, 1, trace(1))
        view.updateMlKitDetections(box, 100, 200, trace(1, reliable = false))
        val bitmap = render(view)
        assertEquals(Color.rgb(0, 191, 255), bitmap.getPixel(20, 50))
        assertEquals(Color.YELLOW, bitmap.getPixel(70, 50))
        bitmap.recycle()
        view.updateDetections(box, 200, 200, 1, 1, trace(2, 1600))
        val mlOnly = render(view)
        assertEquals(Color.TRANSPARENT, mlOnly.getPixel(20, 50))
        assertEquals(Color.YELLOW, mlOnly.getPixel(70, 50))
        mlOnly.recycle()
        view.clearDetections()
    }

    @Test fun mlKitYellowBoxesRequireBothVisibilityGatesAndFreshResultAfterReenable() = instrumentation.runOnMainSync {
        val view = overlay()
        try {
            view.configure(false, false, true, false, false, 1500)
            view.updateMlKitDetections(box, 200, 200, trace(1))
            assertFalse("Global overlay OFF must suppress ML Kit boxes", hasPixels(view))
            view.configure(true, false, false, false, false, 1500)
            view.updateMlKitDetections(box, 200, 200, trace(2))
            assertFalse("ML Kit box visibility OFF must suppress boxes", hasPixels(view))
            view.configure(true, false, true, false, false, 1500)
            assertFalse("Enabling boxes must not revive a suppressed payload", hasPixels(view))
            view.updateMlKitDetections(box, 200, 200, trace(3))
            val rendered = render(view)
            try { assertEquals(Color.YELLOW, rendered.getPixel(20, 50)) } finally { rendered.recycle() }
            view.setDrawingEnabled(false)
            view.setDrawingEnabled(true)
            assertFalse("Global OFF/ON must require another fresh SDK result", hasPixels(view))
            view.updateMlKitDetections(box, 200, 200, trace(4))
            assertTrue(hasPixels(view))
        } finally { view.clearDetections() }
    }

    @Test fun mlKitSuccessfulButStaleResultIsRejectedBeforeAnyYellowBoxDraw() = instrumentation.runOnMainSync {
        val view = overlay()
        val logs = mutableListOf<String>()
        view.diagnosticLog = logs::add
        try {
            view.configure(true, false, true, false, false, 1500)
            view.updateMlKitDetections(box, 200, 200, trace(1))
            assertTrue(hasPixels(view))
            view.updateMlKitDetections(box, 200, 200, trace(2, 1600))
            assertFalse("A completed SDK result can already be stale on arrival", hasPixels(view))
            assertTrue(logs.any { it.contains("source=ML_KIT") && it.contains("reason=EXPIRED_AT_UPDATE") })
            assertFalse(logs.any { it.contains("[OVERLAY_DRAW]") && it.contains("source=ML_KIT") && it.contains("frame=2 ") })
        } finally { view.diagnosticLog = null; view.clearDetections() }
    }

    @Test fun mlKitMultipleObjectsDrawAndNextSuccessfulEmptyResultClearsPreviousBoxes() = instrumentation.runOnMainSync {
        val view = overlay()
        try {
            view.configure(true, false, true, false, false, 1500)
            val multiple = box + DetectionResult("ML Kit", 1f, 120f, 120f, 180f, 180f)
            view.updateMlKitDetections(multiple, 200, 200, trace(1))
            val rendered = render(view)
            try {
                assertEquals(Color.YELLOW, rendered.getPixel(20, 50))
                assertEquals(Color.YELLOW, rendered.getPixel(120, 150))
            } finally { rendered.recycle() }
            view.updateMlKitDetections(emptyList(), 200, 200, trace(2))
            assertFalse("A fresh zero-object SDK result must clear previous ML Kit boxes", hasPixels(view))
        } finally { view.clearDetections() }
    }

    @Test fun mlKitPortraitRoiBoxUsesUprightFullFrameFitCenterCoordinates() = instrumentation.runOnMainSync {
        val view = overlay()
        try {
            view.configure(true, false, true, false, false, 1500)
            // 1080x1440 upright frame; top 20%, left/right 10% ROI restored from a 640x360 box.
            view.updateMlKitDetections(listOf(DetectionResult("ML Kit", 1f, 108f, 288f, 972f, 1440f)),
                1080, 1440, trace(1))
            val rendered = render(view)
            try {
                // FIT_CENTER => scale=200/1440, offsetX=25. ROI's vertical sides are x=40 and x=160.
                assertEquals(Color.YELLOW, rendered.getPixel(40, 80))
                assertEquals(Color.YELLOW, rendered.getPixel(160, 80))
                assertEquals(Color.TRANSPARENT, rendered.getPixel(25, 80))
            } finally { rendered.recycle() }
        } finally { view.clearDetections() }
    }

    @Test fun togglesAndTimeoutChangesNeverReviveOldPayload() = instrumentation.runOnMainSync {
        val view = overlay()
        view.updateDetections(box, 200, 200, 1, 1, trace(1))
        assertTrue(hasPixels(view))
        view.setDrawingEnabled(false)
        view.setDrawingEnabled(true)
        assertFalse(hasPixels(view))
        view.updateDetections(box, 200, 200, 1, 1, trace(2))
        assertTrue(hasPixels(view))
        view.updateDetections(box, 200, 200, 1, 1, trace(3, 500))
        view.configure(true, true, true, false, false, 100)
        assertFalse(hasPixels(view))
        view.configure(true, true, true, false, false, 1500)
        assertFalse(hasPixels(view))
        view.clearDetections()
    }

    @Test fun attachedTimerExpiresWithoutFurtherResultsAndRecreationClearsOldView() {
        val expired = CountDownLatch(1)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            lateinit var previous: BoundingBoxOverlay
            scenario.onActivity { activity ->
                previous = BoundingBoxOverlay(activity).apply {
                    setDebugMode(OverlayDebugMode.NONE)
                    configure(true, true, true, false, false, 100)
                    diagnosticLog = { if (it.contains("reason=EXPIRED")) expired.countDown() }
                }
                activity.setContentView(previous)
                previous.updateDetections(box, 200, 200, 1, 1, trace(1))
            }
            assertTrue("expiry callback did not run", expired.await(3, TimeUnit.SECONDS))
            scenario.onActivity {
                previous.configure(true, true, true, false, false, 1500)
                previous.updateDetections(box, 200, 200, 1, 1, trace(2))
            }
            scenario.recreate()
            scenario.onActivity {
                previous.layout(0, 0, 200, 200)
                assertFalse(hasPixels(previous))
                assertFalse(hasPixels(overlay()))
            }
        }
    }
}
