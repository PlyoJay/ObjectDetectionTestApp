package com.samin.objectdetection.evaluation

import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.distance.CameraIntrinsics
import com.samin.objectdetection.distance.DistanceEstimate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DistanceJsonTest {
    @Test fun manualTruthErrorsAndFilterMetadataAreAdditiveAndNeverSynthesizedWithoutTruth() {
        val depth = com.samin.objectdetection.distance.ObjectDepthEstimate(2.4f, 2.2f,
            com.samin.objectdetection.distance.DepthMethod.FULL, 42, "INTERIOR_CONSISTENT", 100, 95, 1000, "TRACKING",
            groundTruthMeters = 2f, groundTruthStatus = "UNIQUE_TARGET", filterStatus = "MEDIAN", updateDelayMs = 250)
        val json = JSONObject().putDistance(depth.applyTo(box))
        assertEquals(.4, json.getDouble("absoluteErrorMeters"), .0001)
        assertEquals(20.0, json.getDouble("relativeErrorPercent"), .001)
        assertEquals(.2, json.getDouble("filteredAbsoluteErrorMeters"), .0001)
        assertEquals("CAMERA_FORWARD_Z", json.getString("groundTruthDefinition"))
        assertEquals("bollard", json.getString("objectLabel"))
        val absent = JSONObject().putDistance(depth.copy(groundTruthMeters = null).applyTo(box))
        assertTrue(absent.isNull("absoluteErrorMeters")); assertTrue(absent.isNull("groundTruthMeters"))
        assertTrue(JSONObject().putDistance(depth.copy(groundTruthMeters = Float.MIN_VALUE).applyTo(box)).isNull("relativeErrorPercent"))
    }
    private val box = DetectionResult("bollard", .9f, 1f, 2f, 3f, 4f)
    @Test fun distanceKeysAreAdditiveAndInvalidOrMissingDistanceUsesJsonNull() {
        val estimate = DistanceEstimate(2.3f, status = "VALID", intrinsics = CameraIntrinsics(800f, 800f, 320f, 240f, "MANUAL_FOV"))
        val json = JSONObject().put("label", box.label).putDistance(estimate.applyTo(box))
        assertEquals("bollard", json.getString("label"))
        assertEquals(2.3, json.getDouble("estimatedDistanceMeters"), .0001)
        assertTrue(json.getBoolean("distanceEstimationValid"))
        assertEquals("GROUND_PLANE", json.getString("distanceEstimationMethod"))
        assertEquals("MANUAL_FOV", json.getString("distanceOpticsSource"))
        assertEquals(800.0, json.getJSONObject("distanceIntrinsics").getDouble("fy"), 0.0)
        for (value in listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val invalid = JSONObject().putDistance(box.copy(estimatedDistanceMeters = value))
            assertTrue(invalid.isNull("estimatedDistanceMeters"))
            assertFalse(invalid.getBoolean("distanceEstimationValid"))
        }
        val invalid = JSONObject().putDistance(DistanceEstimate(status = "CONTACT_CLIPPED").applyTo(box))
        assertEquals("CONTACT_CLIPPED", invalid.getString("distanceEstimationStatus"))
    }
    @Test fun arCoreRawAndFullSourcesPreserveOriginalValuesStatusTimestampsAndLegacyFields() {
        for (method in com.samin.objectdetection.distance.DepthMethod.entries) {
            val depth = com.samin.objectdetection.distance.ObjectDepthEstimate(2.4f, 2.3f, method, 42,
                "INTERIOR_CONSISTENT", 100, 95, 1000, "TRACKING")
            val json = JSONObject().put("label", box.label).putDistance(depth.applyTo(box))
            assertEquals("ARCORE_DEPTH", json.getString("distanceEstimationMethod"))
            assertEquals("CAMERA_FORWARD_Z", json.getString("distanceDefinition"))
            assertEquals(method.name, json.getString("depthMethod"))
            assertEquals(2.4, json.getDouble("rawDepthMeters"), .0001)
            assertEquals(2.3, json.getDouble("estimatedDistanceMeters"), .0001)
            assertEquals(95, json.getLong("depthTimestampNs")); assertEquals(42, json.getInt("depthSampleCount"))
            assertTrue(json.isNull("distanceIntrinsics"))
            val missing = JSONObject().putDistance(depth.copy(rawMeters = null, meters = null, status = "AMBIGUOUS_CLUSTERS").applyTo(box))
            assertTrue(missing.isNull("estimatedDistanceMeters")); assertFalse(missing.getBoolean("distanceEstimationValid"))
        }
    }
}
