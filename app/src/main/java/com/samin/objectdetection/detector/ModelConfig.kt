package com.samin.objectdetection.detector

enum class YoloModel(val assetName: String, val inputSize: Int, val displayName: String) {
    YOLO_640("best.tflite", 640, "YOLO 640×640 (best.tflite)"),
    YOLO_512("best_512.tflite", 512, "YOLO11s v2 · 12클래스 · 512×512 (best_512.tflite)"),
    YOLO_512_V1("best_512_v1_11class.tflite", 512, "YOLO11s v1 · 11클래스 · 512×512 (best_512_v1_11class.tflite)");

    val classLabels: List<String> get() = ModelConfig.labelsFor(assetName)
}

/** Verified against each asset's embedded metadata.json names. Index is the model class ID. */
object ModelConfig {
    const val MODEL_ASSET_NAME = "best.tflite"
    const val LABEL_ASSET_NAME = "labels.txt"

    val LEGACY_CLASS_LABELS: List<String> = listOf(
        "bollard", "person", "bicycle", "car", "motorcycle",
        "bus", "truck", "stop sign", "bench"
    )

    val V1_CLASS_LABELS: List<String> = LEGACY_CLASS_LABELS + listOf("cat", "dog")
    val CLASS_LABELS: List<String> = V1_CLASS_LABELS + "cardboard_box"

    fun labelsFor(modelName: String): List<String> = when (modelName) {
        "best.tflite" -> LEGACY_CLASS_LABELS
        "best_512_v1_11class.tflite" -> V1_CLASS_LABELS
        "best_512.tflite" -> CLASS_LABELS
        else -> throw IllegalArgumentException("Unverified model class order: $modelName")
    }
}
