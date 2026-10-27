package com.samin.objectdetection.arcore.yolo

import com.samin.objectdetection.arcore.DepthPlaneSampler
import com.samin.objectdetection.detector.DetectionResult
import java.nio.ByteBuffer
import kotlin.math.abs

data class ImagePoint(val x: Float, val y: Float)
data class ImageBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun valid(width: Int, height: Int): Boolean = listOf(left, top, right, bottom).all { it.isFinite() } &&
        left >= 0 && top >= 0 && right <= width && bottom <= height && right > left && bottom > top
    fun iou(other: ImageBox): Float {
        val intersection = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f) *
            (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
        val union = (right - left) * (bottom - top) + (other.right - other.left) * (other.bottom - other.top) - intersection
        return if (union > 0) intersection / union else 0f
    }
    companion object { fun of(d: DetectionResult) = ImageBox(d.left, d.top, d.right, d.bottom) }
}

/** Immutable corner transforms frozen on the GL thread using ARCore IMAGE_PIXELS conversions.
 * Corner order: top-left, top-right, bottom-left, bottom-right. No ARCore Frame escapes capture.
 */
class ArImageMapping(val width: Int, val height: Int, viewCorners: FloatArray, depthCorners: FloatArray) {
    private val view = viewCorners.copyOf()
    private val depth = depthCorners.copyOf()
    fun valid(): Boolean = width > 0 && height > 0 && view.size == 8 && depth.size == 8 &&
        view.all { it.isFinite() } && depth.all { it.isFinite() } &&
        abs((view[2] - view[0]) * (view[5] - view[1]) - (view[3] - view[1]) * (view[4] - view[0])) > 1e-6f
    val rotation: Int get() {
        val dx = view[2] - view[0]; val dy = view[3] - view[1]
        return if (abs(dx) > abs(dy)) { if (dx >= 0) 0 else 180 } else { if (dy >= 0) 90 else 270 }
    }
    fun view(point: ImagePoint): ImagePoint = map(view, point)
    fun depth(point: ImagePoint): ImagePoint = map(depth, point)
    fun bottomCenter(box: ImageBox): ImagePoint = when (rotation) {
        0 -> ImagePoint((box.left + box.right) / 2, box.bottom)
        90 -> ImagePoint(box.right, (box.top + box.bottom) / 2)
        180 -> ImagePoint((box.left + box.right) / 2, box.top)
        270 -> ImagePoint(box.left, (box.top + box.bottom) / 2)
        else -> error("Invalid rotation")
    }
    fun viewBox(box: ImageBox): ImageBox {
        val points = listOf(ImagePoint(box.left, box.top), ImagePoint(box.right, box.top),
            ImagePoint(box.left, box.bottom), ImagePoint(box.right, box.bottom)).map(::view)
        return ImageBox(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }
    private fun map(corners: FloatArray, point: ImagePoint): ImagePoint {
        // Stable lerps avoid a one-pixel floor error at exact pixel boundaries after interpolation.
        val u = point.x.toDouble() / width; val v = point.y.toDouble() / height
        fun axis(i: Int): Float {
            val top = corners[i] + (corners[i + 2].toDouble() - corners[i]) * u
            val bottom = corners[i + 4] + (corners[i + 6].toDouble() - corners[i + 4]) * u
            return (top + (bottom - top) * v).toFloat()
        }
        return ImagePoint(axis(0), axis(1))
    }
    fun rawBox(upright: ImageBox): ImageBox = when (rotation) {
        0 -> upright
        90 -> ImageBox(upright.top, height - upright.right, upright.bottom, height - upright.left)
        180 -> ImageBox(width - upright.right, height - upright.bottom, width - upright.left, height - upright.top)
        270 -> ImageBox(width - upright.bottom, upright.left, width - upright.top, upright.right)
        else -> error("Invalid rotation")
    }
}

data class ArDepthPlane(val bytes: ByteArray, val width: Int, val height: Int, val rowStride: Int,
    val pixelStride: Int, val timestampNs: Long, val crop: ImageBox = ImageBox(0f, 0f, width.toFloat(), height.toFloat()))

data class ArObjectDepth(val rawMeters: Float? = null, val meters: Float? = rawMeters,
    val sampleCount: Int = 0, val status: String, val samplePoint: ImagePoint? = null)

object ArObjectDepthSampler {
    const val MAX_DEPTH_AGE_NS = 1_000_000_000L
    fun sample(box: ImageBox, mapping: ArImageMapping, plane: ArDepthPlane?, frameTimestampNs: Long,
        tracking: String, unavailableStatus: String = "DEPTH_NOT_READY"): ArObjectDepth {
        if (!box.valid(mapping.width, mapping.height) || !mapping.valid()) return ArObjectDepth(status = "INVALID_BBOX_OR_MAPPING")
        if (tracking != "TRACKING") return ArObjectDepth(status = "TRACKING_PAUSED")
        if (plane == null) return ArObjectDepth(status = unavailableStatus)
        if (plane.timestampNs <= 0 || plane.timestampNs > frameTimestampNs || frameTimestampNs - plane.timestampNs >= MAX_DEPTH_AGE_NS) {
            return ArObjectDepth(status = "STALE_DEPTH")
        }
        val points = listOf(mapping.bottomCenter(box),
            ImagePoint((box.left + box.right) / 2, (box.top + box.bottom) / 2))
        var count = 0
        for ((index, point) in points.withIndex()) {
            val uv = mapping.depth(point)
            val pixel = DepthPlaneSampler.pixel(uv.x, uv.y, plane.width, plane.height) ?: continue
            // Do not sample beyond a non-full Image.cropRect.
            if (maxOf(0, pixel.x - 2) < plane.crop.left || maxOf(0, pixel.y - 2) < plane.crop.top ||
                minOf(plane.width, pixel.x + 3) > plane.crop.right || minOf(plane.height, pixel.y + 3) > plane.crop.bottom) continue
            val region = DepthPlaneSampler.sample(ByteBuffer.wrap(plane.bytes), plane.width, plane.height,
                plane.rowStride, plane.pixelStride, pixel)
            count = maxOf(count, region.validCount)
            val meters = region.meters?.toFloat()?.takeIf { it.isFinite() && it > 0 } ?: continue
            return ArObjectDepth(meters, sampleCount = region.validCount,
                status = if (index == 0) "BOTTOM_CENTER" else "CENTER_FALLBACK", samplePoint = ImagePoint(pixel.x.toFloat(), pixel.y.toFloat()))
        }
        return ArObjectDepth(sampleCount = count, status = "INSUFFICIENT_DEPTH")
    }
}

data class ArYoloObject(val detection: DetectionResult, val viewBox: ImageBox, val depth: ArObjectDepth)
data class ArYoloFrame(val frameTimestampNs: Long, val cameraTimestampNs: Long, val depthTimestampNs: Long?,
    val capturedElapsedNs: Long, val distanceExpiresNs: Long, val epoch: Long, val tracking: String,
    val imageWidth: Int, val imageHeight: Int, val depthWidth: Int, val depthHeight: Int,
    val objects: List<ArYoloObject>, val inferenceMs: Long) {
    fun isFresh(nowNs: Long, currentEpoch: Long, timeoutNs: Long = MAX_RESULT_AGE_NS): Boolean =
        epoch == currentEpoch && nowNs >= capturedElapsedNs && nowNs - capturedElapsedNs < timeoutNs

    fun hasFreshDepth(nowNs: Long): Boolean = tracking == "TRACKING" && depthTimestampNs != null &&
        depthTimestampNs > 0 && nowNs >= capturedElapsedNs && nowNs < distanceExpiresNs

    companion object { const val MAX_RESULT_AGE_NS = 1_000_000_000L }
}

/** A small same-label/IoU matcher confined to this experiment. Missing depth never borrows old Z. */
class ArObjectDepthSmoother {
    private data class Track(val label: String, val box: ImageBox, val history: List<Float>, val timestamp: Long, val seenNs: Long)
    private var tracks = emptyList<Track>()
    fun reset() { tracks = emptyList() }
    fun update(objects: List<ArYoloObject>, depthTimestamp: Long?, nowNs: Long): List<ArYoloObject> {
        val previous = tracks.filter { nowNs >= it.seenNs && nowNs - it.seenNs < ArObjectDepthSampler.MAX_DEPTH_AGE_NS }.toMutableList()
        val next = mutableListOf<Track>()
        val result = objects.map { obj ->
            val box = ImageBox.of(obj.detection)
            val match = previous.filter { it.label == obj.detection.label && it.box.iou(box) >= .25f }.maxByOrNull { it.box.iou(box) }
            if (match != null) previous.remove(match)
            val raw = obj.depth.rawMeters
            if (raw == null || depthTimestamp == null) return@map obj
            val history = if (match == null) listOf(raw) else if (depthTimestamp <= match.timestamp) match.history else (match.history + raw).takeLast(5)
            next.add(Track(obj.detection.label, box, history, depthTimestamp, nowNs))
            val sorted = history.sorted(); val middle = sorted.size / 2
            val median = if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
            obj.copy(depth = obj.depth.copy(meters = median))
        }
        tracks = next
        return result
    }
}
