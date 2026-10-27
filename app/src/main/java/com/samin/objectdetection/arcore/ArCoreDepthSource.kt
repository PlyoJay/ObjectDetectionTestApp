package com.samin.objectdetection.arcore

import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.NotTrackingException

/** Only called for the current frame, inside the Session/GL lock. Images never escape this call. */
class ArCoreDepthSource : ForwardDepthSource<Frame> {
    private val viewCenter = floatArrayOf(.5f, .5f)
    private val textureCenter = FloatArray(2)

    override fun measure(input: Frame): DepthSample {
        val base = DepthSample(input.timestamp, input.camera.trackingState.name, depthStatus = "WAITING_FOR_DEPTH")
        if (input.camera.trackingState != TrackingState.TRACKING) return base.copy(depthStatus = "TRACKING_PAUSED")
        return try {
            input.acquireDepthImage16Bits().use { image ->
                input.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, viewCenter,
                    Coordinates2d.TEXTURE_NORMALIZED, textureCenter)
                val sample = base.copy(depthWidth = image.width, depthHeight = image.height, depthTimestampNs = image.timestamp)
                val pixel = DepthPlaneSampler.pixel(textureCenter[0], textureCenter[1], image.width, image.height)
                    ?: return sample.copy(depthStatus = "CENTER_OUT_OF_BOUNDS")
                val plane = image.planes.firstOrNull() ?: return sample.copy(depthStatus = "INVALID_DEPTH_PLANE")
                val region = DepthPlaneSampler.sample(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride, pixel)
                sample.copy(centerX = pixel.x, centerY = pixel.y, rawDepthMeters = region.meters,
                    validSampleCount = region.validCount, depthStatus = if (region.meters != null) "UPDATED" else "INSUFFICIENT_SAMPLES")
            }
        } catch (_: NotYetAvailableException) {
            base
        } catch (_: NotTrackingException) {
            base.copy(trackingState = "PAUSED", depthStatus = "TRACKING_PAUSED")
        } catch (_: Exception) {
            base.copy(depthStatus = "DEPTH_ACQUISITION_ERROR")
        }
    }
}
