package com.samin.objectdetection.arcore.yolo

/** Exact per-depth-pixel center conversions frozen while Frame is current; no native references. */
class DepthPixelCoordinates(val width: Int, val height: Int, val frameTimestampNs: Long,
    cpuPixels: FloatArray, viewNormalized: FloatArray, val audit: CoordinateAudit? = null, val cpuValidBounds: ImageBox? = null) {
    private val cpu = cpuPixels.copyOf()
    private val view = viewNormalized.copyOf()
    init { require(width > 0 && height > 0 && width.toLong() * height * 2 == cpu.size.toLong() && cpu.size == view.size) }
    fun raw(x: Int, y: Int) = ImagePoint(cpu[(y * width + x) * 2], cpu[(y * width + x) * 2 + 1])
    fun view(x: Int, y: Int) = ImagePoint(view[(y * width + x) * 2], view[(y * width + x) * 2 + 1])
}

data class CoordinateAudit(val maxDepthPixelError: Float, val meanDepthPixelError: Float,
    val maxViewPixelError: Float, val maxRoundTripPixelError: Float, val comparedPoints: Int = 0)

/** Pure audit used with batched official API outputs (and synthetic transforms in unit tests). */
object CoordinateMappingAudit {
    fun compare(mapping: ArImageMapping, cpu: FloatArray, directDepth: FloatArray, directView: FloatArray,
        expectedDepth: FloatArray, depthWidth: Int, depthHeight: Int, viewWidth: Int, viewHeight: Int,
        cpuBounds: ImageBox = ImageBox(0f, 0f, mapping.width.toFloat(), mapping.height.toFloat())): CoordinateAudit {
        require(cpu.size == directDepth.size && cpu.size == directView.size && cpu.size == expectedDepth.size && cpu.size % 2 == 0)
        var maxDepth = 0f; var sum = 0f; var maxView = 0f; var roundTrip = 0f; var count = 0
        for (i in cpu.indices step 2) {
            val p = ImagePoint(cpu[i], cpu[i + 1]); val d = mapping.depth(p); val v = mapping.view(p)
            if (p.x < cpuBounds.left || p.x >= cpuBounds.right || p.y < cpuBounds.top || p.y >= cpuBounds.bottom ||
                directView[i] !in 0f..1f || directView[i + 1] !in 0f..1f) continue
            count++
            fun length(x: Float, y: Float) = kotlin.math.sqrt(x * x + y * y).takeIf { it.isFinite() } ?: Float.POSITIVE_INFINITY
            val error = length((d.x - directDepth[i]) * depthWidth, (d.y - directDepth[i + 1]) * depthHeight)
            sum += error; maxDepth = maxOf(maxDepth, error)
            maxView = maxOf(maxView, length((v.x - directView[i]) * viewWidth, (v.y - directView[i + 1]) * viewHeight))
            roundTrip = maxOf(roundTrip, length((directDepth[i] - expectedDepth[i]) * depthWidth, (directDepth[i + 1] - expectedDepth[i + 1]) * depthHeight))
        }
        if (count == 0) return CoordinateAudit(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        return CoordinateAudit(maxDepth, sum / count, maxView, roundTrip, count)
    }
}
