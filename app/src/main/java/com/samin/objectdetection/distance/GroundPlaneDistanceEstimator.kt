package com.samin.objectdetection.distance

import com.samin.objectdetection.detector.DetectionResult
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

enum class DistanceOpticsMode { AUTO, MANUAL }

/** Null mounting values deliberately mean uncalibrated. No guessed installation defaults. */
data class DistanceSettings(
    val cameraHeightCm: Float? = null,
    val downwardPitchDegrees: Float? = null,
    val opticsMode: DistanceOpticsMode = DistanceOpticsMode.AUTO,
    // Vertical FOV of the entire UNROTATED ImageAnalysis buffer, before application ROI.
    val manualVerticalFovDegrees: Float? = null
) {
    fun validationErrors(): List<String> = buildList {
        if (cameraHeightCm != null && cameraHeightCm !in 1f..1000f) add("카메라 설치 높이는 1~1000cm 또는 미설정이어야 합니다.")
        if (downwardPitchDegrees != null && downwardPitchDegrees !in 0f..89f) add("카메라 하향 각도는 0~89° 또는 미설정이어야 합니다.")
        if (manualVerticalFovDegrees != null && manualVerticalFovDegrees !in 5f..150f) add("수동 수직 화각은 5~150° 또는 미설정이어야 합니다.")
    }
}

data class ImageBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun isValid(width: Int, height: Int): Boolean =
        listOf(left, top, right, bottom).all { it.isFinite() } &&
            left >= 0 && top >= 0 && right <= width && bottom <= height && right > left && bottom > top

    fun rotate(width: Int, height: Int, degrees: Int): ImageBounds = when (degrees) {
        0 -> this
        90 -> ImageBounds(height - bottom, left, height - top, right)
        180 -> ImageBounds(width - right, height - bottom, width - left, height - top)
        270 -> ImageBounds(top, width - right, bottom, width - left)
        else -> error("Unsupported rotation")
    }
}

/** Pinhole parameters in edge-based pixel coordinates (pixel centers are at n + 0.5). */
data class CameraIntrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float, val source: String) {
    fun isValid(width: Int, height: Int): Boolean =
        listOf(fx, fy, cx, cy).all { it.isFinite() } && fx > 0 && fy > 0 &&
            cx in 0f..width.toFloat() && cy in 0f..height.toFloat()

    fun rotate(width: Int, height: Int, degrees: Int): CameraIntrinsics = when (degrees) {
        0 -> this
        90 -> copy(fx = fy, fy = fx, cx = height - cy, cy = cx)
        180 -> copy(cx = width - cx, cy = height - cy)
        270 -> copy(fx = fy, fy = fx, cx = cy, cy = width - cx)
        else -> error("Unsupported rotation")
    }

    companion object {
        fun manual(width: Int, height: Int, verticalFov: Float): CameraIntrinsics? {
            if (width <= 0 || height <= 0 || verticalFov !in 5f..150f) return null
            val focal = (height / (2.0 * tan(Math.toRadians(verticalFov.toDouble()) / 2))).toFloat()
            return CameraIntrinsics(focal, focal, width / 2f, height / 2f, "MANUAL_FOV")
        }

        /** Camera2 scaler crop followed by the stream's aspect-ratio center crop and scaling. */
        fun fromSensorCrop(sensor: CameraIntrinsics, crop: ImageBounds, width: Int, height: Int): CameraIntrinsics? {
            val cw = crop.right - crop.left
            val ch = crop.bottom - crop.top
            if (width <= 0 || height <= 0 || !cw.isFinite() || !ch.isFinite() || cw <= 0 || ch <= 0) return null
            val scale = maxOf(width / cw, height / ch)
            val result = sensor.copy(fx = sensor.fx * scale, fy = sensor.fy * scale,
                cx = (sensor.cx - (crop.left + (cw - width / scale) / 2)) * scale,
                cy = (sensor.cy - (crop.top + (ch - height / scale) / 2)) * scale)
            return result.takeIf { it.isValid(width, height) }
        }
    }
}

/** Describes the actual full buffer. proxy crop is a validity mask, never a second bitmap crop. */
data class AnalysisCameraGeometry(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val rotationDegrees: Int,
    val validBufferBounds: ImageBounds = ImageBounds(0f, 0f, bufferWidth.toFloat(), bufferHeight.toFloat()),
    val automaticIntrinsics: CameraIntrinsics? = null,
    val automaticUnavailableReason: String? = null
) {
    val width: Int get() = if (rotationDegrees % 180 == 0) bufferWidth else bufferHeight
    val height: Int get() = if (rotationDegrees % 180 == 0) bufferHeight else bufferWidth
    fun isValid(): Boolean = bufferWidth > 0 && bufferHeight > 0 && rotationDegrees in listOf(0, 90, 180, 270) &&
        validBufferBounds.isValid(bufferWidth, bufferHeight)
    fun uprightBounds(): ImageBounds = validBufferBounds.rotate(bufferWidth, bufferHeight, rotationDegrees)
    fun intrinsics(settings: DistanceSettings): CameraIntrinsics? {
        if (!isValid()) return null
        val raw = when (settings.opticsMode) {
            DistanceOpticsMode.AUTO -> automaticIntrinsics
            DistanceOpticsMode.MANUAL -> settings.manualVerticalFovDegrees?.let {
                CameraIntrinsics.manual(bufferWidth, bufferHeight, it)
            }
        } ?: return null
        return raw.takeIf { it.isValid(bufferWidth, bufferHeight) }?.rotate(bufferWidth, bufferHeight, rotationDegrees)
    }
}

data class DistanceEstimate(
    val meters: Float? = null,
    val method: String = "GROUND_PLANE",
    val status: String,
    val intrinsics: CameraIntrinsics? = null
) {
    fun applyTo(detection: DetectionResult): DetectionResult = detection.copy(
        estimatedDistanceMeters = meters, distanceEstimation = this)
}

/** Fixed camera, level ground, zero roll. Reports ground-forward Z, not slant or radial range. */
class GroundPlaneDistanceEstimator(private val settings: DistanceSettings) {
    fun estimate(detection: DetectionResult, geometry: AnalysisCameraGeometry?, inferenceBounds: ImageBounds? = null): DistanceEstimate {
        fun invalid(reason: String, k: CameraIntrinsics? = null) = DistanceEstimate(status = reason, intrinsics = k)
        val height = settings.cameraHeightCm ?: return invalid("MOUNT_UNSET")
        val pitch = settings.downwardPitchDegrees ?: return invalid("MOUNT_UNSET")
        if (settings.validationErrors().isNotEmpty()) return invalid("INVALID_SETTINGS")
        if (geometry == null || !geometry.isValid()) return invalid("INVALID_FRAME_GEOMETRY")
        val k = geometry.intrinsics(settings) ?: return invalid(
            if (settings.opticsMode == DistanceOpticsMode.AUTO) geometry.automaticUnavailableReason ?: "AUTO_OPTICS_UNAVAILABLE"
            else "MANUAL_FOV_UNSET")
        val box = listOf(detection.left, detection.top, detection.right, detection.bottom)
        if (box.any { !it.isFinite() } || detection.left < 0 || detection.top < 0 ||
            detection.right > geometry.width || detection.bottom > geometry.height ||
            detection.right <= detection.left || detection.bottom <= detection.top) return invalid("INVALID_BBOX", k)
        val bounds = geometry.uprightBounds()
        val roi = inferenceBounds ?: ImageBounds(0f, 0f, geometry.width.toFloat(), geometry.height.toFloat())
        if (!roi.isValid(geometry.width, geometry.height)) return invalid("INVALID_ROI", k)
        val contactX = (detection.left + detection.right) / 2.0
        val contactY = detection.bottom.toDouble()
        // Conservative one-pixel guard: clamped detector/ROI/proxy edge boxes cannot prove a ground contact.
        if (contactX < maxOf(bounds.left, roi.left) || contactX > minOf(bounds.right, roi.right) ||
            contactY < maxOf(bounds.top, roi.top) || contactY >= minOf(bounds.bottom, roi.bottom) - 1f) {
            return invalid("CONTACT_CLIPPED", k)
        }
        val q = (contactY - k.cy) / k.fy
        val theta = Math.toRadians(pitch.toDouble())
        val down = sin(theta) + q * cos(theta)
        val forward = cos(theta) - q * sin(theta)
        if (down <= 1e-6 || forward <= 0) return invalid("NO_FORWARD_GROUND_INTERSECTION", k)
        val meters = ((height / 100.0) * forward / down).toFloat()
        if (!meters.isFinite() || meters <= 0) return invalid("INVALID_DISTANCE", k)
        return DistanceEstimate(meters, status = "VALID", intrinsics = k)
    }
}
