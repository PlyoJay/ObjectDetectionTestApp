package com.samin.objectdetection.detector

import com.samin.objectdetection.camera.YoloResizeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class YoloResizeTransformTest {
    @Test fun landscapeLetterboxGeometry() {
        val t = YoloResizeTransform.calculate(YoloResizeMode.LETTERBOX, 1280, 720, 640, 640)
        assertEquals(640, t.scaledWidth)
        assertEquals(360, t.scaledHeight)
        assertEquals(0, t.padLeft)
        assertEquals(0, t.padRight)
        assertEquals(140, t.padTop)
        assertEquals(140, t.padBottom)
        assertEquals(0.5f, t.scaleX, 0f)
        assertEquals(0.5f, t.scaleY, 0f)
    }

    @Test fun portraitLetterboxGeometry() {
        val t = YoloResizeTransform.calculate(YoloResizeMode.LETTERBOX, 720, 1280, 640, 640)
        assertEquals(360, t.scaledWidth)
        assertEquals(640, t.scaledHeight)
        assertEquals(140, t.padLeft)
        assertEquals(140, t.padRight)
        assertEquals(0, t.padTop)
        assertEquals(0, t.padBottom)
    }

    @Test fun letterboxBoxMapsBackToSourceAndClampsPadding() {
        val t = YoloResizeTransform.calculate(YoloResizeMode.LETTERBOX, 1280, 720, 640, 640)
        // Source [200,100,600,300] appears in model pixels as [100,190,300,290].
        assertEquals(200f, t.sourceX(100f / 640), 0.001f)
        assertEquals(100f, t.sourceY(190f / 640), 0.001f)
        assertEquals(600f, t.sourceX(300f / 640), 0.001f)
        assertEquals(300f, t.sourceY(290f / 640), 0.001f)
        assertEquals(0f, t.sourceY(0f), 0f)
        assertEquals(720f, t.sourceY(1f), 0f)
    }

    @Test fun stretchRegressionUsesOriginalNormalizedCoordinates() {
        val t = YoloResizeTransform.calculate(YoloResizeMode.STRETCH, 1280, 720, 640, 640)
        assertEquals(640, t.scaledWidth)
        assertEquals(640, t.scaledHeight)
        assertEquals(0, t.padTop)
        val normalizedLeft = ((320f - 160f / 2f) / 640f).coerceIn(0f, 1f)
        val normalizedTop = ((320f - 160f / 2f) / 640f).coerceIn(0f, 1f)
        assertEquals(normalizedLeft * 1280, t.sourceX(normalizedLeft), 0f)
        assertEquals(normalizedTop * 720, t.sourceY(normalizedTop), 0f)
        assertEquals(0.5f, t.scaleX, 0f)
        assertEquals(640f / 720f, t.scaleY, 0f)
    }
}
