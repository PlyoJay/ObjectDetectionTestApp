package com.samin.objectdetection.warning

import com.samin.objectdetection.detector.DetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningPolicyCrowdDecisionTest {

    @Test
    fun evaluate_returnsNone_whenPersonIsAbsent() {
        val decision = WarningPolicy.resolveCrowdDecision(
            listOf(detection(label = "car"))
        )

        assertEquals(CrowdLevel.NONE, decision.crowdLevel)
        assertEquals(0, decision.totalPersonCount)
        assertNull(decision.message)
        assertFalse(decision.shouldNotify)
    }

    @Test
    fun evaluate_returnsHigh_whenThreePeopleAreInCenter() {
        val decision = WarningPolicy.resolveCrowdDecision(
            listOf(
                detection(horizontalPosition = HorizontalPosition.CENTER),
                detection(horizontalPosition = HorizontalPosition.CENTER),
                detection(horizontalPosition = HorizontalPosition.CENTER)
            )
        )

        assertEquals(CrowdLevel.HIGH, decision.crowdLevel)
        assertEquals(3, decision.centerPersonCount)
        assertEquals("전방 혼잡", decision.message)
        assertTrue(decision.shouldNotify)
    }

    @Test
    fun evaluate_returnsMedium_whenThreePeopleAreDetected() {
        val decision = WarningPolicy.resolveCrowdDecision(
            listOf(
                detection(horizontalPosition = HorizontalPosition.LEFT),
                detection(horizontalPosition = HorizontalPosition.LEFT),
                detection(horizontalPosition = HorizontalPosition.RIGHT)
            )
        )

        assertEquals(CrowdLevel.MEDIUM, decision.crowdLevel)
        assertEquals(3, decision.totalPersonCount)
        assertEquals("전방 사람 많음", decision.message)
        assertTrue(decision.shouldNotify)
    }

    @Test
    fun evaluate_returnsLow_whenOnePersonIsNotCenter() {
        val decision = WarningPolicy.resolveCrowdDecision(
            listOf(detection(horizontalPosition = HorizontalPosition.LEFT))
        )

        assertEquals(CrowdLevel.LOW, decision.crowdLevel)
        assertEquals(1, decision.totalPersonCount)
        assertNull(decision.message)
        assertFalse(decision.shouldNotify)
    }

    private fun detection(
        label: String = "person",
        horizontalPosition: HorizontalPosition = HorizontalPosition.LEFT,
        proximityLevel: ProximityLevel = ProximityLevel.FAR
    ): DetectionResult {
        return DetectionResult(
            label = label,
            confidence = 0.9f,
            left = 0f,
            top = 0f,
            right = 10f,
            bottom = 10f,
            horizontalPosition = horizontalPosition,
            proximityLevel = proximityLevel
        )
    }
}
