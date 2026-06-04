package com.samin.objectdetection.camera

data class DetectionConfig(
    val leftCropRatio: Float = 0.05f,  //0.18
    val rightCropRatio: Float = 0.05f,  //0.18
    val topCropRatio: Float = 0.10f, //0.25
    val detectIntervalMs: Long = 500L,
    val inputSize: Int = 640,
    val confidenceThreshold: Float = 0.60f,
    val minBoxAreaRatio: Float = 0.015f,
    val ignoreTopRatioForGuide: Float = 0.25f,
    val maxGuideObjectCount: Int = 2,
    val saveDebugImage: Boolean = true
)
