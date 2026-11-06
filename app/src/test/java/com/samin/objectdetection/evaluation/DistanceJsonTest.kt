package com.samin.objectdetection.evaluation

import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.distance.CameraIntrinsics
import com.samin.objectdetection.distance.DistanceEstimate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DistanceJsonTest {
    private val box = DetectionResult("cardboard_box", .9f, 1f, 2f, 3f, 4f)

    @Test fun unavailableAndInvalidDistanceAreNullNeverZero() {
        for (value in listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val json = JSONObject().put("label", box.label).putDistance(box.copy(estimatedDistanceMeters = value))
            assertEquals("cardboard_box", json.getString("label"))
            assertTrue(json.isNull("estimatedDistanceMeters"))
            assertFalse(json.getBoolean("distanceEstimationValid"))
            assertEquals("NOT_SUPPORTED", json.getString("distanceEstimationStatus"))
            assertTrue(json.isNull("distanceEstimationMethod"))
        }
    }

    @Test fun legacyGroundPlaneSerializationRemainsIndependentOfCameraPipeline() {
        val estimate = DistanceEstimate(2.3f, status = "VALID", intrinsics = CameraIntrinsics(800f, 800f, 320f, 240f, "MANUAL_FOV"))
        val json = JSONObject().putDistance(estimate.applyTo(box))
        assertEquals(2.3, json.getDouble("estimatedDistanceMeters"), .0001)
        assertTrue(json.getBoolean("distanceEstimationValid"))
        assertEquals("GROUND_PLANE", json.getString("distanceEstimationMethod"))
        assertEquals("MANUAL_FOV", json.getString("distanceOpticsSource"))
        assertEquals(800.0, json.getJSONObject("distanceIntrinsics").getDouble("fy"), 0.0)
    }
}
