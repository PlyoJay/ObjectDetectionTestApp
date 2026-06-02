package com.samin.objectdetection.dto

import com.samin.objectdetection.detector.DetectionResult

data class DetectionEvent(
    val deviceId: String,
    val timestamp: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val roi: RoiInfo,
    val detections: List<DetectionResult>
)

data class RoiInfo(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)