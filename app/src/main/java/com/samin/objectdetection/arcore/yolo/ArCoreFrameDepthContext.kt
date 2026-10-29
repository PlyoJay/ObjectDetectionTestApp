package com.samin.objectdetection.arcore.yolo

import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.distance.DepthInputMode
import com.samin.objectdetection.distance.FrameDepthContext
import com.samin.objectdetection.distance.ObjectDepthEstimate

/** A single copied camera/depth pair. No native ARCore/Image object survives acquisition. */
class ArCoreFrameDepthContext(capture: ArYoloCapture, private val mode: DepthInputMode,
    private val sampler: ArObjectSurfaceDepthSampler, private val smoother: ArObjectDepthSmoother,
    private val nowNs: () -> Long, private val currentEpoch: () -> Long,
    private val diagnostics: Boolean = false, private val groundTruth: com.samin.objectdetection.distance.DepthGroundTruth? = null) : FrameDepthContext {
    // UI/ML Kit validity callbacks must not retain the copied YUV camera planes.
    private val mapping = capture.mapping
    private val full = capture.depth
    private val raw = capture.rawDepth
    private val frameNs = capture.frameTimestampNs
    private val capturedNs = capture.capturedElapsedNs
    private val epoch = capture.epoch
    private val tracking = capture.tracking
    private val depthStatus = capture.depthStatus
    private val rawStatus = capture.rawDepthStatus
    override val projection = mapping.projection()
    override fun isCurrent(): Boolean = epoch == currentEpoch()
    override fun estimate(detections: List<DetectionResult>): List<DetectionResult> {
        val now = nowNs()
        val objects = detections.map { detection ->
            val rawBox = mapping.rawBox(ImageBox.of(detection))
            var depth = if (!isCurrent()) ArObjectDepth(status = "COORDINATES_INVALIDATED") else
                sampler.sample(rawBox, mapping, full, raw, mode, frameNs, tracking, depthStatus, diagnostics, rawStatus)
            val timestamp = depth.timestampNs
            if (timestamp != null && now >= expiration(timestamp)) depth = depth.copy(meters = null, status = "STALE_DEPTH")
            ArYoloObject(detection, mapping.viewBox(rawBox), depth)
        }
        return smoother.update(objects, null, now).map { obj ->
            val d = obj.depth
            val truthMatches = groundTruth?.let { target -> detections.count { it.label.equals(target.objectLabel, true) } } ?: 0
            val truth = groundTruth?.takeIf { diagnostics && truthMatches == 1 && it.objectLabel.equals(obj.detection.label, true) }
            ObjectDepthEstimate(d.rawMeters, d.meters, d.method, d.sampleCount, d.status,
                frameNs, d.timestampNs, d.timestampNs?.let(::expiration) ?: capturedNs, tracking,
                d.analysis, d.filterStatus, d.filterLagMs,
                d.timestampNs?.let { ((now - capturedNs + frameNs - it) / 1_000_000).coerceAtLeast(0) }, d.trackId,
                truth?.meters, if (truth != null) "UNIQUE_TARGET" else if (groundTruth != null && truthMatches > 1) "AMBIGUOUS_TARGET" else "NOT_SET").applyTo(obj.detection)
        }
    }

    private fun expiration(timestamp: Long): Long = capturedNs +
        ArObjectDepthSampler.MAX_DEPTH_AGE_NS - (frameNs - timestamp).coerceAtLeast(0)
}
