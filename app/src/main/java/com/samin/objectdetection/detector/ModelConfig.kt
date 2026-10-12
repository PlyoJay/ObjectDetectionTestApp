package com.samin.objectdetection.detector

/** Asset contract for the YOLO11s nine-class Detect export. List index is the model class ID. */
object ModelConfig {
    const val MODEL_ASSET_NAME = "best.tflite"
    const val LABEL_ASSET_NAME = "labels.txt"

    val CLASS_LABELS: List<String> = listOf(
        "bollard", "person", "bicycle", "car", "motorcycle",
        "bus", "truck", "stop sign", "bench"
    )
}
