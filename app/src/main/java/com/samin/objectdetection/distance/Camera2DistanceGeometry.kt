package com.samin.objectdetection.distance

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/** Optional metadata only: failure never interrupts camera input or object detection. */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class Camera2DistanceGeometry {
    @Volatile private var cameraInfo: Camera2CameraInfo? = null
    private val results = LinkedHashMap<Long, TotalCaptureResult>()

    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            synchronized(results) {
                results[timestamp] = result
                while (results.size > 16) results.remove(results.keys.first())
            }
        }
    }

    fun bind(info: Camera2CameraInfo) { cameraInfo = info }

    fun geometry(image: ImageProxy, automaticEnabled: Boolean = true): AnalysisCameraGeometry {
        val crop = image.cropRect
        val base = AnalysisCameraGeometry(image.width, image.height, image.imageInfo.rotationDegrees,
            ImageBounds(crop.left.toFloat(), crop.top.toFloat(), crop.right.toFloat(), crop.bottom.toFloat()))
        if (!automaticEnabled) return base.copy(automaticUnavailableReason = "AUTO_OPTICS_DISABLED")
        return runCatching {
            val info = cameraInfo ?: return base.copy(automaticUnavailableReason = "CAMERA2_UNAVAILABLE")
            // Match the actual exposure. Never reuse optics from an older frame or another lens.
            val result = synchronized(results) { results.remove(image.imageInfo.timestamp) }
                ?: return base.copy(automaticUnavailableReason = "CAPTURE_METADATA_UNAVAILABLE")
            fun unavailable(reason: String) = base.copy(automaticUnavailableReason = reason)
            val capabilities = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: return unavailable("CAMERA_CAPABILITIES_UNAVAILABLE")
            if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in capabilities) {
                return unavailable("LOGICAL_CAMERA_REQUIRES_MANUAL_FOV")
            }
            if (result.get(CaptureResult.SCALER_ROTATE_AND_CROP)?.let { it != CaptureResult.SCALER_ROTATE_AND_CROP_NONE } == true ||
                result.get(CaptureResult.SENSOR_PIXEL_MODE)?.let { it != CaptureResult.SENSOR_PIXEL_MODE_DEFAULT } == true) {
                return unavailable("UNSUPPORTED_SENSOR_TRANSFORM")
            }
            val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?: return unavailable("SENSOR_SIZE_UNAVAILABLE")
            val pre = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                ?: return unavailable("SENSOR_SIZE_UNAVAILABLE")
            // No distortion model is applied here. Reject any ambiguous corrected coordinate system.
            val distortion = result.get(CaptureResult.LENS_DISTORTION)
                ?: info.getCameraCharacteristic(CameraCharacteristics.LENS_DISTORTION)
            if (pre != active || distortion?.any { !it.isFinite() || abs(it) > 1e-6f } == true) {
                return unavailable("DISTORTION_REQUIRES_MANUAL_FOV")
            }
            val video = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
            val optical = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
            if (video != CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_OFF ||
                optical != CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_OFF) {
                return unavailable("STABILIZATION_OR_METADATA_UNSUPPORTED")
            }
            val zoom = result.get(CaptureResult.CONTROL_ZOOM_RATIO)
            if (zoom == null || abs(zoom - 1f) > 1e-4f) return unavailable("ZOOM_REQUIRES_MANUAL_FOV")
            val sensorCrop = result.get(CaptureResult.SCALER_CROP_REGION)
                ?: return unavailable("SENSOR_CROP_UNAVAILABLE")
            if (!active.contains(sensorCrop) || sensorCrop.isEmpty) return unavailable("INVALID_SENSOR_CROP")
            val frameCalibration = result.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)
            val calibration = frameCalibration ?: info.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            val sensorK = if (calibration != null) {
                if (frameCalibration == null) {
                    val focalLengths = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val focal = result.get(CaptureResult.LENS_FOCAL_LENGTH)
                    if (focalLengths?.size != 1 || focal == null || !focal.isFinite() || abs(focal - focalLengths[0]) > .001f) {
                        return unavailable("VARIABLE_FOCAL_CALIBRATION_UNAVAILABLE")
                    }
                }
                if (calibration.size < 5 || calibration.any { !it.isFinite() } || abs(calibration[4]) > 1e-4f) {
                    return unavailable("INVALID_INTRINSIC_CALIBRATION")
                }
                CameraIntrinsics(calibration[0], calibration[1], calibration[2] + pre.left,
                    calibration[3] + pre.top, "CAMERA2_INTRINSICS")
            } else {
                // Physical-size fallback is an explicitly recorded approximation, never a fixed FOV.
                val mm = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    ?: return unavailable("PHYSICAL_OPTICS_UNAVAILABLE")
                val pixels = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    ?: return unavailable("PHYSICAL_OPTICS_UNAVAILABLE")
                val focal = result.get(CaptureResult.LENS_FOCAL_LENGTH)
                    ?: return unavailable("FOCAL_LENGTH_UNAVAILABLE")
                if (!focal.isFinite() || focal <= 0 || mm.width <= 0 || mm.height <= 0) return unavailable("INVALID_PHYSICAL_OPTICS")
                CameraIntrinsics(focal * pixels.width / mm.width, focal * pixels.height / mm.height,
                    pixels.width / 2f, pixels.height / 2f, "CAMERA2_PHYSICAL_APPROXIMATION")
            }
            val effective = CameraIntrinsics.fromSensorCrop(sensorK, ImageBounds(sensorCrop.left.toFloat(),
                sensorCrop.top.toFloat(), sensorCrop.right.toFloat(), sensorCrop.bottom.toFloat()), image.width, image.height)
                ?: return unavailable("INVALID_EFFECTIVE_INTRINSICS")
            base.copy(automaticIntrinsics = effective)
        }.getOrElse { base.copy(automaticUnavailableReason = "CAMERA2_METADATA_ERROR") }
    }
}
