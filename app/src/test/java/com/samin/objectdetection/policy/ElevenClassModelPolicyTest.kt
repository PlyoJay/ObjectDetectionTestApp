package com.samin.objectdetection.policy

import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.detector.DetectionDebugFrame
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.ModelConfig
import com.samin.objectdetection.detector.YoloModel
import com.samin.objectdetection.metrics.DetectionMetricsCollector
import com.samin.objectdetection.warning.FeedbackLevel
import com.samin.objectdetection.warning.RiskObjectCategory
import com.samin.objectdetection.warning.WarningPolicy
import org.junit.Assert.*
import org.junit.Test

class ElevenClassModelPolicyTest {
    private val expected = listOf("bollard", "person", "bicycle", "car", "motorcycle",
        "bus", "truck", "stop sign", "bench", "cat", "dog")

    @Test fun everyClassIdIsExplicitAndUnknownModelOrderIsRejected() {
        assertEquals(expected, ModelConfig.CLASS_LABELS)
        expected.forEachIndexed { id, label -> assertEquals(label, YoloModel.YOLO_512.classLabels[id]) }
        assertEquals(expected.take(9), YoloModel.YOLO_640.classLabels)
        assertThrows(IllegalArgumentException::class.java) { ModelConfig.labelsFor("unverified.tflite") }
    }

    @Test fun animalsPassOverlayAndOptionalFiltersWithoutEnablingAnyWarnings() {
        val config = DetectionConfig()
        val validator = AdaptiveTemporalValidator(immediateConfidence = .99f, confirmationConfidence = .9f)
        for (label in listOf("cat", "dog")) {
            val box = DetectionResult(label, .9f, 200f, 10f, 440f, 630f)
            val policy = ObjectTuningPolicyRegistry.get(label)!!
            assertTrue(OverlayObjectFilter.isAllowed(" ${label.uppercase()} "))
            assertTrue(policy.enableOverlay)
            assertFalse(policy.enableWarning)
            assertFalse(policy.enableVoice)
            assertEquals(RiskObjectCategory.UNKNOWN, policy.category)
            assertNull(YoloDefaultPolicyRegistry.get(label))
            assertFalse(ObjectTuningPolicyRegistry.shouldWarn(box.copy(confidence = 1f)))
            assertTrue(BollardGeometryValidator.isValid(box, 640, 640, .99f, .01f))
            assertEquals(listOf(box), validator.filter(listOf(box)))
            assertEquals(listOf(box), SmallBoxFilterPolicy.filter(listOf(box), 640, 640, config))
            val evaluated = ObjectTuningPolicyRegistry.applyVoiceTuning(WarningPolicy.evaluate(box, 640, 640))
            assertEquals(FeedbackLevel.NONE, evaluated.warningFeedback.voiceLevel)
            assertEquals(FeedbackLevel.NONE, evaluated.warningFeedback.beepLevel)
            assertEquals(FeedbackLevel.NONE, evaluated.warningFeedback.vibrationLevel)
            assertFalse(evaluated.warningFeedback.shouldNotify)
        }
        assertEquals(expected.take(9).toSet(), YoloDefaultPolicyRegistry.getAll().map { it.label }.toSet())
    }

    @Test fun animalsRetainClassIdsConfidenceAndBoxesInDetailedLogsAndStatistics() {
        val frame = DetectionDebugFrame(1, 0, DetectionConfig(debugDetectionLogging = true))
        val boxes = listOf("cat", "dog").mapIndexed { index, label ->
            frame.raw(index, index + 9, label, .8f + index * .1f, .5f, .5f, .2f, .4f, .2f)
            DetectionResult(label, .8f + index * .1f, 10f, 20f, 30f, 40f)
        }
        frame.summary(5376)
        assertTrue(frame.text().contains("classId=9 className=cat confidence=0.8"))
        assertTrue(frame.text().contains("classId=10 className=dog confidence=0.9"))
        assertTrue(frame.text().contains("bollardCandidateCount=0"))
        val metrics = DetectionMetricsCollector()
        metrics.recordYoloDetections(boxes, boxes, emptyList(), 1234)
        val summary = metrics.buildSummary()
        assertTrue(summary.contains("cat 0.80"))
        assertTrue(summary.contains("dog 0.90"))
        assertTrue(summary.contains("Label raw: cat 1, dog 1"))
        assertTrue(summary.contains("Label visible: cat 1, dog 1"))
        assertTrue(summary.contains("Label warning: none"))
        assertTrue(summary.contains("Avg conf: none"))
    }
}
