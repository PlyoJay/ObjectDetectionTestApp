package com.samin.objectdetection.detector

import com.samin.objectdetection.camera.DetectionConfig
import org.junit.Assert.*
import org.junit.Test

class DetectionDebugFrameTest {
    @Test fun recordsBelowProductionThresholdAndMaxEvenWhenDetailsAreCapped() {
        val frame = DetectionDebugFrame(123, 456, DetectionConfig(
            debugDetectionLogging = true, diagnosticMaxRawCandidates = 1))
        frame.raw(0, 0, "bollard", 0.083f, .5f, .5f, .2f, .4f, .2f)
        frame.raw(1, 0, "bollard", 0.183f, .5f, .5f, .2f, .4f, .2f)
        frame.raw(2, 0, "bollard", 0.001f, .5f, .5f, .2f, .4f, .2f)
        frame.summary(3)
        val text = frame.text()
        assertTrue(text.contains("confidence=0.083"))
        assertTrue(text.contains("reason=CONFIDENCE"))
        assertTrue(text.contains("candidateCount=2 logged=1 omitted=1"))
        assertTrue(text.contains("maxConfidence=0.183"))
        assertTrue(text.contains("bollardMaxConfidence=0.183"))
        assertTrue(text.contains("bollardCandidateCount=2"))
        assertFalse(text.contains("[RAW_YOLO] frame=123 candidate=1"))
    }

    @Test fun maxIsNotRestrictedToDiagnosticThreshold() {
        val frame = DetectionDebugFrame(1, 0, DetectionConfig(debugDetectionLogging = true))
        frame.raw(0, 0, "bollard", .005f, 0f, 0f, 0f, 0f, .2f)
        frame.summary(1)
        assertTrue(frame.text().contains("candidateCount=0"))
        assertTrue(frame.text().contains("maxConfidence=0.005"))
        assertTrue(frame.text().contains("bollardCandidateCount=0"))
    }

    @Test fun onlyRemovedBoxesAreReportedWithoutMutatingLists() {
        val frame = DetectionDebugFrame(1, 0, DetectionConfig(debugDetectionLogging = true))
        val kept = DetectionResult("bollard", .3f, 10f, 20f, 30f, 40f)
        val removed = kept.copy(confidence = .4f)
        val before = listOf(kept, removed)
        frame.removed(before, listOf(kept), "GEOMETRY_FILTER")
        assertTrue(frame.text().contains("confidence=0.4 reason=GEOMETRY_FILTER"))
        assertFalse(frame.text().contains("confidence=0.3"))
        assertEquals(2, before.size)
    }

    @Test fun loggingOffDoesNotRecordCandidateDetails() {
        val frame = DetectionDebugFrame(1, 0, DetectionConfig())
        frame.raw(0, 0, "bollard", .5f, 0f, 0f, 0f, 0f, .2f)
        frame.summary(1)
        assertFalse(frame.text().contains("RAW_YOLO"))
        assertFalse(frame.text().contains("[FRAME]"))
    }
}
