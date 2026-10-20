package com.samin.objectdetection.detector

enum class YoloModel(val assetName: String, val inputSize: Int, val displayName: String) {
    YOLO_640("best.tflite", 640, "YOLO 640×640 (best.tflite)"),
    YOLO_512("best_512.tflite", 512, "YOLO 512×512 (best_512.tflite)")
}

/** Asset contract for the YOLO11s nine-class Detect export. List index is the model class ID. */
object ModelConfig {
    const val MODEL_ASSET_NAME = "best.tflite"
    const val LABEL_ASSET_NAME = "labels.txt"

    val CLASS_LABELS: List<String> = listOf(
        "bollard", "person", "bicycle", "car", "motorcycle",
        "bus", "truck", "stop sign", "bench"
    )
}
