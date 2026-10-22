package com.samin.objectdetection.mlkit

import android.graphics.Bitmap
import android.graphics.Color
import com.samin.objectdetection.camera.FrameRoi
import com.samin.objectdetection.camera.RecognitionRoi
import com.samin.objectdetection.camera.rotate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MlKitInputBufferTest {
    @Test fun pixelsMatchLegacyCropAndFilteredResizeForEveryRotationAndRoi() {
        val buffer = MlKitInputBuffer()
        try {
            // Odd sizes exercise fractional scale factors; both upscaling and downscaling matter.
            for ((width, height) in listOf(97 to 61, 1281 to 721, 640 to 360)) {
                val raw = patternedBitmap(width, height)
                try {
                    for (rotation in listOf(0, 90, 180, 270)) {
                        val upright = raw.rotate(rotation.toFloat())
                        try {
                            for ((config, centerSquare) in listOf(
                                RecognitionRoi() to false,
                                RecognitionRoi() to true,
                                RecognitionRoi(true, top = .20f, left = .10f, right = .10f) to false,
                                RecognitionRoi(true, top = .13f, bottom = .07f, left = .11f, right = .17f) to false
                            )) {
                                val roi = FrameRoi.calculate(upright.width, upright.height, config, centerSquare)
                                val expected = legacyInput(upright, roi)
                                val lease = buffer.acquire(upright, roi)
                                try {
                                    assertEquals(640, lease.bitmap.width)
                                    assertEquals(360, lease.bitmap.height)
                                    assertEquals(Bitmap.Config.ARGB_8888, lease.bitmap.config)
                                    assertArrayEquals("size=${width}x$height rotation=$rotation roi=$roi",
                                        pixels(expected), pixels(lease.bitmap))
                                    assertEquals(roi.right.toFloat(), roi.sourceX(640f, 640), .001f)
                                    assertEquals(roi.bottom.toFloat(), roi.sourceY(360f, 360), .001f)
                                } finally {
                                    lease.close()
                                    expected.recycle()
                                }
                            }
                        } finally { if (upright !== raw) upright.recycle() }
                    }
                } finally { raw.recycle() }
            }
        } finally { buffer.close() }
    }

    @Test fun filteringDoesNotReadPixelsOutsideRoi() {
        val source = Bitmap.createBitmap(67, 43, Bitmap.Config.ARGB_8888)
        val buffer = MlKitInputBuffer()
        try {
            source.eraseColor(Color.MAGENTA)
            val roi = FrameRoi(9, 7, 58, 36)
            for (y in roi.top until roi.bottom) for (x in roi.left until roi.right) {
                source.setPixel(x, y, if ((x + y) % 2 == 0) Color.GREEN else Color.BLUE)
            }
            val expected = legacyInput(source, roi)
            val lease = buffer.acquire(source, roi)
            try { assertArrayEquals(pixels(expected), pixels(lease.bitmap)) }
            finally { lease.close(); expected.recycle() }
        } finally { buffer.close(); source.recycle() }
    }

    @Test fun exactSizeInputIsOwnedAndUnaffectedBySourceMutationOrRecycle() {
        val source = patternedBitmap(640, 360)
        val before = pixels(source)
        val buffer = MlKitInputBuffer()
        val lease = buffer.acquire(source, FrameRoi(0, 0, 640, 360))
        try {
            assertNotSame(source, lease.bitmap)
            source.eraseColor(Color.RED)
            source.recycle()
            assertArrayEquals(before, pixels(lease.bitmap))
            assertFalse(lease.bitmap.isRecycled)
        } finally { lease.close(); buffer.close(); if (!source.isRecycled) source.recycle() }
    }

    @Test fun reusesIdentityAndFullyOverwritesPreviousPixelsIncludingAlpha() {
        val source = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val buffer = MlKitInputBuffer()
        try {
            source.eraseColor(Color.RED)
            val first = buffer.acquire(source, FrameRoi(0, 0, 640, 360))
            val retained = first.bitmap
            first.close()
            source.eraseColor(Color.TRANSPARENT)
            val second = buffer.acquire(source, FrameRoi(0, 0, 640, 360))
            try {
                assertSame(retained, second.bitmap)
                assertArrayEquals(pixels(source), pixels(second.bitmap))
                // A repeated old lease release must not release a newer lease.
                first.close()
                assertThrows(IllegalStateException::class.java) {
                    buffer.acquire(source, FrameRoi(0, 0, 640, 360))
                }
            } finally { second.close() }
            assertFalse(retained.isRecycled)
            buffer.close()
            assertTrue(retained.isRecycled)
        } finally { buffer.close(); source.recycle() }
    }

    @Test fun concurrentAcquireCannotOverwriteLeasedPixelsAndCloseDefersRecycle() {
        val source = patternedBitmap(640, 360)
        val buffer = MlKitInputBuffer()
        val executor = Executors.newSingleThreadExecutor()
        val lease = buffer.acquire(source, FrameRoi(0, 0, 640, 360))
        try {
            val before = pixels(lease.bitmap)
            source.eraseColor(Color.RED)
            executor.submit {
                assertThrows(IllegalStateException::class.java) {
                    buffer.acquire(source, FrameRoi(0, 0, 640, 360))
                }
                buffer.close()
            }.get(5, TimeUnit.SECONDS)
            assertFalse(lease.bitmap.isRecycled)
            assertArrayEquals(before, pixels(lease.bitmap))
            lease.close()
            lease.close()
            assertTrue(lease.bitmap.isRecycled)
            assertThrows(IllegalStateException::class.java) {
                buffer.acquire(source, FrameRoi(0, 0, 640, 360))
            }
        } finally { executor.shutdownNow(); lease.close(); buffer.close(); source.recycle() }
    }

    @Test fun invalidRoiDoesNotStrandBufferAndIdleCloseIsIdempotent() {
        val source = patternedBitmap(97, 61)
        val buffer = MlKitInputBuffer()
        try {
            for (roi in listOf(FrameRoi(-1, 0, 50, 50), FrameRoi(0, 0, 98, 61),
                FrameRoi(1, 1, 1, 30), FrameRoi(0, 0, 20, 62))) {
                assertThrows(IllegalArgumentException::class.java) { buffer.acquire(source, roi) }
            }
            val lease = buffer.acquire(source, FrameRoi(0, 0, 97, 61))
            val bitmap = lease.bitmap
            lease.close()
            buffer.close()
            buffer.close()
            assertTrue(bitmap.isRecycled)
            assertThrows(IllegalStateException::class.java) {
                buffer.acquire(source, FrameRoi(0, 0, 97, 61))
            }
        } finally { buffer.close(); source.recycle() }
    }

    private fun patternedBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            density = 320
            val values = IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                Color.rgb((x * 37 + y * 17) % 256, (x * 13 + y * 43) % 256, (x * 71 + y * 7) % 256)
            }
            setPixels(values, 0, width, 0, 0, width, height)
        }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun legacyInput(source: Bitmap, roi: FrameRoi): Bitmap {
        val cropped = Bitmap.createBitmap(source, roi.left, roi.top, roi.width, roi.height)
        val scaled = Bitmap.createScaledBitmap(cropped, 640, 360, true)
        val owned = if (scaled === source) checkNotNull(scaled.copy(Bitmap.Config.ARGB_8888, false)) else scaled
        if (cropped !== source && cropped !== owned) cropped.recycle()
        return owned
    }
}
