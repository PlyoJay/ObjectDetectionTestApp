package com.samin.objectdetection.warning

import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.motion.MotionDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class WarningPolicyVehicleScenarioTest {

    @Test
    fun stableNearHighPriorityVehicle_isFrontVehicleWithoutCriticalRisk() {
        val detection = vehicleDetection(
            proximityLevel = ProximityLevel.NEAR,
            motionDirection = MotionDirection.STABLE
        )

        val result = WarningPolicy.applyScenarioFeedback(detection)

        assertEquals(RiskLevel.HIGH, result.riskLevel)
        assertEquals(WarningScenario.FRONT_VEHICLE, result.warningScenario)
        assertEquals("전방 차량", result.warningFeedback.message)
        assertEquals(FeedbackLevel.MEDIUM, result.warningFeedback.beepLevel)
        assertEquals(FeedbackLevel.MEDIUM, result.warningFeedback.vibrationLevel)
        assertEquals(FeedbackLevel.NONE, result.warningFeedback.voiceLevel)
    }

    @Test
    fun approachingNearHighPriorityVehicle_isCriticalApproachingObject() {
        val detection = vehicleDetection(
            proximityLevel = ProximityLevel.NEAR,
            motionDirection = MotionDirection.APPROACHING
        )

        val result = WarningPolicy.applyScenarioFeedback(detection)

        assertEquals(RiskLevel.CRITICAL, result.riskLevel)
        assertEquals(WarningScenario.APPROACHING_OBJECT, result.warningScenario)
        assertEquals("차량 접근", result.warningFeedback.message)
        assertEquals(FeedbackLevel.HIGH, result.warningFeedback.beepLevel)
        assertEquals(FeedbackLevel.HIGH, result.warningFeedback.vibrationLevel)
        assertEquals(FeedbackLevel.MEDIUM, result.warningFeedback.voiceLevel)
    }

    @Test
    fun centerVeryNearHighPriorityVehicle_isImmediateDanger() {
        val detection = vehicleDetection(
            proximityLevel = ProximityLevel.VERY_NEAR,
            motionDirection = MotionDirection.UNKNOWN
        )

        val result = WarningPolicy.applyScenarioFeedback(detection)

        assertEquals(RiskLevel.CRITICAL, result.riskLevel)
        assertEquals(WarningScenario.IMMEDIATE_DANGER, result.warningScenario)
        assertEquals("정지! 차량", result.warningFeedback.message)
    }

    private fun vehicleDetection(
        proximityLevel: ProximityLevel,
        motionDirection: MotionDirection,
        horizontalPosition: HorizontalPosition = HorizontalPosition.CENTER
    ): DetectionResult {
        return DetectionResult(
            label = "car",
            confidence = 0.9f,
            left = 0f,
            top = 0f,
            right = 100f,
            bottom = 100f,
            motionDirection = motionDirection,
            horizontalPosition = horizontalPosition,
            riskObjectCategory = RiskObjectCategory.VEHICLE_RISK,
            objectPriority = ObjectPriority.HIGH,
            proximityLevel = proximityLevel,
            isIgnored = false
        )
    }
}
