package com.samin.objectdetection.policy

import com.samin.objectdetection.detector.DetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveTemporalValidatorTest {
    private val validator = AdaptiveTemporalValidator(0.75f, 0.50f)

    @Test
    fun highConfidenceIsImmediate() {
        assertEquals(1, validator.filter(listOf(box(0.80f))).size)
    }

    @Test
    fun mediumConfidenceNeedsOneMatchingFrame() {
        assertTrue(validator.filter(listOf(box(0.60f))).isEmpty())
        assertEquals(1, validator.filter(listOf(box(0.60f, 2f))).size)
    }

    @Test
    fun lowConfidenceKeepsDetectorPolicyBehavior() {
        assertEquals(1, validator.filter(listOf(box(0.30f))).size)
    }

    @Test
    fun iouHandlesDisjointBoxes() {
        assertEquals(0f, AdaptiveTemporalValidator.iou(box(0.6f), box(0.6f, 200f)))
    }

    private fun box(confidence: Float, offset: Float = 0f) = DetectionResult(
        label = "bollard",
        confidence = confidence,
        left = 10f + offset,
        top = 10f,
        right = 60f + offset,
        bottom = 160f
    )
}
