package com.samin.objectdetection.arcore

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** A future ToF adapter can return the same Z sample without depending on ARCore or YOLO. */
fun interface ForwardDepthSource<in T> {
    fun measure(input: T): DepthSample
}

data class DepthSample(
    val frameTimestampNs: Long,
    val trackingState: String,
    val depthWidth: Int = 0,
    val depthHeight: Int = 0,
    val centerX: Int? = null,
    val centerY: Int? = null,
    val rawDepthMeters: Double? = null,
    val validSampleCount: Int = 0,
    val depthTimestampNs: Long? = null,
    val depthStatus: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class DepthMeasurement(
    val sample: DepthSample,
    val filteredDepthMeters: Double?,
    val recentMeanMeters: Double?,
    val recentValidCount: Int,
    val receivedElapsedNs: Long,
    val lastValidElapsedNs: Long?,
    val groundTruthMeters: Double? = null
) {
    // Errors always use the unfiltered region median and the same camera-forward Z definition.
    val absoluteErrorMeters: Double? get() = groundTruthMeters?.takeIf { it.isFinite() && it > 0 && sample.depthStatus == "UPDATED" }?.let { truth ->
        sample.rawDepthMeters?.let { abs(it - truth) }
    }
    val relativeErrorPercent: Double? get() = absoluteErrorMeters?.let { it / groundTruthMeters!! * 100 }
    fun isFresh(nowNs: Long): Boolean = lastValidElapsedNs?.let { nowNs >= it && nowNs - it < STALE_NS } == true

    companion object { const val STALE_NS = 1_000_000_000L }
}

data class DepthPixel(val x: Int, val y: Int)
data class RegionDepth(val meters: Double?, val validCount: Int)

/** Reads D_16, not Android DEPTH16's confidence-packed 13-bit representation. */
object DepthPlaneSampler {
    fun pixel(u: Float, v: Float, width: Int, height: Int): DepthPixel? {
        if (!u.isFinite() || !v.isFinite() || u < 0 || v < 0 || u >= 1 || v >= 1 || width <= 0 || height <= 0) return null
        return DepthPixel((u * width).toInt(), (v * height).toInt())
    }

    fun sample(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
        center: DepthPixel, radius: Int = 2, minSamples: Int = 9): RegionDepth {
        if (width <= 0 || height <= 0 || pixelStride < 2 || rowStride < (width - 1L) * pixelStride + 2 ||
            center.x !in 0 until width || center.y !in 0 until height || radius !in 0..8 || minSamples <= 0) return RegionDepth(null, 0)
        val bytes = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val origin = bytes.position().toLong()
        val values = ArrayList<Double>((radius * 2 + 1) * (radius * 2 + 1))
        for (y in maxOf(0, center.y - radius)..minOf(height - 1, center.y + radius)) {
            for (x in maxOf(0, center.x - radius)..minOf(width - 1, center.x + radius)) {
                val offset = origin + y.toLong() * rowStride + x.toLong() * pixelStride
                if (offset < origin || offset + 2 > bytes.limit()) continue
                val mm = bytes.getShort(offset.toInt()).toInt() and 0xffff
                if (mm > 0) values.add(mm.toDouble())
            }
        }
        if (values.size < minSamples) return RegionDepth(null, values.size)
        val median = median(values)
        val mad = median(values.map { abs(it - median) })
        val inliers = values.filter { abs(it - median) <= maxOf(50.0, 3.0 * mad) }
        return RegionDepth(if (inliers.size >= minSamples) median(inliers) / 1000.0 else null, inliers.size)
    }

    internal fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }
}

/** GL-thread owned. Duplicate depth timestamps do not count as new samples or renew freshness. */
class DepthMeasurementFilter {
    private data class Timed(val meters: Double, val receivedNs: Long)
    private val history = ArrayDeque<Timed>()
    private var latestDepthTimestamp = Long.MIN_VALUE
    private var lastValidNs: Long? = null

    fun reset() { history.clear(); latestDepthTimestamp = Long.MIN_VALUE; lastValidNs = null }

    fun update(input: DepthSample, nowNs: Long, stabilize: Boolean, groundTruth: Double?): DepthMeasurement {
        if (input.trackingState != "TRACKING") reset()
        while (history.isNotEmpty() && nowNs - history.first().receivedNs >= 3_000_000_000L) history.removeFirst()
        val raw = input.rawDepthMeters?.takeIf { it.isFinite() && it > 0 }
        val timestamp = input.depthTimestampNs
        val duplicate = timestamp != null && timestamp <= latestDepthTimestamp
        // Depth from earlier camera frames may be returned by ARCore. Reject a stale image even
        // when it has just been acquired, using the shared ARCore frame/depth timestamp domain.
        val staleImage = timestamp != null && (timestamp <= 0 || input.frameTimestampNs < timestamp ||
            input.frameTimestampNs - timestamp >= DepthMeasurement.STALE_NS)
        val accepted = input.trackingState == "TRACKING" && input.depthStatus == "UPDATED" && raw != null && timestamp != null && !duplicate && !staleImage
        var sample = input.copy(rawDepthMeters = raw)
        if (staleImage) sample = sample.copy(depthStatus = "STALE_DEPTH")
        else if (duplicate) sample = sample.copy(depthStatus = "DUPLICATE_DEPTH")
        if (accepted) {
            if (lastValidNs?.let { nowNs - it >= DepthMeasurement.STALE_NS } == true) history.clear()
            latestDepthTimestamp = timestamp!!
            val observationNs = nowNs - (input.frameTimestampNs - timestamp)
            lastValidNs = observationNs
            history.addLast(Timed(raw!!, observationNs))
            while (history.size > 30) history.removeFirst()
        }
        val fresh = lastValidNs?.let { nowNs >= it && nowNs - it < DepthMeasurement.STALE_NS } == true
        val filtered = if (!fresh || history.isEmpty()) null else if (stabilize)
            DepthPlaneSampler.median(history.takeLast(5).map { it.meters }) else history.last().meters
        return DepthMeasurement(sample, filtered, if (fresh && history.isNotEmpty()) history.map { it.meters }.average() else null,
            if (fresh) history.size else 0, nowNs, lastValidNs, groundTruth?.takeIf { it.isFinite() && it > 0 })
    }
}
