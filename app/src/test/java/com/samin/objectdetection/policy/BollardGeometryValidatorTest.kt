package com.samin.objectdetection.policy

import com.samin.objectdetection.detector.DetectionResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BollardGeometryValidatorTest {
    @Test
    fun acceptsPlausibleBollard() {
        assertTrue(BollardGeometryValidator.isValid(box("bollard", 100f, 50f, 150f, 300f), 640, 480))
    }

    @Test
    fun rejectsClearlyFlatOrOutOfFrameBollard() {
        assertFalse(BollardGeometryValidator.isValid(box("bollard", 10f, 20f, 400f, 40f), 640, 480))
        assertFalse(BollardGeometryValidator.isValid(box("bollard", -1f, 20f, 40f, 200f), 640, 480))
    }

    @Test
    fun doesNotFilterOtherClasses() {
        assertTrue(BollardGeometryValidator.isValid(box("person", -1f, 0f, 0f, 0f), 640, 480))
    }

    private fun box(label: String, left: Float, top: Float, right: Float, bottom: Float) =
        DetectionResult(label, 0.6f, left, top, right, bottom)
}
