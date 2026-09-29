package com.samin.objectdetection.metrics

import com.samin.objectdetection.camera.YoloResizeMode
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceLogFormatterTest {
    @Test
    fun headerContainsActualConfiguration() {
        val text = PerformanceLogFormatter.formatHeader(
            PerformanceLogHeader(
                startedAtMs = 0L,
                modelName = "model.tflite",
                detectorName = "Detector",
                inputWidth = 640,
                inputHeight = 640,
                cameraWidth = 1280,
                cameraHeight = 720,
                resizeMode = YoloResizeMode.LETTERBOX,
                yoloThreads = 4,
                mlKitEnabled = false,
                geometryFilterEnabled = true,
                adaptiveTemporalEnabled = false,
                confidenceThreshold = 0.2f,
                nmsThreshold = 0.45f
            )
        )

        assertTrue(text.contains("Model: model.tflite"))
        assertTrue(text.contains("Camera Resolution: 1280x720"))
        assertTrue(text.contains("resizeMode=LETTERBOX"))
        assertTrue(text.contains("ML Kit Enabled: false"))
    }

    @Test
    fun frameIsFormattedAsOneLine() {
        val text = PerformanceLogFormatter.formatFrame(
            PerformanceFrameRecord(
                timestampMs = 0L,
                frameIndex = 123L,
                bitmapConversionMs = 3L,
                preprocessMs = 14L,
                cropMs = 0L,
                resizeMs = 4L,
                inferenceMs = 87L,
                outputParsingMs = 3L,
                nmsMs = 1L,
                postprocessMs = 5L,
                pipelineMs = 109L,
                frameAgeAtInferenceStartMs = 20L,
                frameAgeAtOverlayMs = 137L,
                detectionCount = 2,
                visibleDetectionCount = 1,
                warningDetectionCount = 1,
                fps = 8
            )
        )

        assertTrue(text.startsWith("FRAME 000123"))
        assertTrue(text.contains("inference=87ms"))
        assertTrue(text.contains("frameAgeOverlay=137ms"))
        assertTrue(!text.contains('\n'))
    }

    @Test
    fun finalSummaryContainsP95AndDroppedLogCount() {
        val stats = PerformanceMetricStats(10L, 20L)
        val text = PerformanceLogFormatter.formatFinalSummary(
            PerformanceSnapshot(
                durationMs = 278_000L,
                receivedFrames = 10L,
                processedFrames = 8L,
                droppedFrames = 2L,
                averageFps = 8,
                preprocess = stats,
                inference = stats,
                postprocess = stats,
                pipeline = stats,
                frameAgeAtOverlay = stats,
                yoloDetections = 4L,
                visibleDetections = 3L,
                warnings = 1L,
                mlKitAverageMs = 15L
            ),
            droppedLogLines = 7L
        )

        assertTrue(text.contains("Duration: 04:38"))
        assertTrue(text.contains("P95 Pipeline: 20 ms"))
        assertTrue(text.contains("Dropped Log Lines: 7"))
    }
}
