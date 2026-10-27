package com.samin.objectdetection.arcore.yolo

data class YuvPlaneBytes(val bytes: ByteArray, val rowStride: Int, val pixelStride: Int) {
    fun value(x: Int, y: Int): Int {
        val offset = y.toLong() * rowStride + x.toLong() * pixelStride
        require(pixelStride > 0 && rowStride > 0 && offset >= 0 && offset < bytes.size) { "Invalid YUV plane layout" }
        return bytes[offset.toInt()].toInt() and 0xff
    }
}

/** CPU-owned arrays; Camera Image planes are copied and closed on the GL thread. */
data class ArYuvSnapshot(val width: Int, val height: Int, val y: YuvPlaneBytes, val u: YuvPlaneBytes, val v: YuvPlaneBytes) {
    fun toArgb(): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height <= 16_777_216) { "Invalid CPU camera image size" }
        val pixels = IntArray(width * height)
        for (row in 0 until height) for (column in 0 until width) {
            val luminance = (y.value(column, row) - 16).coerceAtLeast(0)
            val blue = u.value(column / 2, row / 2) - 128
            val red = v.value(column / 2, row / 2) - 128
            val r = ((298 * luminance + 409 * red + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * luminance - 100 * blue - 208 * red + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * luminance + 516 * blue + 128) shr 8).coerceIn(0, 255)
            pixels[row * width + column] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        return pixels
    }
}

/** Single in-flight frame, zero waiting frames. Newly arriving frames are dropped while busy. */
class ArYoloWorkGate(private val intervalNs: Long = 250_000_000L) {
    private var busy = false
    private var closed = false
    private var previousNs: Long? = null
    @Synchronized fun acquire(nowNs: Long): Boolean {
        if (closed || busy || previousNs?.let { nowNs - it < intervalNs } == true) return false
        busy = true; previousNs = nowNs
        return true
    }
    @Synchronized fun release() { busy = false }
    @Synchronized fun close() { closed = true }
}
