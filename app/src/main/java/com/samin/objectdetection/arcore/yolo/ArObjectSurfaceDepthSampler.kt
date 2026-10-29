package com.samin.objectdetection.arcore.yolo

import com.samin.objectdetection.arcore.DepthPlaneSampler
import com.samin.objectdetection.distance.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Quality gates, never distance correction factors. */
data class ObjectDepthSamplingConfig(
    val xFractions: List<Float> = listOf(.3f, .5f, .7f), val yFractions: List<Float> = listOf(.25f, .5f, .75f),
    val edgeInset: Float = .12f, val minSamples: Int = 12, val minRegions: Int = 3,
    val minRawConfidence: Int = 128, val minDominantFraction: Float = .65f, val maxRelativeSpread: Float = .3f,
    val maxInteriorSamples: Int = 768, val backgroundMargin: Float = .18f, val maxBackgroundAffinity: Float = .35f
) {
    init {
        require(minSamples >= 3 && minRegions >= 2 && minRawConfidence in 1..255 && maxInteriorSamples >= minSamples)
        require(backgroundMargin > 0 && backgroundMargin <= .5f && maxBackgroundAffinity in 0f..1f)
        require(edgeInset in 0f..0.25f && minDominantFraction in .5f..1f && maxRelativeSpread > 0)
        require(xFractions.isNotEmpty() && yFractions.isNotEmpty() &&
            (xFractions + yFractions).all { it > edgeInset && it < 1 - edgeInset })
    }
}

/** Interior distribution versus exterior background; no single nearest-pixel selection. */
class ArObjectSurfaceDepthSampler(private val config: ObjectDepthSamplingConfig = ObjectDepthSamplingConfig()) {
    private data class Pixel(val mm: Int, val core: Boolean, val region: Int)
    private data class Result(val depth: ArObjectDepth, val candidate: DepthCandidateSummary, val points: List<DepthSamplePoint>)
    fun sample(box: ImageBox, mapping: ArImageMapping, full: ArDepthPlane?, raw: ArDepthPlane?,
        mode: DepthInputMode, frameTimestampNs: Long, tracking: String, unavailable: String = "DEPTH_NOT_READY", diagnostics: Boolean = false,
        rawUnavailable: String = "DEPTH_NOT_READY"): ArObjectDepth {
        if (!mapping.valid() || !box.valid(mapping.width, mapping.height)) return ArObjectDepth(status = "INVALID_BBOX_OR_MAPPING")
        if (tracking != "TRACKING") return ArObjectDepth(status = "TRACKING_PAUSED")
        val f = fromPlane(box, mapping, full, frameTimestampNs, diagnostics, unavailable)
        val r = if (mode == DepthInputMode.RAW_WITH_FULL_FALLBACK || diagnostics) fromPlane(box, mapping, raw, frameTimestampNs, diagnostics, rawUnavailable) else null
        var chosen = f; var reason = "FULL_REQUESTED"
        if (mode == DepthInputMode.RAW_WITH_FULL_FALLBACK) {
            if (r?.depth?.meters != null) {
                chosen = r; reason = "RAW_VALID"
                val a = r.depth.meters!!; val b = f.depth.meters
                if (b != null && abs(a - b) > maxOf(.15f, minOf(a, b) * .15f)) {
                    chosen = r.copy(depth = r.depth.copy(meters = null, status = "DEPTH_SOURCE_DISAGREEMENT")); reason = "FULL_RAW_DISAGREE"
                }
            } else if (r?.depth?.status in setOf("AMBIGUOUS_CLUSTERS", "INCONSISTENT_SURFACE", "NO_OBJECT_BACKGROUND_SEPARATION")) {
                chosen = requireNotNull(r); reason = "RAW_AMBIGUOUS_NO_FALLBACK"
            } else {
                reason = "RAW_FALLBACK:${r?.depth?.status ?: "DEPTH_NOT_READY"}"
                chosen = f.copy(depth = f.depth.copy(status = "${f.depth.status};$reason"))
            }
        }
        val audit = (if (chosen.depth.method == DepthMethod.RAW) raw else full)?.coordinates?.audit
        return chosen.depth.copy(analysis = DepthAnalysis(f.candidate, r?.candidate, reason, if (diagnostics) chosen.points else emptyList(),
            audit?.maxDepthPixelError, audit?.meanDepthPixelError, audit?.maxRoundTripPixelError, audit?.maxViewPixelError))
    }
    private fun fromPlane(box: ImageBox, mapping: ArImageMapping, plane: ArDepthPlane?, frameNs: Long,
        debug: Boolean, unavailable: String = "DEPTH_NOT_READY"): Result {
        fun failure(status: String, count: Int = 0, confidence: List<Int> = emptyList(), low: Int = 0,
            clusters: List<DepthClusterSummary> = emptyList(), points: List<DepthSamplePoint> = emptyList()): Result {
            val d = ArObjectDepth(sampleCount = count, status = status, method = plane?.method, timestampNs = plane?.timestampNs)
            return Result(d, DepthCandidateSummary(plane?.method, null, count, status, plane?.timestampNs,
                confidence.minOrNull(), confidence.takeIf { it.isNotEmpty() }?.average()?.toFloat(), confidence.maxOrNull(), low, clusters,
                if (confidence.isEmpty()) emptyList() else (0..3).map { bucket -> confidence.count { it / 64 == bucket } }), points)
        }
        if (plane == null) return failure(unavailable)
        if (plane.timestampNs <= 0 || plane.timestampNs > frameNs || frameNs - plane.timestampNs >= ArObjectDepthSampler.MAX_DEPTH_AGE_NS) return failure("STALE_DEPTH")
        if (plane.width <= 0 || plane.height <= 0 || plane.pixelStride < 2 || plane.rowStride < (plane.width - 1L) * plane.pixelStride + 2 || !plane.crop.valid(plane.width, plane.height)) return failure("INVALID_DEPTH_LAYOUT")
        val c = plane.confidence
        if (plane.method == DepthMethod.RAW && (c == null || c.width != plane.width || c.height != plane.height || c.timestampNs != plane.timestampNs ||
            c.values.pixelStride <= 0 || c.values.rowStride < (plane.width - 1L) * c.values.pixelStride + 1)) return failure("INVALID_RAW_CONFIDENCE")
        val coordinates = plane.coordinates
        if (coordinates != null && (coordinates.frameTimestampNs != frameNs || coordinates.width != plane.width || coordinates.height != plane.height)) return failure("INVALID_COORDINATE_FRAME")
        if (coordinates?.audit?.maxRoundTripPixelError?.let { !it.isFinite() || it > .5f } == true) return failure("INVALID_COORDINATE_ROUNDTRIP")
        val upright = mapping.uprightBox(box); val bw = upright.right - upright.left; val bh = upright.bottom - upright.top
        val uvA = mapping.depth(ImagePoint(box.left, box.top)); val uvB = mapping.depth(ImagePoint(box.right, box.bottom))
        val area = abs((uvB.x - uvA.x) * plane.width * (uvB.y - uvA.y) * plane.height)
        val step = ceil(sqrt(area / config.maxInteriorSamples)).toInt().coerceIn(1, 32)
        val bytes = ByteBuffer.wrap(plane.bytes).order(ByteOrder.LITTLE_ENDIAN)
        val inside = mutableListOf<Pixel>(); val outside = mutableListOf<Int>(); val confidences = mutableListOf<Int>()
        var lowConfidence = 0; val points = mutableListOf<DepthSamplePoint>()
        val debugStep = maxOf(1, (area * 1.8f / (step * step) / 180).toInt()); var examined = 0
        for (y in ceil(plane.crop.top).toInt() until plane.crop.bottom.toInt() step step) for (x in ceil(plane.crop.left).toInt() until plane.crop.right.toInt() step step) {
            val rawPoint = coordinates?.raw(x, y) ?: mapping.rawFromDepth(ImagePoint((x + .5f) / plane.width, (y + .5f) / plane.height)) ?: continue
            if (rawPoint.x !in 0f..mapping.width.toFloat() || rawPoint.y !in 0f..mapping.height.toFloat()) continue
            val bounds = coordinates?.cpuValidBounds
            if (bounds != null && (rawPoint.x < bounds.left || rawPoint.x >= bounds.right || rawPoint.y < bounds.top || rawPoint.y >= bounds.bottom)) continue
            val view = coordinates?.view(x, y) ?: mapping.view(rawPoint)
            if (view.x !in 0f..1f || view.y !in 0f..1f) continue
            val p = mapping.uprightPoint(rawPoint); val u = (p.x - upright.left) / bw; val v = (p.y - upright.top) / bh
            val interior = u in config.edgeInset..1 - config.edgeInset && v in config.edgeInset..1 - config.edgeInset
            val ring = u in -config.backgroundMargin..1 + config.backgroundMargin && v in -config.backgroundMargin..1 + config.backgroundMargin && (u < 0 || u > 1 || v < 0 || v > 1)
            if (!interior && !ring) continue
            val offset = y.toLong() * plane.rowStride + x.toLong() * plane.pixelStride
            if (offset < 0 || offset + 2 > bytes.limit()) continue
            val mm = bytes.getShort(offset.toInt()).toInt() and 0xffff
            val confidence = if (plane.method == DepthMethod.RAW) runCatching { c!!.values.value(x, y) }.getOrDefault(0) else 255
            if (interior && mm > 0 && plane.method == DepthMethod.RAW) confidences.add(confidence)
            if (interior && mm > 0 && confidence < config.minRawConfidence) lowConfidence++
            val valid = mm > 0 && confidence >= config.minRawConfidence
            val core = interior && u in config.xFractions.min()..config.xFractions.max() && v in config.yFractions.min()..config.yFractions.max()
            if (debug && examined++ % debugStep == 0 && points.size < 256)
                points.add(DepthSamplePoint(p.x, p.y, if (ring) "BACKGROUND" else if (core) "CORE" else "INTERIOR", valid,
                    mm.takeIf { it > 0 }?.div(1000f), view.x, view.y))
            if (!valid) continue
            if (ring) outside.add(mm) else {
                val column = config.xFractions.indices.minBy { abs(config.xFractions[it] - u) }
                val row = config.yFractions.indices.minBy { abs(config.yFractions[it] - v) }
                inside.add(Pixel(mm, core, row * config.xFractions.size + column))
            }
        }
        if (inside.size < config.minSamples) return failure("INSUFFICIENT_DEPTH", inside.size, confidences, lowConfidence, points = points)
        if (outside.size < config.minSamples) return failure("INSUFFICIENT_BACKGROUND", inside.size, confidences, lowConfidence, points = points)
        val groups = mutableListOf<MutableList<Pixel>>()
        for (p in inside.sortedBy { it.mm }) {
            val group = groups.lastOrNull()
            if (group == null || p.mm - group.first().mm > maxOf(150f, group.first().mm * .12f)) groups.add(mutableListOf(p)) else group.add(p)
        }
        data class Candidate(val pixels: List<Pixel>, val summary: DepthClusterSummary)
        val candidates = groups.map { group ->
            val median = DepthPlaneSampler.median(group.map { it.mm.toDouble() }); val mad = DepthPlaneSampler.median(group.map { abs(it.mm - median) })
            val inliers = group.filter { abs(it.mm - median) <= maxOf(50.0, 3 * mad) }
            val m = DepthPlaneSampler.median(inliers.map { it.mm.toDouble() }); val sorted = inliers.sortedBy { it.mm }
            val spread = (sorted[(sorted.size - 1) * 9 / 10].mm - sorted[(sorted.size - 1) / 10].mm) / 1000f
            val affinity = outside.count { abs(it - m) <= maxOf(150.0, m * .12) }.toFloat() / outside.size
            Candidate(inliers, DepthClusterSummary((m / 1000).toFloat(), inliers.size, inliers.count { it.core }, inliers.map { it.region }.distinct().size, affinity, spread))
        }
        val summaries = candidates.map { it.summary }; val coreCount = inside.count { it.core }
        val eligible = candidates.filter { it.summary.count >= config.minSamples && it.summary.regions >= config.minRegions &&
            it.summary.coreCount >= maxOf(6, config.minSamples / 2) && it.summary.backgroundFraction < config.maxBackgroundAffinity }
        val best = eligible.maxByOrNull { it.summary.coreCount } ?: return failure("NO_OBJECT_BACKGROUND_SEPARATION", inside.size, confidences, lowConfidence, summaries, points)
        if (eligible.any { it !== best && it.summary.coreCount >= maxOf(3f, coreCount * .15f) } || best.summary.coreCount < coreCount * config.minDominantFraction)
            return failure("AMBIGUOUS_CLUSTERS", inside.size, confidences, lowConfidence, summaries, points)
        if (best.summary.spreadMeters > best.summary.meters * config.maxRelativeSpread) return failure("INCONSISTENT_SURFACE", inside.size, confidences, lowConfidence, summaries, points)
        val meters = best.summary.meters.takeIf { it.isFinite() && it > 0 } ?: return failure("INVALID_DISTANCE")
        val outputPoints = points.map { p -> if (p.valid && p.role != "BACKGROUND" && p.meters != null && abs(p.meters - meters) <= maxOf(.05f, best.summary.spreadMeters)) p.copy(role = "SELECTED") else p }
        val depth = ArObjectDepth(meters, sampleCount = best.summary.count, status = "INTERIOR_CONSISTENT", method = plane.method, timestampNs = plane.timestampNs, supportRegions = best.summary.regions)
        return Result(depth, DepthCandidateSummary(plane.method, meters, best.summary.count, depth.status, plane.timestampNs,
            confidences.minOrNull(), confidences.takeIf { it.isNotEmpty() }?.average()?.toFloat(), confidences.maxOrNull(), lowConfidence, summaries,
            if (confidences.isEmpty()) emptyList() else (0..3).map { bucket -> confidences.count { it / 64 == bucket } }), outputPoints)
    }
}
