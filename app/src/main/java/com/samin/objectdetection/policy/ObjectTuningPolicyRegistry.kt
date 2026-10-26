package com.samin.objectdetection.policy

import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.ModelConfig
import com.samin.objectdetection.warning.FeedbackLevel
import com.samin.objectdetection.warning.ObjectPriority
import com.samin.objectdetection.warning.RiskObjectCategory
import com.samin.objectdetection.warning.WarningFeedback
import java.util.Locale

object ObjectTuningPolicyRegistry {

    private val vehicleLabels = listOf("car", "bus", "truck", "motorcycle", "bicycle")
    private val staticObstacleLabels = listOf("bench")

    val currentModelLabels: Set<String> = ModelConfig.CLASS_LABELS.toSet()

    // Reserved policy templates. These are not emitted or enabled by either bundled model.
    val futureCustomLabels = setOf(
        "stairs",
        "curb",
        "low_obstacle"
    )

    private val policies = buildList {
        listOf("cat", "dog").forEach { label ->
            add(ObjectTuningPolicy(
                label = label,
                minConfidence = DetectionConfig.DEFAULT_CONFIDENCE_THRESHOLD,
                minAreaRatio = 0.015f,
                enableOverlay = true,
                enableWarning = false,
                enableVoice = false,
                priority = ObjectPriority.LOW,
                category = RiskObjectCategory.UNKNOWN,
                note = "신규 동물 클래스는 탐지·표시·통계만 지원하며 경고·음성 안내에서 제외"
            ))
        }
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
        (listOf("bollard") + futureCustomLabels).forEach { label ->
            val isBollard = label == "bollard"
            add(
                ObjectTuningPolicy(
                    label = label,
                    minConfidence = if (isBollard) {
                        DetectionConfig.DEFAULT_CONFIDENCE_THRESHOLD
                    } else {
                        0.45f
                    },
                    minAreaRatio = if (isBollard) BOLLARD_MIN_AREA_RATIO else 0.006f,
                    minWidthRatio = if (isBollard) BOLLARD_MIN_WIDTH_RATIO else null,
                    minHeightRatio = if (isBollard) BOLLARD_MIN_HEIGHT_RATIO else null,
                    enableOverlay = true,
                    enableWarning = true,
                    enableVoice = true,
                    priority = ObjectPriority.HIGH,
                    category = RiskObjectCategory.TEMPORARY_OBSTACLE,
                    note = if (isBollard) {
                        "중·원거리 Bollard 필드 테스트를 위해 전용 소형 bbox 기준 적용"
                    } else {
                        "커스텀 보행 장애물 모델에서 사용할 라벨"
                    }
                )
            )
        }
    }.associateBy { normalize(it.label) }

    val overlayLabels: Set<String> = policies.values
        .filter { it.label in currentModelLabels && it.enableOverlay }
        .map { normalize(it.label) }
        .toSet()

    fun get(label: String): ObjectTuningPolicy? {
        return policies[normalize(label)]
    }

    fun getAll(): List<ObjectTuningPolicy> {
        return policies.values.toList()
    }

    fun shouldShowOverlay(label: String): Boolean {
        return normalize(label) in currentModelLabels && get(label)?.enableOverlay == true
    }

    fun shouldWarn(detection: DetectionResult): Boolean {
        val policy = get(detection.label) ?: return false
        return normalize(detection.label) in currentModelLabels && policy.enableWarning &&
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
        if (!policy.enableWarning) return detection.copy(warningFeedback = WarningFeedback.NONE)
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

    private const val BOLLARD_MIN_AREA_RATIO = 0.002f
    private const val BOLLARD_MIN_WIDTH_RATIO = 0.010f
    private const val BOLLARD_MIN_HEIGHT_RATIO = 0.010f

}
