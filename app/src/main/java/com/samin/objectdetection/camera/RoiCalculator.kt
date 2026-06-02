package com.samin.objectdetection.camera

import android.graphics.Rect
import com.samin.objectdetection.camera.DetectionConfig

object RoiCalculator {

    fun calculate(
        frameWidth: Int,
        frameHeight: Int,
        config: DetectionConfig
    ): Rect {
        val left = (frameWidth * config.leftCropRatio).toInt()
        val top = (frameHeight * config.topCropRatio).toInt()
        val right = (frameWidth * (1f - config.rightCropRatio)).toInt()
        val bottom = frameHeight

        return Rect(left, top, right, bottom)
    }
}