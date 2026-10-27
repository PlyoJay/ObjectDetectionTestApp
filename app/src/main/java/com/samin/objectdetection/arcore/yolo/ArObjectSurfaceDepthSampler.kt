package com.samin.objectdetection.arcore.yolo

import com.samin.objectdetection.arcore.DepthPlaneSampler
import com.samin.objectdetection.distance.DepthInputMode
import com.samin.objectdetection.distance.DepthMethod
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Quality gates, not distance correction. Fractions can be tuned using recorded device evidence. */
data class ObjectDepthSamplingConfig(
    val xFractions: List<Float> = listOf(.3f, .5f, .7f),
    val yFractions: List<Float> = listOf(.25f, .5f, .75f),
    val radius: Int = 2, val edgeInset: Float = .12f,
    val minSamples: Int = 12, val minRegions: Int = 3, val minRawConfidence: Int = 128,
    val minDominantFraction: Float = .65f, val maxRelativeSpread: Float = .3f
) {
    init {
        require(radius in 0..4 && minSamples >= 3 && minRegions >= 2 && minRawConfidence in 1..255)
        require(edgeInset in 0f..0.25f && minDominantFraction in .5f..1f && maxRelativeSpread > 0)
        require(xFractions.isNotEmpty() && yFractions.isNotEmpty() &&
            (xFractions + yFractions).all { it > edgeInset && it < 1 - edgeInset })
    }
}

/** Multiple interior patches, unique pixels, spatial support and conservative cluster rejection. */
class ArObjectSurfaceDepthSampler(private val config: ObjectDepthSamplingConfig = ObjectDepthSamplingConfig()) {
    private data class Pixel(val mm: Int, val x: Int, val y: Int, val regions: MutableSet<Int>)

    fun sample(box: ImageBox, mapping: ArImageMapping, full: ArDepthPlane?, raw: ArDepthPlane?,
        mode: DepthInputMode, frameTimestampNs: Long, tracking: String, unavailable: String = "DEPTH_NOT_READY"): ArObjectDepth {
        if (!mapping.valid() || !box.valid(mapping.width, mapping.height)) return ArObjectDepth(status = "INVALID_BBOX_OR_MAPPING")
        if (tracking != "TRACKING") return ArObjectDepth(status = "TRACKING_PAUSED")
        if (mode == DepthInputMode.RAW_WITH_FULL_FALLBACK) {
            val primary = fromPlane(box, mapping, raw, frameTimestampNs)
            if (primary.meters != null) return primary
            // Ambiguous distributions are not repaired by interpolated FULL depth.
            if (primary.status in setOf("AMBIGUOUS_CLUSTERS", "INCONSISTENT_SURFACE")) return primary
            val fallback = fromPlane(box, mapping, full, frameTimestampNs)
            return fallback.copy(status = "${fallback.status};RAW_FALLBACK:${primary.status}")
        }
        return if (full == null) ArObjectDepth(status = unavailable) else fromPlane(box, mapping, full, frameTimestampNs)
    }

    private fun fromPlane(box: ImageBox, mapping: ArImageMapping, plane: ArDepthPlane?, frameNs: Long): ArObjectDepth {
        if (plane == null) return ArObjectDepth(status = "DEPTH_NOT_READY")
        fun invalid(status: String, count: Int = 0) = ArObjectDepth(sampleCount = count, status = status,
            method = plane.method, timestampNs = plane.timestampNs)
        if (plane.timestampNs <= 0 || plane.timestampNs > frameNs || frameNs - plane.timestampNs >= ArObjectDepthSampler.MAX_DEPTH_AGE_NS)
            return invalid("STALE_DEPTH")
        if (plane.width <= 0 || plane.height <= 0 || plane.pixelStride < 2 ||
            plane.rowStride < (plane.width - 1L) * plane.pixelStride + 2 || !plane.crop.valid(plane.width, plane.height)) return invalid("INVALID_DEPTH_LAYOUT")
        val confidence = plane.confidence
        if (plane.method == DepthMethod.RAW && (confidence == null || confidence.width != plane.width ||
            confidence.height != plane.height || confidence.timestampNs != plane.timestampNs ||
            confidence.values.pixelStride <= 0 || confidence.values.rowStride < (plane.width - 1L) * confidence.values.pixelStride + 1)) return invalid("INVALID_RAW_CONFIDENCE")
        val upright = mapping.uprightBox(box)
        fun point(x: Float, y: Float) = mapping.depth(mapping.rawPoint(ImagePoint(
            upright.left + (upright.right - upright.left) * x, upright.top + (upright.bottom - upright.top) * y)))
        val corners = listOf(point(config.edgeInset, config.edgeInset), point(1 - config.edgeInset, config.edgeInset),
            point(config.edgeInset, 1 - config.edgeInset), point(1 - config.edgeInset, 1 - config.edgeInset))
        if (corners.any { !it.x.isFinite() || !it.y.isFinite() }) return invalid("INVALID_MAPPING")
        val minX = maxOf(plane.crop.left, corners.minOf { it.x } * plane.width)
        val maxX = minOf(plane.crop.right, corners.maxOf { it.x } * plane.width)
        val minY = maxOf(plane.crop.top, corners.minOf { it.y } * plane.height)
        val maxY = minOf(plane.crop.bottom, corners.maxOf { it.y } * plane.height)
        val bytes = ByteBuffer.wrap(plane.bytes).order(ByteOrder.LITTLE_ENDIAN)
        val pixels = linkedMapOf<Long, Pixel>()
        val centers = mutableSetOf<Long>()
        var regionId = 0
        for (v in config.yFractions) for (u in config.xFractions) {
            val id = regionId++
            val visible = mapping.view(mapping.rawPoint(ImagePoint(upright.left + (upright.right - upright.left) * u,
                upright.top + (upright.bottom - upright.top) * v)))
            if (visible.x !in 0f..1f || visible.y !in 0f..1f) continue
            val uv = point(u, v)
            val center = DepthPlaneSampler.pixel(uv.x, uv.y, plane.width, plane.height) ?: continue
            if (!centers.add(center.y.toLong() * plane.width + center.x)) continue
            val patch = mutableListOf<Pixel>()
            for (y in maxOf(0, center.y - config.radius)..minOf(plane.height - 1, center.y + config.radius)) {
                for (x in maxOf(0, center.x - config.radius)..minOf(plane.width - 1, center.x + config.radius)) {
                    if (x + .5f < minX || x + .5f >= maxX || y + .5f < minY || y + .5f >= maxY) continue
                    val offset = y.toLong() * plane.rowStride + x.toLong() * plane.pixelStride
                    if (offset < 0 || offset + 2 > bytes.limit()) continue
                    val mm = bytes.getShort(offset.toInt()).toInt() and 0xffff
                    if (mm == 0) continue
                    if (plane.method == DepthMethod.RAW && runCatching { confidence!!.values.value(x, y) }.getOrDefault(0) < config.minRawConfidence) continue
                    patch.add(Pixel(mm, x, y, mutableSetOf(id)))
                }
            }
            if (patch.isEmpty()) continue
            val median = DepthPlaneSampler.median(patch.map { it.mm.toDouble() })
            val mad = DepthPlaneSampler.median(patch.map { abs(it.mm - median) })
            for (pixel in patch.filter { abs(it.mm - median) <= maxOf(50.0, 3 * mad) }) {
                val key = pixel.y.toLong() * plane.width + pixel.x
                pixels[key]?.regions?.add(id) ?: run { pixels[key] = pixel }
            }
        }
        val values = pixels.values.sortedBy { it.mm }
        if (values.size < config.minSamples) return invalid("INSUFFICIENT_DEPTH", values.size)
        val clusters = mutableListOf<MutableList<Pixel>>()
        for (pixel in values) {
            val last = clusters.lastOrNull()
            if (last == null || pixel.mm - last.last().mm > maxOf(150.0, (pixel.mm + last.last().mm) * .06)) clusters.add(mutableListOf(pixel))
            else last.add(pixel)
        }
        val dominant = clusters.maxBy { it.size }
        fun regions(cluster: List<Pixel>) = cluster.flatMap { it.regions }.toSet().size
        val competitor = clusters.any { it !== dominant && it.size >= maxOf(4, (values.size * .15f).toInt()) && regions(it) >= 2 }
        if (competitor || dominant.size < values.size * config.minDominantFraction) return invalid("AMBIGUOUS_CLUSTERS", values.size)
        val support = regions(dominant)
        if (dominant.size < config.minSamples || support < config.minRegions) return invalid("INSUFFICIENT_SPATIAL_SUPPORT", dominant.size)
        val median = DepthPlaneSampler.median(dominant.map { it.mm.toDouble() })
        val spread = dominant[(dominant.size - 1) * 9 / 10].mm - dominant[(dominant.size - 1) / 10].mm
        if (spread / median > config.maxRelativeSpread) return invalid("INCONSISTENT_SURFACE", dominant.size)
        val meters = (median / 1000).toFloat().takeIf { it.isFinite() && it > 0 } ?: return invalid("INVALID_DISTANCE")
        return ArObjectDepth(meters, sampleCount = dominant.size, status = "INTERIOR_CONSISTENT",
            samplePoint = ImagePoint(dominant.map { it.x }.average().toFloat(), dominant.map { it.y }.average().toFloat()),
            method = plane.method, timestampNs = plane.timestampNs, supportRegions = support)
    }
}
