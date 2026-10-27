package com.samin.objectdetection.arcore.yolo

import com.samin.objectdetection.detector.DetectionResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class ArYoloJsonLogTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun frame(meters: Float?) = ArYoloFrame(100, 100, 100, 1, 1_000_000_000, 1, "TRACKING", 100, 60, 100, 60,
        listOf(ArYoloObject(DetectionResult("cat", .9f, 10f, 12f, 70f, 42f), ImageBox(.1f, .2f, .7f, .7f),
            ArObjectDepth(meters, sampleCount = if (meters == null) 0 else 25, status = if (meters == null) "DEPTH_NOT_READY" else "BOTTOM_CENTER"))), 15)
    @Test fun sourceTimestampsBoxesAndRawAndDisplayedDistanceHaveExplicitDefinitions() {
        val json = ArYoloJsonLog.json(frame(2.3f))
        assertEquals("ARCORE_DEPTH", json.getString("distanceSource"))
        assertEquals("CAMERA_FORWARD_Z", json.getString("distanceDefinition"))
        val obj = json.getJSONArray("detections").getJSONObject(0)
        assertEquals("cat", obj.getString("label")); assertEquals("CPU_IMAGE_PIXELS", obj.getString("bboxSpace"))
        assertEquals(2.3, obj.getDouble("estimatedDistanceMeters"), 1e-6)
        assertEquals(25, obj.getInt("depthSampleCount"))
    }
    @Test fun missingDepthKeepsDetectionAndUsesJsonNullInsteadOfZero() {
        val obj = ArYoloJsonLog.json(frame(null)).getJSONArray("detections").getJSONObject(0)
        assertEquals("cat", obj.getString("label")); assertTrue(obj.isNull("estimatedDistanceMeters"))
    }
    @Test fun optInLoggingDrainsOnCloseAndSeparateSessionsDoNotAppendToOldCsvFiles() {
        val logger = ArYoloJsonLog(temporary.newFolder("arcore_yolo"))
        logger.append(frame(1f)); assertNull(logger.latestFile)
        logger.start(); val first = logger.latestFile!!
        repeat(3) { logger.append(frame(it + 1f)) }
        logger.stop().get(5, TimeUnit.SECONDS)
        assertFalse(logger.recording()); assertFalse(logger.saving()); assertFalse(logger.failed())
        assertEquals(3, first.readLines().size)
        first.readLines().forEach { assertEquals("EXPERIMENTAL_YOLO_ARCORE", JSONObject(it).getString("mode")) }
        logger.start(); assertNotEquals(first, logger.latestFile)
        logger.stop().get(5, TimeUnit.SECONDS)
        assertEquals(3, first.readLines().size)
    }
}
