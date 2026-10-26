package com.samin.objectdetection.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import com.samin.objectdetection.settings.SettingsActivity
import org.junit.Assert.*
import org.junit.Test

class MainScreenLoggingLayoutTest {
    @Test fun smallPortraitFooterKeepsControlsInsideWidthAndWarningAboveButtonsWithPreviewOnOrOff() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for (preview in listOf(true, false)) {
                    val screen = MainScreenView(activity, OverlayDebugMode.SIMPLE, "TEST", "test.tflite", true, false,
                        {}, {}, {}, {}, {}, cameraPreviewEnabled = preview)
                    val density = activity.resources.displayMetrics.density
                    val width = (320 * density).toInt(); val height = (480 * density).toInt()
                    screen.warningMessageTextView.text = "경고 메시지"
                    screen.warningMessageTextView.visibility = View.VISIBLE
                    screen.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    screen.root.layout(0, 0, width, height)
                    fun bounds(view: View): Rect {
                        val result = Rect(0, 0, view.width, view.height)
                        (screen.root as ViewGroup).offsetDescendantRectToMyCoords(view, result)
                        return result
                    }
                    val buttons = listOf(screen.recordingButton, screen.performanceLogButton, screen.mlKitLogButton, screen.mlKitShareButton)
                    buttons.forEach {
                        val box = bounds(it)
                        assertTrue("$box outside width=$width", box.left >= 0 && box.right <= width)
                        assertTrue("$box outside height=$height", box.top >= 0 && box.bottom <= height)
                        assertTrue(it.width > 0 && it.height > 0)
                        assertTrue(bounds(screen.warningMessageTextView).bottom <= box.top)
                    }
                    assertFalse(Rect.intersects(bounds(screen.mlKitLogButton), bounds(screen.mlKitShareButton)))
                    assertEquals(if (preview) View.VISIBLE else View.GONE, screen.previewView.visibility)
                    screen.overlayView.configure(true, true, false, false, false, 1500, showDistance = true)
                    assertEquals(if (preview) View.VISIBLE else View.GONE, screen.overlayView.visibility)
                }
            }
        }
    }
}
