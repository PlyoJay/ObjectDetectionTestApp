package com.samin.objectdetection.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.samin.objectdetection.mlkit.MlKitInputBuffer
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class FrameBitmapBufferTest {
    @Test fun quarterTurnsAndCropsMatchPlatformPixelsIncludingAlphaDensityAndOddSizes() {
        FrameBitmapBuffer().use { rotation ->
            FrameBitmapBuffer().use { crop ->
                for ((width, height) in listOf(97 to 61, 1281 to 721, 640 to 360)) {
                    val source = pattern(width, height)
                    try {
                        for (alpha in listOf(false, true)) {
                            source.setHasAlpha(alpha)
                            for (degrees in listOf(0, 90, 180, 270)) {
                                val expected = source.rotate(degrees.toFloat())
                                val actual = rotation.rotate(source, degrees)
                                try {
                                    equalPixels(expected, actual)
                                    if (degrees == 0) assertSame(source, actual)
                                    val roi = Rect(3, 7, actual.width - 5, actual.height - 9)
                                    val referenceCrop = Bitmap.createBitmap(expected, roi.left, roi.top, roi.width(), roi.height())
                                    try { equalPixels(referenceCrop, crop.crop(actual, roi)) }
                                    finally { referenceCrop.recycle() }
                                    assertSame(actual, crop.crop(actual, Rect(0, 0, actual.width, actual.height)))
                                } finally { if (expected !== source) expected.recycle() }
                            }
                        }
                    } finally { source.recycle() }
                }
            }
        }
    }

    @Test fun reusesStorageAndClearsOldPixelsThenReleasesOnShapeChangeAndClose() {
        val source = pattern(97, 61)
        val other = pattern(99, 63)
        val buffer = FrameBitmapBuffer()
        try {
            val first = buffer.rotate(source, 90)
            source.eraseColor(Color.TRANSPARENT)
            source.density = 160
            val second = buffer.rotate(source, 270)
            assertSame(first, second)
            val reference = source.rotate(270f)
            try { equalPixels(reference, second) } finally { reference.recycle() }
            val third = buffer.rotate(other, 90)
            assertNotSame(first, third)
            assertTrue(first.isRecycled)
            assertFalse(source.isRecycled)
            assertFalse(other.isRecycled)
            buffer.close()
            buffer.close()
            assertTrue(third.isRecycled)
            assertThrows(IllegalStateException::class.java) { buffer.rotate(source, 90) }
        } finally { buffer.close(); source.recycle(); other.recycle() }
    }

    @Test fun invalidRequestAndNonArgbFallbackPreserveOwnershipAndRecovery() {
        val source = pattern(31, 21)
        val rgb = Bitmap.createBitmap(31, 21, Bitmap.Config.RGB_565).apply { eraseColor(Color.GREEN) }
        val buffer = FrameBitmapBuffer()
        try {
            assertThrows(IllegalArgumentException::class.java) { buffer.rotate(source, 45) }
            assertThrows(IllegalArgumentException::class.java) { buffer.crop(source, Rect(-1, 0, 3, 5)) }
            val first = buffer.rotate(source, 90)
            val fallback = buffer.rotate(rgb, 90)
            assertTrue(first.isRecycled)
            val reference = rgb.rotate(90f)
            try { equalPixels(reference, fallback) } finally { reference.recycle() }
            buffer.rotate(source, 90)
            assertTrue(fallback.isRecycled)
            assertFalse(rgb.isRecycled)
        } finally { buffer.close(); source.recycle(); rgb.recycle() }
    }

    @Test fun conversionTransfersZeroRotationButRecyclesRotatedAndFailedSources() {
        val buffer = FrameBitmapBuffer()
        try {
            val zero = pattern(31, 21)
            assertSame(zero, proxy(zero, 0).toBitmapSafe(buffer))
            assertFalse(zero.isRecycled)
            zero.recycle() // CameraController's one-shot callback ownership.
            val source = pattern(31, 21)
            val rotated = checkNotNull(proxy(source, 90).toBitmapSafe(buffer))
            assertTrue(source.isRecycled)
            assertTrue(buffer.owns(rotated))
            assertFalse(rotated.isRecycled)
            for (degrees in listOf(0, 90)) {
                val failed = pattern(31, 21)
                assertThrows(BitmapConversionException::class.java) {
                    proxy(failed, degrees).toBitmapSafe(buffer) { _, _ -> error("timing callback failure") }
                }
                assertTrue(failed.isRecycled)
            }
            buffer.close()
            assertTrue(rotated.isRecycled)
        } finally { buffer.close() }
    }

    @Test fun mlKitLeaseAndCaptureCopySurviveNextFrameAndRotationBufferClose() {
        val source = pattern(97, 61)
        val rotation = FrameBitmapBuffer()
        val mlKit = MlKitInputBuffer()
        try {
            val upright = rotation.rotate(source, 90)
            val capture = checkNotNull(upright.copy(Bitmap.Config.ARGB_8888, false))
            val captured = pixels(capture)
            val lease = mlKit.acquire(upright, FrameRoi(3, 7, upright.width - 5, upright.height - 9))
            try {
                val input = pixels(lease.bitmap)
                source.eraseColor(Color.RED)
                assertSame(upright, rotation.rotate(source, 90))
                rotation.close()
                assertArrayEquals(captured, pixels(capture))
                assertArrayEquals(input, pixels(lease.bitmap))
                mlKit.close()
                assertFalse(lease.bitmap.isRecycled)
                lease.close()
                assertTrue(lease.bitmap.isRecycled)
            } finally { lease.close(); capture.recycle() }
        } finally { mlKit.close(); rotation.close(); source.recycle() }
    }

    private fun proxy(bitmap: Bitmap, degrees: Int): ImageProxy {
        val info = Proxy.newProxyInstance(ImageInfo::class.java.classLoader, arrayOf(ImageInfo::class.java)) { _, method, _ ->
            when (method.name) { "getRotationDegrees" -> degrees; else -> error(method.name) }
        } as ImageInfo
        return Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
            when (method.name) {
                "toBitmap" -> bitmap
                "getImageInfo" -> info
                "getWidth" -> 31
                "getHeight" -> 21
                "getFormat" -> android.graphics.PixelFormat.RGBA_8888
                else -> error(method.name)
            }
        } as ImageProxy
    }

    private fun pattern(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        density = 320
        setPixels(IntArray(width * height) { i -> Color.argb((i * 19) % 256, (i * 37) % 256, (i * 71) % 256, (i * 43) % 256) },
            0, width, 0, 0, width, height)
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun equalPixels(expected: Bitmap, actual: Bitmap) {
        assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
        assertEquals(expected.config, actual.config); assertEquals(expected.density, actual.density)
        assertEquals(expected.hasAlpha(), actual.hasAlpha()); assertEquals(expected.colorSpace, actual.colorSpace)
        assertArrayEquals(pixels(expected), pixels(actual))
    }
}
