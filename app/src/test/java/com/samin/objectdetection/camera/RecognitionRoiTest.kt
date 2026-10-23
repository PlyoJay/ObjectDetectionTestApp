package com.samin.objectdetection.camera

import com.samin.objectdetection.detector.YoloResizeTransform
import org.junit.Assert.*
import org.junit.Test

class RecognitionRoiTest {
    @Test fun offPreservesFullFrameAndLegacyCenterRoi() {
        val off = RecognitionRoi.PRESET.copy(enabled = false)
        assertEquals(FrameRoi(0, 0, 1280, 720), FrameRoi.calculate(1280, 720, off, false))
        assertEquals(FrameRoi(280, 0, 1000, 720), FrameRoi.calculate(1280, 720, off, true))
    }

    @Test fun edgeCropReplacesCenterRatherThanCroppingTwice() {
        val expected = FrameRoi(64, 72, 1216, 720)
        assertEquals(expected, FrameRoi.calculate(1280, 720, RecognitionRoi.PRESET, false))
        assertEquals(expected, FrameRoi.calculate(1280, 720, RecognitionRoi.PRESET, true))
        assertEquals(FrameRoi(36, 128, 684, 1280), FrameRoi.calculate(720, 1280, RecognitionRoi.PRESET, false))
    }

    @Test fun validationRejectsNonFiniteNegativeAndEmptyAreas() {
        for (value in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(RecognitionRoi(top = value).validationErrors().isNotEmpty())
            assertTrue(RecognitionRoi(left = value).validationErrors().isNotEmpty())
        }
        assertTrue(RecognitionRoi(top = .5f, bottom = .5f).validationErrors().isNotEmpty())
        assertTrue(RecognitionRoi(left = .8f, right = .3f).validationErrors().isNotEmpty())
        assertTrue(RecognitionRoi(top = .999f).validationErrors().isEmpty())
        assertEquals(1, FrameRoi.calculate(1, 1, RecognitionRoi(true, top = .999f), false).height)
    }

    @Test fun portrait1080x1440MlKitCoordinatesRestoreCrop20Top10Sides() {
        val roi = FrameRoi.calculate(1080, 1440,
            RecognitionRoi(true, top = .20f, left = .10f, right = .10f), false)
        assertEquals(FrameRoi(108, 288, 972, 1440), roi)
        assertEquals(864, roi.width)
        assertEquals(1152, roi.height)
        // ML Kit sees this portrait ROI stretched to 640x360, then returns input-pixel boxes.
        assertEquals(108f, roi.sourceX(0f, 640), .001f)
        assertEquals(288f, roi.sourceY(0f, 360), .001f)
        assertEquals(972f, roi.sourceX(640f, 640), .001f)
        assertEquals(1440f, roi.sourceY(360f, 360), .001f)
        assertEquals(540f, roi.sourceX(320f, 640), .001f)
        assertEquals(864f, roi.sourceY(180f, 360), .001f)
        assertEquals(194.4f, roi.sourceX(64f, 640), .001f)
        assertEquals(403.2f, roi.sourceY(36f, 360), .001f)
        assertEquals(885.6f, roi.sourceX(576f, 640), .001f)
        assertEquals(1324.8f, roi.sourceY(324f, 360), .001f)
    }

    @Test fun letterboxAndMlKitMapToSameUprightFullFrameForAllQuarterTurns() {
        // CameraX applies rotation first. 90/270 swap dimensions; edge ratios stay on upright axes.
        for (modelSize in listOf(640, 512)) for (rotation in listOf(0, 90, 180, 270)) {
            val width = if (rotation % 180 == 0) 1281 else 721
            val height = if (rotation % 180 == 0) 721 else 1281
            val roi = FrameRoi.calculate(width, height, RecognitionRoi(true, .13f, .07f, .11f, .03f), false)
            val transform = YoloResizeTransform.calculate(YoloResizeMode.LETTERBOX, roi.width, roi.height, modelSize, modelSize)
            assertEquals(modelSize, transform.scaledWidth + transform.padLeft + transform.padRight)
            assertEquals(modelSize, transform.scaledHeight + transform.padTop + transform.padBottom)
            for (fraction in listOf(0f, .23f, .77f, 1f)) {
                val normalizedX = (roi.width * fraction * transform.scaleX + transform.padLeft) / modelSize
                val normalizedY = (roi.height * fraction * transform.scaleY + transform.padTop) / modelSize
                val yoloX = roi.sourceX(transform.sourceX(normalizedX))
                val yoloY = roi.sourceY(transform.sourceY(normalizedY))
                assertEquals(roi.left + roi.width * fraction, yoloX, .001f)
                assertEquals(roi.top + roi.height * fraction, yoloY, .001f)
                assertEquals(yoloX, roi.sourceX(fraction * 640, 640), .001f)
                assertEquals(yoloY, roi.sourceY(fraction * 360, 360), .001f)
            }
        }
    }
}
