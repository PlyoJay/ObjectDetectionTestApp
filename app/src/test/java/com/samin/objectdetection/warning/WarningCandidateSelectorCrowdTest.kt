package com.samin.objectdetection.warning

import org.junit.Assert.assertEquals
import org.junit.Test

class WarningCandidateSelectorCrowdTest {

    private val selector = WarningCandidateSelector()

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
