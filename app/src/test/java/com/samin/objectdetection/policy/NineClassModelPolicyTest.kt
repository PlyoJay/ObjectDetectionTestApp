package com.samin.objectdetection.policy

import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.camera.SizeFilterMode
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.ModelConfig
import com.samin.objectdetection.model.DetectionCategory
import com.samin.objectdetection.warning.FeedbackLevel
import com.samin.objectdetection.warning.RiskObjectCategory
import com.samin.objectdetection.warning.WarningPolicy
import org.junit.Assert.*
import org.junit.Test

class NineClassModelPolicyTest {
    private val labels = listOf("bollard", "person", "bicycle", "car", "motorcycle", "bus", "truck", "stop sign", "bench")

    @Test fun classIdsAndEnabledPoliciesMatchTheNineClassContract() {
        assertEquals(labels, ModelConfig.CLASS_LABELS)
        assertEquals(labels.toSet(), ObjectTuningPolicyRegistry.currentModelLabels)
        assertEquals(labels.toSet(), OverlayObjectFilter.allowedLabels)
        assertEquals(labels.toSet(), YoloDefaultPolicyRegistry.getAll().map { it.label }.toSet())
        val riskCategories = listOf(
            RiskObjectCategory.TEMPORARY_OBSTACLE, RiskObjectCategory.HUMAN_FLOW,
            RiskObjectCategory.VEHICLE_RISK, RiskObjectCategory.VEHICLE_RISK,
            RiskObjectCategory.VEHICLE_RISK, RiskObjectCategory.VEHICLE_RISK,
            RiskObjectCategory.VEHICLE_RISK, RiskObjectCategory.TRAFFIC_CONTROL,
            RiskObjectCategory.STATIC_OBSTACLE
        )
        val detectionCategories = listOf(
            DetectionCategory.OBSTACLE, DetectionCategory.HUMAN, DetectionCategory.VEHICLE,
            DetectionCategory.VEHICLE, DetectionCategory.VEHICLE, DetectionCategory.VEHICLE,
            DetectionCategory.VEHICLE, DetectionCategory.SAFETY, DetectionCategory.OBSTACLE
        )
        labels.forEachIndexed { index, label ->
            assertEquals(riskCategories[index], ObjectTuningPolicyRegistry.get(label)!!.category)
            assertEquals(riskCategories[index], WarningPolicy.resolveObjectCategory(label))
            assertEquals(detectionCategories[index], WarningPolicy.resolveDetectionCategory(label))
            assertEquals(detectionCategories[index].name, YoloDefaultPolicyRegistry.get(label)!!.category.name)
            assertTrue(OverlayObjectFilter.isAllowed(" ${label.uppercase()} "))
            assertTrue(YoloDefaultPolicyRegistry.isSupported(label))
        }
    }

    @Test fun reservedCustomPoliciesAreAvailableButNotActive() {
        assertEquals(setOf("stairs", "curb", "low_obstacle"), ObjectTuningPolicyRegistry.futureCustomLabels)
        ObjectTuningPolicyRegistry.futureCustomLabels.forEach { label ->
            assertNotNull(ObjectTuningPolicyRegistry.get(label))
            assertFalse(OverlayObjectFilter.isAllowed(label))
            assertFalse(ObjectTuningPolicyRegistry.shouldWarn(box(label, 1f)))
            assertNull(YoloDefaultPolicyRegistry.get(label))
        }
    }

    @Test fun legacyCocoLabelsHaveNoCurrentYoloPolicy() {
        listOf("traffic light", "fire hydrant", "chair", "backpack", "umbrella", "cell phone", "parking meter").forEach { label ->
            assertNull(ObjectTuningPolicyRegistry.get(label))
            assertFalse(OverlayObjectFilter.isAllowed(label))
            assertFalse(ObjectTuningPolicyRegistry.shouldWarn(box(label, 1f)))
            assertFalse(YoloDefaultPolicyRegistry.isSupported(label))
        }
    }

    @Test fun bollardGeometryLimitsDoNotRejectAnyOtherCurrentClass() {
        labels.filterNot { it == "bollard" }.forEach { label ->
            // Valid but tiny and very wide: deliberately fails both aggressive bollard limits.
            val detection = box(label, 0.9f).copy(left = 10f, top = 10f, right = 110f, bottom = 11f)
            assertTrue(BollardGeometryValidator.isValid(detection, 640, 640, minAreaRatio = 0.5f, maxWidthToHeightRatio = 0.1f))
            assertFalse(BollardGeometryValidator.isValid(detection.copy(label = "bollard"), 640, 640, 0.5f, 0.1f))
            val policy = ObjectTuningPolicyRegistry.get(label)!!
            assertNull(policy.minWidthRatio)
            assertNull(policy.minHeightRatio)
        }
    }

    @Test fun defaultSizeFilterKeepsAllClassesWhileWarningsUseTheirOwnConfidence() {
        val detections = labels.map { box(it, 0.2f) }
        val config = DetectionConfig()
        assertEquals(SizeFilterMode.DISABLED, config.sizeFilterMode)
        assertEquals(detections, SmallBoxFilterPolicy.filter(detections, 640, 640, config))
        assertTrue(ObjectTuningPolicyRegistry.shouldWarn(box("bollard", 0.2f)))
        labels.forEach { label ->
            val threshold = ObjectTuningPolicyRegistry.get(label)!!.minConfidence
            assertTrue(ObjectTuningPolicyRegistry.shouldWarn(box(label, threshold)))
            assertFalse(ObjectTuningPolicyRegistry.shouldWarn(box(label, threshold - 0.01f)))
            assertFalse(ObjectTuningPolicyRegistry.shouldWarn(box(label, 1f).copy(isIgnored = true)))
        }
    }

    @Test fun bollardTemporalConfirmationDoesNotDelayOtherCurrentClasses() {
        val validator = AdaptiveTemporalValidator(immediateConfidence = 0.6f, confirmationConfidence = 0.2f)
        val detections = labels.map { box(it, 0.3f) }
        assertEquals(detections.filterNot { it.label == "bollard" }, validator.filter(detections))
        assertEquals(detections, validator.filter(detections))
    }

    @Test fun personVoiceTuningKeepsBeepAndVibrationAndDoesNotMuteOtherClasses() {
        labels.forEach { label ->
            val evaluated = WarningPolicy.evaluate(box(label, 0.9f), 640, 640)
            val tuned = ObjectTuningPolicyRegistry.applyVoiceTuning(evaluated)
            if (label == "person") {
                assertEquals(FeedbackLevel.NONE, tuned.warningFeedback.voiceLevel)
                assertNull(tuned.warningFeedback.message)
                assertEquals(evaluated.warningFeedback.beepLevel, tuned.warningFeedback.beepLevel)
                assertEquals(evaluated.warningFeedback.vibrationLevel, tuned.warningFeedback.vibrationLevel)
            } else {
                assertEquals(evaluated, tuned)
            }
        }
    }

    private fun box(label: String, confidence: Float) = DetectionResult(
        label = label, confidence = confidence, left = 200f, top = 10f, right = 440f, bottom = 630f
    )
}
