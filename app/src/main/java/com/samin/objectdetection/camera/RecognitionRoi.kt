package com.samin.objectdetection.camera

/** Ratios refer to the upright (already rotated) full frame, never sensor axes. */
data class RecognitionRoi(
    val enabled: Boolean = false,
    val top: Float = 0f,
    val bottom: Float = 0f,
    val left: Float = 0f,
    val right: Float = 0f,
    val showRegion: Boolean = true
) {
    fun validationErrors(): List<String> = buildList {
        if (listOf(top, bottom, left, right).any { !it.isFinite() || it !in 0f..1f })
            add("크롭 비율은 0~100%의 유한한 값이어야 합니다.")
        if (top + bottom >= 1f || left + right >= 1f)
            add("상단+하단 및 왼쪽+오른쪽 크롭 합계는 각각 100% 미만이어야 합니다.")
    }

    fun description(centerSquare: Boolean): String =
        "mode=${if (enabled) "EDGE_CROP" else if (centerSquare) "CENTER_SQUARE" else "FULL_FRAME"} " +
            "enabled=$enabled top=$top bottom=$bottom left=$left right=$right showRegion=$showRegion"

    companion object { val PRESET = RecognitionRoi(true, top = .10f, left = .05f, right = .05f) }
}

/** Single crop decision shared by YOLO, ML Kit, logs and preview. Edge ROI replaces center crop. */
data class FrameRoi(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun sourceX(inputX: Float, inputWidth: Int = width): Float =
        left + (if (inputWidth == width) inputX else inputX / inputWidth * width).coerceIn(0f, width.toFloat())
    fun sourceY(inputY: Float, inputHeight: Int = height): Float =
        top + (if (inputHeight == height) inputY else inputY / inputHeight * height).coerceIn(0f, height.toFloat())

    companion object {
        fun calculate(width: Int, height: Int, roi: RecognitionRoi, centerSquare: Boolean): FrameRoi {
            require(width > 0 && height > 0)
            require(roi.validationErrors().isEmpty())
            if (roi.enabled) return FrameRoi(
                (width * roi.left).toInt(), (height * roi.top).toInt(),
                width - (width * roi.right).toInt(), height - (height * roi.bottom).toInt())
            if (!centerSquare) return FrameRoi(0, 0, width, height)
            val size = minOf(width, height)
            val left = (width - size) / 2
            val top = (height - size) / 2
            return FrameRoi(left, top, left + size, top + size)
        }
    }
}
