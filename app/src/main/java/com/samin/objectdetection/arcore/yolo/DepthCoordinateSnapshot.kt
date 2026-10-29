package com.samin.objectdetection.arcore.yolo

import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame

object DepthCoordinateSnapshot {
    fun freeze(frame: Frame, depthWidth: Int, depthHeight: Int, mapping: ArImageMapping,
        viewWidth: Int, viewHeight: Int, audit: Boolean,
        cpuValidBounds: ImageBox = ImageBox(0f, 0f, mapping.width.toFloat(), mapping.height.toFloat())): DepthPixelCoordinates {
        require(cpuValidBounds.valid(mapping.width, mapping.height))
        require(depthWidth > 0 && depthHeight > 0 && depthWidth.toLong() * depthHeight <= 1_048_576)
        val texture = FloatArray(depthWidth * depthHeight * 2)
        for (y in 0 until depthHeight) for (x in 0 until depthWidth) {
            val i = (y * depthWidth + x) * 2
            texture[i] = (x + .5f) / depthWidth; texture[i + 1] = (y + .5f) / depthHeight
        }
        val cpu = FloatArray(texture.size) { Float.NaN }; val view = FloatArray(texture.size) { Float.NaN }
        frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED, texture, Coordinates2d.IMAGE_PIXELS, cpu)
        frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED, texture, Coordinates2d.VIEW_NORMALIZED, view)
        require(cpu.all { it.isFinite() } && view.all { it.isFinite() }) { "ARCore pixel transform unavailable" }
        val metrics = if (audit) {
            val forward = FloatArray(texture.size) { Float.NaN }
            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, cpu, Coordinates2d.TEXTURE_NORMALIZED, forward)
            CoordinateMappingAudit.compare(mapping, cpu, forward, view, texture, depthWidth, depthHeight, viewWidth, viewHeight, cpuValidBounds)
        } else null
        return DepthPixelCoordinates(depthWidth, depthHeight, frame.timestamp, cpu, view, metrics, cpuValidBounds)
    }
}
