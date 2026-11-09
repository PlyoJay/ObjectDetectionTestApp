package com.samin.objectdetection.detector

enum class YoloModel(val assetName: String, val inputSize: Int, val displayName: String) {
    YOLO_512("best_512.tflite", 512, "YOLO11s · 기준 모델 · 12클래스 · 512×512 (best_512.tflite)"),
    YOLO_11N_512("yolo11n_512.tflite", 512, "YOLO11n · 경량 모델 · 12클래스 · 512×512 (yolo11n_512.tflite)");

    val classLabels: List<String> get() = ModelConfig.labelsFor(assetName)
}

/** Verified against each asset's embedded metadata.json names. Index is the model class ID. */
object ModelConfig {
    const val MODEL_ASSET_NAME = "best_512.tflite"
    const val LABEL_ASSET_NAME = "labels.txt"

    // Existing warning-enabled class IDs. The remaining classes stay display/statistics only.
    val WARNING_CLASS_LABELS: List<String> = listOf(
        "bollard", "person", "bicycle", "car", "motorcycle",
        "bus", "truck", "stop sign", "bench"
    )

    val CLASS_LABELS: List<String> = WARNING_CLASS_LABELS + listOf("cat", "dog", "cardboard_box")

    fun labelsFor(modelName: String): List<String> = when (modelName) {
        "best_512.tflite", "yolo11n_512.tflite" -> CLASS_LABELS
        else -> throw IllegalArgumentException("Unverified model class order: $modelName")
    }
}
