package com.samin.objectdetection.warning

import org.junit.Assert.assertEquals
import org.junit.Test
import com.samin.objectdetection.detector.DetectionResult

class WarningCandidateSelectorCrowdTest {

    private val selector = WarningCandidateSelector()

    @Test fun warningCandidatesHonorClassConfidenceWithoutChangingOverlayDetections() {
        val feedback = candidate("car", ObjectPriority.HIGH, RiskLevel.HIGH).feedback
        val lowCar = DetectionResult("car", 0.30f, 0f, 0f, 20f, 40f, centerYRatio = 0.5f, warningFeedback = feedback)
        val validCar = lowCar.copy(confidence = 0.50f)
        val validBollard = lowCar.copy(label = "bollard", confidence = 0.20f)
        val overlays = listOf(lowCar, validCar, validBollard)
        val candidates = selector.eligibleCandidates(overlays, 0.25f, 10) { it.label }
        assertEquals(3, overlays.size)
        assertEquals(setOf("car", "bollard"), candidates.map { it.label }.toSet())
        assertEquals(2, candidates.size)
    }

    @Test fun candidateLimitKeepsUrgentObjectEvenBehindTwoHigherConfidenceObjects() {
        val low = candidate("person", ObjectPriority.LOW, RiskLevel.LOW).copy(confidence = 0.99f)
        val urgent = candidate("car", ObjectPriority.HIGH, RiskLevel.CRITICAL).copy(confidence = 0.60f)
        val ranked = selector.ranked(listOf(low, low.copy(label = "bench"), urgent), 2)
        assertEquals("car", ranked.first().label)
        assertEquals("car", selector.select(ranked)?.label)
    }

    @Test
    fun selectWithCrowd_keepsHighPriorityUrgentObjectBeforeCrowd() {
        val objectCandidate = candidate(
            label = "car",
            priority = ObjectPriority.HIGH,
            riskLevel = RiskLevel.HIGH
        )
        val crowdCandidate = crowdCandidate(RiskLevel.HIGH)

        val selected = selector.selectWithCrowd(listOf(objectCandidate), crowdCandidate)

        assertEquals("car", selected?.label)
    }

    @Test
    fun selectWithCrowd_allowsCrowdWhenHighPriorityUrgentObjectIsAbsent() {
        val objectCandidate = candidate(
            label = "person",
            priority = ObjectPriority.LOW,
            riskLevel = RiskLevel.LOW,
            shouldNotify = false
        )
        val crowdCandidate = crowdCandidate(RiskLevel.MEDIUM)

        val selected = selector.selectWithCrowd(listOf(objectCandidate), crowdCandidate)

        assertEquals("crowd", selected?.label)
    }

    private fun crowdCandidate(riskLevel: RiskLevel): WarningCandidate {
        return candidate(
            label = "crowd",
            priority = ObjectPriority.LOW,
            riskLevel = riskLevel,
            message = if (riskLevel == RiskLevel.HIGH) "전방 혼잡" else "전방 사람 많음",
            warningKey = "crowd_${riskLevel.name}"
        )
    }

    private fun candidate(
        label: String,
        priority: ObjectPriority,
        riskLevel: RiskLevel,
        shouldNotify: Boolean = true,
        message: String? = label,
        warningKey: String = label
    ): WarningCandidate {
        return WarningCandidate(
            label = label,
            confidence = 0.9f,
            priority = priority,
            proximityLevel = ProximityLevel.NEAR,
            riskLevel = riskLevel,
            horizontalPosition = HorizontalPosition.CENTER,
            feedback = WarningFeedback(
                riskLevel = riskLevel,
                beepLevel = FeedbackLevel.NONE,
                vibrationLevel = FeedbackLevel.NONE,
                voiceLevel = FeedbackLevel.NONE,
                message = message,
                shouldNotify = shouldNotify
            ),
            warningKey = warningKey
        )
    }
}
