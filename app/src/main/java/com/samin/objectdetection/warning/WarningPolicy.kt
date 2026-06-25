package com.samin.objectdetection.warning

import android.util.Log
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.model.DetectionCategory
import com.samin.objectdetection.model.DetectionPriority
import com.samin.objectdetection.motion.MotionDirection
import java.util.Locale

object WarningPolicy {
    const val DEFAULT_MIN_CONFIDENCE = 0.35f
    const val DEFAULT_MIN_AREA_RATIO = 0.005f

    fun evaluate(
        detection: DetectionResult,
        frameWidth: Int,
        frameHeight: Int,
        minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
        minAreaRatio: Float = DEFAULT_MIN_AREA_RATIO
    ): DetectionResult {
        val safeFrameWidth = frameWidth.coerceAtLeast(1).toFloat()
        val safeFrameHeight = frameHeight.coerceAtLeast(1).toFloat()
        val bboxWidth = (detection.right - detection.left).coerceAtLeast(0f)
        val bboxHeight = (detection.bottom - detection.top).coerceAtLeast(0f)
        val areaRatio = bboxWidth * bboxHeight / (safeFrameWidth * safeFrameHeight)
        val heightRatio = bboxHeight / safeFrameHeight
        val centerXRatio = ((detection.left + bboxWidth / 2f) / safeFrameWidth).coerceIn(0f, 1f)
        val centerYRatio = ((detection.top + bboxHeight / 2f) / safeFrameHeight).coerceIn(0f, 1f)
        val horizontalPosition = resolveHorizontalPosition(centerXRatio)
        val category = resolveObjectCategory(detection.label)
        val priority = resolveObjectPriority(detection.label)
        val proximityLevel = resolveProximityLevel(heightRatio, areaRatio)
        val isIgnored = detection.confidence < minConfidence || areaRatio < minAreaRatio
        val riskLevel = if (isIgnored) {
            RiskLevel.NONE
        } else {
            resolveRiskLevel(
                proximityLevel = proximityLevel,
                category = category,
                priority = priority,
                horizontalPosition = horizontalPosition
            )
        }

        val evaluated = detection.copy(
            bboxWidth = bboxWidth,
            bboxHeight = bboxHeight,
            bboxAreaRatio = areaRatio,
            bboxHeightRatio = heightRatio,
            centerXRatio = centerXRatio,
            centerYRatio = centerYRatio,
            horizontalPosition = horizontalPosition,
            riskObjectCategory = category,
            objectPriority = priority,
            proximityLevel = proximityLevel,
            riskLevel = riskLevel,
            isIgnored = isIgnored
        )

        return applyScenarioFeedback(evaluated)
    }

    fun applyScenarioFeedback(detection: DetectionResult): DetectionResult {
        val scenario = resolveScenario(detection)
        val scenarioDetection = detection.copy(warningScenario = scenario)
        return scenarioDetection.copy(warningFeedback = buildWarningFeedback(scenarioDetection))
    }

    fun logDebug(detection: DetectionResult) {
        Log.d(
            TAG,
            "label=${detection.label}, conf=${format(detection.confidence)}, " +
                "areaRatio=${format(detection.bboxAreaRatio)}, " +
                "heightRatio=${format(detection.bboxHeightRatio)}, " +
                "proximityLevel=${detection.proximityLevel}, " +
                "riskLevel=${detection.riskLevel}, " +
                "priority=${detection.objectPriority}, " +
                "horizontalPosition=${detection.horizontalPosition}, " +
                "category=${detection.riskObjectCategory}, ignored=${detection.isIgnored}, " +
                "motionDirection=${detection.motionDirection}, " +
                "scenario=${detection.warningScenario}, " +
                "beepLevel=${detection.warningFeedback.beepLevel}, " +
                "vibrationLevel=${detection.warningFeedback.vibrationLevel}, " +
                "voiceLevel=${detection.warningFeedback.voiceLevel}, " +
                "message=${detection.warningFeedback.message}, " +
                "shouldNotify=${detection.warningFeedback.shouldNotify}"
        )
    }

    fun resolveObjectCategory(label: String): RiskObjectCategory {
        return when (normalize(label)) {
            "person" -> RiskObjectCategory.HUMAN_FLOW
            "car",
            "truck",
            "bus",
            "motorcycle",
            "bicycle" -> RiskObjectCategory.VEHICLE_RISK
            "traffic light",
            "stop sign" -> RiskObjectCategory.TRAFFIC_CONTROL
            "bench",
            "fire hydrant" -> RiskObjectCategory.STATIC_OBSTACLE
            "chair",
            "stairs",
            "curb",
            "low_obstacle",
            "bollard" -> RiskObjectCategory.TEMPORARY_OBSTACLE
            else -> RiskObjectCategory.UNKNOWN
        }
    }

    fun resolveObjectPriority(label: String): ObjectPriority {
        return when (normalize(label)) {
            "bicycle",
            "motorcycle",
            "car",
            "bus",
            "truck",
            "stairs",
            "curb",
            "low_obstacle" -> ObjectPriority.HIGH
            "person",
            "bollard",
            "bench",
            "fire hydrant",
            "chair" -> ObjectPriority.LOW
            else -> ObjectPriority.LOW
        }
    }

    fun resolveDetectionCategory(label: String): DetectionCategory {
        return when (normalize(label)) {
            "traffic light",
            "stop sign" -> DetectionCategory.SAFETY
            "car",
            "bus",
            "truck",
            "motorcycle",
            "bicycle" -> DetectionCategory.VEHICLE
            "chair",
            "bench",
            "fire hydrant" -> DetectionCategory.OBSTACLE
            "person" -> DetectionCategory.HUMAN
            else -> DetectionCategory.ETC
        }
    }

    fun resolveDetectionPriority(label: String): DetectionPriority {
        return when (normalize(label)) {
            "traffic light",
            "stop sign",
            "car",
            "bus",
            "truck",
            "motorcycle",
            "bicycle" -> DetectionPriority.HIGH
            "person",
            "bench",
            "fire hydrant",
            "parking meter" -> DetectionPriority.MEDIUM
            else -> DetectionPriority.LOW
        }
    }

    fun resolveProximityLevel(heightRatio: Float, areaRatio: Float): ProximityLevel {
        return when {
            heightRatio >= 0.35f || areaRatio >= 0.15f -> ProximityLevel.VERY_NEAR
            heightRatio >= 0.22f || areaRatio >= 0.07f -> ProximityLevel.NEAR
            heightRatio >= 0.12f || areaRatio >= 0.02f -> ProximityLevel.MID
            else -> ProximityLevel.FAR
        }
    }

    fun resolveRiskLevel(
        proximityLevel: ProximityLevel,
        category: RiskObjectCategory,
        priority: ObjectPriority,
        horizontalPosition: HorizontalPosition
    ): RiskLevel {
        if (category == RiskObjectCategory.VEHICLE_RISK && proximityLevel == ProximityLevel.VERY_NEAR) {
            return RiskLevel.CRITICAL
        }
        if (category == RiskObjectCategory.HUMAN_FLOW && proximityLevel == ProximityLevel.FAR) {
            return RiskLevel.NONE
        }
        if (category == RiskObjectCategory.STATIC_OBSTACLE && proximityLevel == ProximityLevel.FAR) {
            return RiskLevel.NONE
        }

        val baseRisk = baseRisk(proximityLevel)
        val positionAdjustedRisk = if (horizontalPosition != HorizontalPosition.CENTER && proximityLevel != ProximityLevel.VERY_NEAR) {
            decrease(baseRisk)
        } else {
            baseRisk
        }

        return applyPriorityRisk(positionAdjustedRisk, priority, proximityLevel)
    }

    fun resolveScenario(detection: DetectionResult): WarningScenario {
        return when {
            detection.riskLevel == RiskLevel.CRITICAL -> WarningScenario.IMMEDIATE_DANGER
            detection.riskObjectCategory == RiskObjectCategory.VEHICLE_RISK &&
                detection.motionDirection == MotionDirection.APPROACHING &&
                isNearOrCloser(detection.proximityLevel) -> WarningScenario.APPROACHING_OBJECT
            detection.riskObjectCategory == RiskObjectCategory.VEHICLE_RISK &&
                detection.objectPriority == ObjectPriority.HIGH &&
                isNearOrCloser(detection.proximityLevel) -> WarningScenario.APPROACHING_OBJECT
            (detection.riskObjectCategory == RiskObjectCategory.STATIC_OBSTACLE ||
                detection.riskObjectCategory == RiskObjectCategory.TEMPORARY_OBSTACLE) &&
                detection.horizontalPosition == HorizontalPosition.CENTER &&
                isNearOrCloser(detection.proximityLevel) -> WarningScenario.FRONT_OBSTACLE
            detection.riskObjectCategory == RiskObjectCategory.TRAFFIC_CONTROL -> WarningScenario.TRAFFIC_INFO
            else -> WarningScenario.MONITORING
        }
    }

    fun resolveScenario(crowdLevel: CrowdLevel): WarningScenario {
        return when (crowdLevel) {
            CrowdLevel.HIGH,
            CrowdLevel.MEDIUM -> WarningScenario.CROWD
            CrowdLevel.LOW,
            CrowdLevel.NONE -> WarningScenario.MONITORING
        }
    }

    fun buildWarningFeedback(detection: DetectionResult): WarningFeedback {
        return buildScenarioFeedback(detection)
    }

    fun buildCrowdFeedback(
        crowdLevel: CrowdLevel,
        riskLevel: RiskLevel,
        message: String?
    ): WarningFeedback {
        return when (crowdLevel) {
            CrowdLevel.HIGH -> WarningFeedback(
                riskLevel = riskLevel,
                beepLevel = FeedbackLevel.MEDIUM,
                vibrationLevel = FeedbackLevel.MEDIUM,
                voiceLevel = FeedbackLevel.NONE,
                message = message ?: "전방 혼잡",
                shouldNotify = true
            )
            CrowdLevel.MEDIUM -> WarningFeedback(
                riskLevel = riskLevel,
                beepLevel = FeedbackLevel.NONE,
                vibrationLevel = FeedbackLevel.LOW,
                voiceLevel = FeedbackLevel.NONE,
                message = message ?: "전방 사람 많음",
                shouldNotify = true
            )
            CrowdLevel.LOW,
            CrowdLevel.NONE -> WarningFeedback.NONE
        }
    }

    private fun buildScenarioFeedback(detection: DetectionResult): WarningFeedback {
        val scenario = detection.warningScenario
        val message = buildScenarioMessage(detection)
        val feedback = when (scenario) {
            WarningScenario.IMMEDIATE_DANGER -> WarningFeedback(
                riskLevel = detection.riskLevel,
                beepLevel = FeedbackLevel.HIGH,
                vibrationLevel = FeedbackLevel.HIGH,
                voiceLevel = FeedbackLevel.HIGH,
                message = message,
                shouldNotify = message != null
            )
            WarningScenario.APPROACHING_OBJECT -> {
                val beepLevel = if (detection.objectPriority == ObjectPriority.HIGH) FeedbackLevel.HIGH else FeedbackLevel.MEDIUM
                val vibrationLevel = if (detection.objectPriority == ObjectPriority.HIGH) FeedbackLevel.HIGH else FeedbackLevel.MEDIUM
                WarningFeedback(
                    riskLevel = detection.riskLevel,
                    beepLevel = beepLevel,
                    vibrationLevel = vibrationLevel,
                    voiceLevel = FeedbackLevel.MEDIUM,
                    message = message,
                    shouldNotify = message != null
                )
            }
            WarningScenario.FRONT_OBSTACLE -> {
                val level = if (detection.proximityLevel == ProximityLevel.VERY_NEAR) {
                    FeedbackLevel.HIGH
                } else {
                    FeedbackLevel.MEDIUM
                }
                WarningFeedback(
                    riskLevel = detection.riskLevel,
                    beepLevel = level,
                    vibrationLevel = level,
                    voiceLevel = level,
                    message = message,
                    shouldNotify = message != null
                )
            }
            WarningScenario.CROWD -> WarningFeedback.NONE
            WarningScenario.TRAFFIC_INFO -> WarningFeedback(
                riskLevel = detection.riskLevel,
                beepLevel = FeedbackLevel.NONE,
                vibrationLevel = FeedbackLevel.LOW,
                voiceLevel = FeedbackLevel.NONE,
                message = message,
                shouldNotify = message != null
            )
            WarningScenario.MONITORING -> WarningFeedback.NONE
        }

        if (detection.isIgnored || detection.riskLevel == RiskLevel.NONE) {
            return feedback.copy(
                beepLevel = FeedbackLevel.NONE,
                vibrationLevel = FeedbackLevel.NONE,
                voiceLevel = FeedbackLevel.NONE,
                shouldNotify = false
            )
        }
        return feedback
    }

    fun buildWarningFeedback(
        label: String,
        category: VisionObjectCategory,
        priority: ObjectPriority,
        riskLevel: RiskLevel
    ): WarningFeedback {
        val message = buildWarningMessage(label, category, riskLevel, priority)
        if (priority == ObjectPriority.HIGH) {
            return buildHighPriorityFeedback(riskLevel, message)
        }

        return when (riskLevel) {
            RiskLevel.CRITICAL -> WarningFeedback(
                riskLevel = riskLevel,
                beepLevel = FeedbackLevel.HIGH,
                vibrationLevel = FeedbackLevel.HIGH,
                voiceLevel = FeedbackLevel.HIGH,
                message = message,
                shouldNotify = true
            )
            RiskLevel.HIGH -> WarningFeedback(
                riskLevel = riskLevel,
                beepLevel = FeedbackLevel.MEDIUM,
                vibrationLevel = FeedbackLevel.MEDIUM,
                voiceLevel = FeedbackLevel.MEDIUM,
                message = message,
                shouldNotify = true
            )
            RiskLevel.MEDIUM -> WarningFeedback(
                riskLevel = riskLevel,
                beepLevel = FeedbackLevel.LOW,
                vibrationLevel = FeedbackLevel.LOW,
                voiceLevel = FeedbackLevel.LOW,
                message = message,
                shouldNotify = true
            )
            RiskLevel.LOW,
            RiskLevel.NONE -> WarningFeedback(
                riskLevel = riskLevel,
                beepLevel = FeedbackLevel.NONE,
                vibrationLevel = FeedbackLevel.NONE,
                voiceLevel = FeedbackLevel.NONE,
                message = null,
                shouldNotify = false
            )
        }
    }

    fun buildWarningMessage(
        label: String,
        category: VisionObjectCategory,
        riskLevel: RiskLevel,
        priority: ObjectPriority = ObjectPriority.LOW
    ): String? {
        if (riskLevel == RiskLevel.LOW || riskLevel == RiskLevel.NONE) return null
        return when (priority) {
            ObjectPriority.HIGH -> buildHighPriorityMessage(label, riskLevel)
            ObjectPriority.LOW -> buildLowPriorityMessage(label, category, riskLevel)
        }
    }

    fun resolveCrowdDecision(detections: List<DetectionResult>): CrowdDecision {
        val personDetections = detections.filter { normalize(it.label) == "person" }
        val totalPersonCount = personDetections.size
        val centerPersonCount = personDetections.count { it.horizontalPosition == HorizontalPosition.CENTER }
        val nearPersonCount = personDetections.count {
            it.proximityLevel == ProximityLevel.NEAR || it.proximityLevel == ProximityLevel.VERY_NEAR
        }
        val crowdLevel = when {
            totalPersonCount == 0 -> CrowdLevel.NONE
            centerPersonCount >= 3 -> CrowdLevel.HIGH
            nearPersonCount >= 3 -> CrowdLevel.HIGH
            centerPersonCount >= 2 && nearPersonCount >= 2 -> CrowdLevel.HIGH
            totalPersonCount >= 3 -> CrowdLevel.MEDIUM
            centerPersonCount >= 1 -> CrowdLevel.MEDIUM
            else -> CrowdLevel.LOW
        }
        val message = when (crowdLevel) {
            CrowdLevel.HIGH -> "전방 혼잡"
            CrowdLevel.MEDIUM -> "전방 사람 많음"
            CrowdLevel.LOW,
            CrowdLevel.NONE -> null
        }

        return CrowdDecision(
            crowdLevel = crowdLevel,
            totalPersonCount = totalPersonCount,
            centerPersonCount = centerPersonCount,
            nearPersonCount = nearPersonCount,
            message = message,
            shouldNotify = crowdLevel == CrowdLevel.HIGH || crowdLevel == CrowdLevel.MEDIUM
        )
    }

    fun labelToKorean(label: String): String {
        return when (normalize(label)) {
            "person" -> "사람"
            "bicycle" -> "자전거"
            "car" -> "자동차"
            "motorcycle" -> "오토바이"
            "bus" -> "버스"
            "truck" -> "트럭"
            "traffic light" -> "신호등"
            "stop sign" -> "정지 표지판"
            "bench" -> "벤치"
            "fire hydrant" -> "소화전"
            "chair" -> "의자"
            "bollard" -> "볼라드"
            "stairs" -> "계단"
            "curb" -> "단차"
            "low_obstacle" -> "낮은 장애물"
            else -> "객체"
        }
    }

    private fun buildScenarioMessage(detection: DetectionResult): String? {
        val label = normalize(detection.label)
        return when (detection.warningScenario) {
            WarningScenario.IMMEDIATE_DANGER -> buildImmediateDangerMessage(label)
            WarningScenario.APPROACHING_OBJECT -> buildApproachingObjectMessage(label)
            WarningScenario.FRONT_OBSTACLE -> {
                if (detection.proximityLevel == ProximityLevel.VERY_NEAR) "정지! 장애물" else "전방 장애물"
            }
            WarningScenario.CROWD -> null
            WarningScenario.TRAFFIC_INFO -> buildTrafficInfoMessage(label)
            WarningScenario.MONITORING -> null
        }
    }

    private fun buildImmediateDangerMessage(label: String): String {
        return when (label) {
            "car",
            "bus",
            "truck" -> "정지! 차량"
            "motorcycle" -> "정지! 오토바이"
            "bicycle" -> "정지! 자전거"
            "person" -> "정지! 사람"
            "stairs" -> "정지! 계단"
            "curb" -> "정지! 단차"
            "low_obstacle",
            "bollard",
            "bench",
            "fire hydrant",
            "chair" -> "정지! 장애물"
            else -> "정지!"
        }
    }

    private fun buildApproachingObjectMessage(label: String): String {
        return when (label) {
            "car",
            "bus",
            "truck" -> "차량 접근"
            "motorcycle" -> "오토바이 접근"
            "bicycle" -> "자전거 접근"
            "person" -> "사람 접근"
            else -> "전방 접근"
        }
    }

    private fun buildTrafficInfoMessage(label: String): String? {
        return when (label) {
            "traffic light" -> "전방 신호등"
            "stop sign" -> "전방 정지 표지판"
            else -> null
        }
    }

    private fun buildHighPriorityFeedback(
        riskLevel: RiskLevel,
        message: String?
    ): WarningFeedback {
        return when (riskLevel) {
            RiskLevel.CRITICAL -> WarningFeedback(
                riskLevel = riskLevel,
                beepLevel = FeedbackLevel.HIGH,
                vibrationLevel = FeedbackLevel.HIGH,
                voiceLevel = FeedbackLevel.HIGH,
                message = message,
                shouldNotify = true
            )
            RiskLevel.HIGH -> WarningFeedback(
                riskLevel = riskLevel,
                beepLevel = FeedbackLevel.HIGH,
                vibrationLevel = FeedbackLevel.HIGH,
                voiceLevel = FeedbackLevel.MEDIUM,
                message = message,
                shouldNotify = true
            )
            RiskLevel.MEDIUM -> WarningFeedback(
                riskLevel = riskLevel,
                beepLevel = FeedbackLevel.MEDIUM,
                vibrationLevel = FeedbackLevel.MEDIUM,
                voiceLevel = FeedbackLevel.MEDIUM,
                message = message,
                shouldNotify = true
            )
            RiskLevel.LOW,
            RiskLevel.NONE -> WarningFeedback(
                riskLevel = riskLevel,
                beepLevel = FeedbackLevel.NONE,
                vibrationLevel = FeedbackLevel.NONE,
                voiceLevel = FeedbackLevel.NONE,
                message = null,
                shouldNotify = false
            )
        }
    }

    private fun buildHighPriorityMessage(label: String, riskLevel: RiskLevel): String? {
        val normalized = normalize(label)
        return when (riskLevel) {
            RiskLevel.CRITICAL -> when (normalized) {
                "bicycle" -> "정지! 자전거"
                "motorcycle" -> "정지! 오토바이"
                "car",
                "bus",
                "truck" -> "정지! 차량"
                "stairs" -> "정지! 계단"
                "curb" -> "정지! 단차"
                "low_obstacle" -> "정지! 장애물"
                else -> "정지!"
            }
            RiskLevel.HIGH,
            RiskLevel.MEDIUM -> when (normalized) {
                "bicycle" -> "자전거 주의"
                "motorcycle" -> "오토바이 주의"
                "car",
                "bus",
                "truck" -> "차량 주의"
                "stairs" -> "계단 주의"
                "curb" -> "단차 주의"
                "low_obstacle" -> "낮은 장애물 주의"
                else -> "전방 주의"
            }
            RiskLevel.LOW,
            RiskLevel.NONE -> null
        }
    }

    private fun buildLowPriorityMessage(
        label: String,
        category: VisionObjectCategory,
        riskLevel: RiskLevel
    ): String? {
        val normalized = normalize(label)
        return when (riskLevel) {
            RiskLevel.CRITICAL -> when {
                normalized == "person" -> "정지! 사람"
                isLowPriorityObstacle(normalized, category) -> "정지! 장애물"
                else -> "정지!"
            }
            RiskLevel.HIGH,
            RiskLevel.MEDIUM -> when {
                normalized == "person" -> "전방 사람"
                isLowPriorityObstacle(normalized, category) -> "전방 장애물"
                else -> "전방 주의"
            }
            RiskLevel.LOW,
            RiskLevel.NONE -> null
        }
    }

    private fun isLowPriorityObstacle(
        label: String,
        category: VisionObjectCategory
    ): Boolean {
        return label == "bollard" ||
            label == "bench" ||
            label == "fire hydrant" ||
            label == "chair" ||
            category == RiskObjectCategory.STATIC_OBSTACLE ||
            category == RiskObjectCategory.TEMPORARY_OBSTACLE
    }

    private fun resolveHorizontalPosition(centerXRatio: Float): HorizontalPosition {
        return when {
            centerXRatio < LEFT_CENTER_BOUNDARY -> HorizontalPosition.LEFT
            centerXRatio > RIGHT_CENTER_BOUNDARY -> HorizontalPosition.RIGHT
            else -> HorizontalPosition.CENTER
        }
    }

    private fun baseRisk(proximityLevel: ProximityLevel): RiskLevel {
        return when (proximityLevel) {
            ProximityLevel.VERY_NEAR -> RiskLevel.CRITICAL
            ProximityLevel.NEAR -> RiskLevel.HIGH
            ProximityLevel.MID -> RiskLevel.MEDIUM
            ProximityLevel.FAR -> RiskLevel.LOW
        }
    }

    private fun applyPriorityRisk(
        riskLevel: RiskLevel,
        priority: ObjectPriority,
        proximityLevel: ProximityLevel
    ): RiskLevel {
        if (priority != ObjectPriority.HIGH) return riskLevel

        return when (proximityLevel) {
            ProximityLevel.VERY_NEAR,
            ProximityLevel.NEAR -> RiskLevel.CRITICAL
            ProximityLevel.MID -> maxOf(riskLevel, RiskLevel.HIGH)
            ProximityLevel.FAR -> riskLevel
        }
    }

    private fun isNearOrCloser(proximityLevel: ProximityLevel): Boolean {
        return proximityLevel == ProximityLevel.NEAR || proximityLevel == ProximityLevel.VERY_NEAR
    }

    private fun decrease(riskLevel: RiskLevel): RiskLevel {
        return when (riskLevel) {
            RiskLevel.NONE,
            RiskLevel.LOW -> RiskLevel.NONE
            RiskLevel.MEDIUM -> RiskLevel.LOW
            RiskLevel.HIGH -> RiskLevel.MEDIUM
            RiskLevel.CRITICAL -> RiskLevel.HIGH
        }
    }

    private fun normalize(label: String): String {
        return label.trim().lowercase(Locale.US)
    }

    private fun format(value: Float): String {
        return String.format(Locale.US, "%.3f", value)
    }

    private const val LEFT_CENTER_BOUNDARY = 1f / 3f
    private const val RIGHT_CENTER_BOUNDARY = 2f / 3f
    private const val TAG = "WarningPolicy"
}
