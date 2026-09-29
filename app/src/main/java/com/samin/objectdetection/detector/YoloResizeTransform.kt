package com.samin.objectdetection.detector

import com.samin.objectdetection.camera.YoloResizeMode
import kotlin.math.roundToInt

/** Geometry of the exact integer-sized image drawn into the model input. */
internal data class YoloResizeTransform(
    val mode: YoloResizeMode,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val modelWidth: Int,
    val modelHeight: Int,
    val scaledWidth: Int,
    val scaledHeight: Int,
    val padLeft: Int,
    val padTop: Int,
    val padRight: Int,
    val padBottom: Int
) {
    val scaleX: Float get() = scaledWidth.toFloat() / sourceWidth
    val scaleY: Float get() = scaledHeight.toFloat() / sourceHeight

    fun sourceX(normalizedModelX: Float): Float = if (mode == YoloResizeMode.STRETCH) {
        normalizedModelX.coerceIn(0f, 1f) * sourceWidth
    } else {
        ((normalizedModelX * modelWidth - padLeft) / scaleX).coerceIn(0f, sourceWidth.toFloat())
    }

    fun sourceY(normalizedModelY: Float): Float = if (mode == YoloResizeMode.STRETCH) {
        normalizedModelY.coerceIn(0f, 1f) * sourceHeight
    } else {
        ((normalizedModelY * modelHeight - padTop) / scaleY).coerceIn(0f, sourceHeight.toFloat())
    }

    companion object {
        fun calculate(
            mode: YoloResizeMode,
            sourceWidth: Int,
            sourceHeight: Int,
            modelWidth: Int,
            modelHeight: Int
        ): YoloResizeTransform {
            require(sourceWidth > 0 && sourceHeight > 0 && modelWidth > 0 && modelHeight > 0)
            val scale = minOf(modelWidth.toDouble() / sourceWidth, modelHeight.toDouble() / sourceHeight)
            val scaledWidth = if (mode == YoloResizeMode.LETTERBOX) {
                (sourceWidth * scale).roundToInt().coerceIn(1, modelWidth)
            } else modelWidth
            val scaledHeight = if (mode == YoloResizeMode.LETTERBOX) {
                (sourceHeight * scale).roundToInt().coerceIn(1, modelHeight)
            } else modelHeight
            val padLeft = (modelWidth - scaledWidth) / 2
            val padTop = (modelHeight - scaledHeight) / 2
            return YoloResizeTransform(
                mode, sourceWidth, sourceHeight, modelWidth, modelHeight,
                scaledWidth, scaledHeight, padLeft, padTop,
                modelWidth - scaledWidth - padLeft, modelHeight - scaledHeight - padTop
            )
        }
    }
}
