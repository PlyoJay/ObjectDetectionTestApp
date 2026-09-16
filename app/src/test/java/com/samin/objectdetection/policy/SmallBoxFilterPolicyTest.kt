package com.samin.objectdetection.policy

import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.camera.SizeFilterMode
import com.samin.objectdetection.detector.DetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmallBoxFilterPolicyTest {

    private val tinyBollard = DetectionResult(
        label = "bollard",
        confidence = 0.9f,
        left = 100f,
        top = 100f,
        right = 101f,
        bottom = 101f
    )

    @Test
    fun disabledKeepsDetectionRegardlessOfSize() {
        val result = filter(SizeFilterMode.DISABLED)

        assertEquals(listOf(tinyBollard), result)
    }

    @Test
    fun relaxedStillRejectsExtremelySmallDetection() {
        assertTrue(filter(SizeFilterMode.RELAXED).isEmpty())
    }

    @Test
    fun normalPreservesExistingClassSpecificThresholds() {
        assertTrue(filter(SizeFilterMode.NORMAL).isEmpty())
    }

    private fun filter(mode: SizeFilterMode): List<DetectionResult> {
        return SmallBoxFilterPolicy.filter(
            detections = listOf(tinyBollard),
            frameWidth = 640,
            frameHeight = 640,
            config = DetectionConfig(
                sizeFilterMode = mode,
                enableDetectorDiagnostics = false
            )
        )
    }
}
