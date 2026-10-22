package com.samin.objectdetection.evaluation

import com.samin.objectdetection.metrics.FrameTrace
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FrameTraceJsonTest {
    @Test fun successfulEmptyFrameAndFailedFrameHaveDifferentStatusAndNullableInferenceTime() {
        val empty = FrameTrace("session", 8, 1000, 0, false, 1000, 100,
            completedAtMs = 1100, completedElapsedNs = 200)
        val successJson = JSONObject().putTrace(empty)
        val failureJson = JSONObject().putTrace(empty.copy(status = "ERROR", errorStage = "PREPROCESS", error = "bad bitmap"))
        assertEquals("SUCCESS", successJson.getString("status"))
        assertEquals("ERROR", failureJson.getString("status"))
        assertEquals("PREPROCESS", failureJson.getString("errorStage"))
        assertTrue(failureJson.isNull("inferenceStartedElapsedNs"))
        assertEquals("session", failureJson.getString("sessionId")); assertEquals(8L, failureJson.getLong("frameId"))
        assertEquals(-1L, failureJson.getLong("frameAgeAtInferenceMs"))
        assertTrue(successJson.isNull("inferenceEndedElapsedNs"))
        assertEquals(200L, successJson.getLong("pipelineCompletedElapsedNs"))
        assertTrue(failureJson.isNull("pipelineCompletedElapsedNs"))
        assertEquals(150L, JSONObject().putTrace(empty.copy(inferenceEndedElapsedNs = 150))
            .getLong("inferenceEndedElapsedNs"))
    }
}
