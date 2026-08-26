package com.samin.objectdetection.policy

import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.warning.WarningPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BollardDetectionPolicyTest {

    @Test
    fun detectorAndBollardPolicyShareConfiguredConfidenceThreshold() {
        val config = DetectionConfig()
        val bollardPolicy = ObjectTuningPolicyRegistry.get("bollard")

        assertEquals(0.20f, config.confidenceThreshold)
        assertEquals(0.45f, config.nmsThreshold)
        assertEquals(config.confidenceThreshold, bollardPolicy?.minConfidence)
    }

    @Test
    fun warningPolicyDoesNotApplyAnotherConfidenceFilter() {
        val evaluated = WarningPolicy.evaluate(
            detection = DetectionResult(
                label = "bollard",
                confidence = 0.01f,
                left = 10f,
                top = 10f,
                right = 110f,
                bottom = 210f
            ),
            frameWidth = 640,
            frameHeight = 640
        )

        assertFalse(evaluated.isIgnored)
    }
}
