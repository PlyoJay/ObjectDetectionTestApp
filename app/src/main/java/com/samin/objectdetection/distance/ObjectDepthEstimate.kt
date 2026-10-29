package com.samin.objectdetection.distance

import com.samin.objectdetection.detector.DetectionResult

enum class ObjectDistanceMethod { ARCORE_DEPTH, GROUND_PLANE }
enum class DepthInputMode { FULL, RAW_WITH_FULL_FALLBACK }
enum class DepthMethod { FULL, RAW }

/** Camera-forward Z only. Kept separate from ground-plane calibration and warning inputs. */
data class ObjectDepthEstimate(
    val rawMeters: Float?, val meters: Float?, val method: DepthMethod?, val sampleCount: Int,
    val status: String, val frameTimestampNs: Long, val depthTimestampNs: Long?,
    val expiresElapsedNs: Long, val trackingState: String,
    val analysis: DepthAnalysis? = null, val filterStatus: String = "RAW", val filterLagMs: Long = 0,
    val updateDelayMs: Long? = null, val trackId: Long? = null, val groundTruthMeters: Float? = null,
    val groundTruthStatus: String = "NOT_SET"
) {
    val absoluteErrorMeters: Float? get() = groundTruthMeters?.takeIf { it.isFinite() && it > 0 }?.let { truth -> rawMeters?.takeIf { it.isFinite() && it > 0 }?.let { kotlin.math.abs(it - truth) } }
    val relativeErrorPercent: Float? get() = absoluteErrorMeters?.let {
        (it.toDouble() / groundTruthMeters!!.toDouble() * 100).toFloat().takeIf { value -> value.isFinite() }
    }
    val filteredAbsoluteErrorMeters: Float? get() = groundTruthMeters?.takeIf { it.isFinite() && it > 0 }?.let { truth -> meters?.takeIf { it.isFinite() && it > 0 }?.let { kotlin.math.abs(it - truth) } }
    fun isFresh(nowNs: Long): Boolean = trackingState == "TRACKING" && depthTimestampNs != null &&
        depthTimestampNs > 0 && nowNs < expiresElapsedNs && meters?.let { it.isFinite() && it > 0 } == true
    fun applyTo(detection: DetectionResult): DetectionResult = detection.copy(
        estimatedDistanceMeters = meters?.takeIf { it.isFinite() && it > 0 },
        distanceEstimation = null, depthEstimation = this)
}

/** Upright image-normalized coordinates to ARCore viewport-normalized coordinates. */
data class ViewportProjection(val scaleX: Float, val scaleY: Float, val offsetX: Float, val offsetY: Float) {
    fun x(pixel: Float, imageWidth: Int): Float = offsetX + pixel / imageWidth * scaleX
    fun y(pixel: Float, imageHeight: Int): Float = offsetY + pixel / imageHeight * scaleY
}

interface FrameDepthContext {
    val projection: ViewportProjection
    fun isCurrent(): Boolean
    fun estimate(detections: List<DetectionResult>): List<DetectionResult>
}
