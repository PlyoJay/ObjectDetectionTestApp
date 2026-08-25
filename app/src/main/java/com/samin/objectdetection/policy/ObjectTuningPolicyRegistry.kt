package com.samin.objectdetection.policy

import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.warning.FeedbackLevel
import com.samin.objectdetection.warning.ObjectPriority
import com.samin.objectdetection.warning.RiskObjectCategory
import java.util.Locale

object ObjectTuningPolicyRegistry {

    private val vehicleLabels = listOf("car", "bus", "truck", "motorcycle", "bicycle")
    private val staticObstacleLabels = listOf("bench", "fire hydrant")
    private val excludedCocoLabels = listOf("backpack", "umbrella", "cell phone", "parking meter")

    val cocoTuningLabels = setOf(
        "person",
        "bicycle",
        "car",
        "motorcycle",
        "bus",
        "truck",
        "traffic light",
        "stop sign",
        "bench",
        "fire hydrant",
        "chair",
        "backpack",
        "umbrella",
        "cell phone",
        "parking meter"
    )

    // The current yolo11n_float32.tflite model uses COCO labels, so these custom labels may not be emitted
    // until a future custom model and matching labels.txt are shipped.
    val futureCustomLabels = setOf(
        "bollard",
        "stairs",
        "curb",
        "low_obstacle"
    )

    private val policies = buildList {
        add(
            ObjectTuningPolicy(
                label = "person",
                minConfidence = 0.50f,
                minAreaRatio = 0.006f,
                enableOverlay = true,
                enableWarning = true,
                enableVoice = false,
                priority = ObjectPriority.LOW,
                category = RiskObjectCategory.HUMAN_FLOW,
                note = "사람은 보행 흐름/혼잡 판단에는 사용하지만 음성 안내는 기본 비활성화"
            )
        )
        vehicleLabels.forEach { label ->
            add(
                ObjectTuningPolicy(
                    label = label,
                    minConfidence = 0.50f,
                    minAreaRatio = 0.008f,
                    enableOverlay = true,
                    enableWarning = true,
                    enableVoice = true,
                    priority = ObjectPriority.HIGH,
                    category = RiskObjectCategory.VEHICLE_RISK,
                    note = "차량/이동체는 보행 위험도가 높아 안내 대상"
                )
            )
        }
        add(
            ObjectTuningPolicy(
                label = "traffic light",
                minConfidence = 0.55f,
                minAreaRatio = 0.002f,
                enableOverlay = true,
                enableWarning = true,
                enableVoice = true,
                priority = ObjectPriority.HIGH,
                category = RiskObjectCategory.TRAFFIC_CONTROL,
                note = "작게 잡히는 경우가 많아 bbox 면적 기준을 낮게 설정"
            )
        )
        add(
            ObjectTuningPolicy(
                label = "stop sign",
                minConfidence = 0.45f,
                minAreaRatio = 0.002f,
                enableOverlay = true,
                enableWarning = true,
                enableVoice = true,
                priority = ObjectPriority.HIGH,
                category = RiskObjectCategory.TRAFFIC_CONTROL,
                note = "정지 표지는 보행 안전 안내 대상으로 유지"
            )
        )
        staticObstacleLabels.forEach { label ->
            add(
                ObjectTuningPolicy(
                    label = label,
                    minConfidence = 0.45f,
                    minAreaRatio = 0.010f,
                    enableOverlay = true,
                    enableWarning = true,
                    enableVoice = true,
                    priority = ObjectPriority.LOW,
                    category = RiskObjectCategory.STATIC_OBSTACLE,
                    note = "고정 장애물은 가까운 경우 안내 대상"
                )
            )
        }
        add(
            ObjectTuningPolicy(
                label = "chair",
                minConfidence = 0.45f,
                minAreaRatio = 0.015f,
                enableOverlay = true,
                enableWarning = false,
                enableVoice = false,
                priority = ObjectPriority.LOW,
                category = RiskObjectCategory.TEMPORARY_OBSTACLE,
                note = "실외 보행 기준에서는 오탐/불필요 안내 가능성이 있어 기본 경고 제외"
            )
        )
        excludedCocoLabels.forEach { label ->
            add(
                ObjectTuningPolicy(
                    label = label,
                    minConfidence = 0.50f,
                    minAreaRatio = 0.015f,
                    enableOverlay = false,
                    enableWarning = false,
                    enableVoice = false,
                    priority = ObjectPriority.LOW,
                    category = RiskObjectCategory.UNKNOWN,
                    note = "현재 보행 안내 정책에서는 제외"
                )
            )
        }
        futureCustomLabels.forEach { label ->
            add(
                ObjectTuningPolicy(
                    label = label,
                    minConfidence = if (label == "bollard") 0.15f else 0.45f,
                    minAreaRatio = 0.006f,
                    enableOverlay = true,
                    enableWarning = true,
                    enableVoice = true,
                    priority = ObjectPriority.HIGH,
                    category = RiskObjectCategory.TEMPORARY_OBSTACLE,
                    note = "향후 커스텀 보행 장애물 모델에서 사용할 라벨"
                )
            )
        }
    }.associateBy { normalize(it.label) }

    val overlayLabels: Set<String> = policies.values
        .filter { it.enableOverlay }
        .map { normalize(it.label) }
        .toSet()

    fun get(label: String): ObjectTuningPolicy? {
        return policies[normalize(label)]
    }

    fun getAll(): List<ObjectTuningPolicy> {
        return policies.values.toList()
    }

    fun shouldShowOverlay(label: String): Boolean {
        return get(label)?.enableOverlay == true
    }

    fun shouldWarn(detection: DetectionResult): Boolean {
        val policy = get(detection.label) ?: return false
        return policy.enableWarning &&
            !detection.isIgnored &&
            detection.confidence >= policy.minConfidence
    }

    fun minAreaRatioFor(label: String, config: DetectionConfig): Float {
        return get(label)?.minAreaRatio ?: config.minBoxAreaRatio
    }

    fun minWidthRatioFor(label: String, config: DetectionConfig): Float {
        return get(label)?.minWidthRatio ?: config.minBoxWidthRatio
    }

    fun minHeightRatioFor(label: String, config: DetectionConfig): Float {
        return get(label)?.minHeightRatio ?: config.minBoxHeightRatio
    }

    fun applyVoiceTuning(detection: DetectionResult): DetectionResult {
        val policy = get(detection.label) ?: return detection
        if (policy.enableVoice) return detection
        val feedback = detection.warningFeedback.copy(
            voiceLevel = FeedbackLevel.NONE,
            message = null,
            shouldNotify = detection.warningFeedback.beepLevel != FeedbackLevel.NONE ||
                detection.warningFeedback.vibrationLevel != FeedbackLevel.NONE
        )
        return detection.copy(warningFeedback = feedback)
    }

    fun normalize(label: String): String {
        return label.trim().lowercase(Locale.US)
    }

}
