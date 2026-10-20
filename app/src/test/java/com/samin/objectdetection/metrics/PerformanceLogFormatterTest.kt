package com.samin.objectdetection.metrics

import com.samin.objectdetection.camera.YoloResizeMode
import com.samin.objectdetection.detector.InferenceExecutionInfo
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
                inputWidth = 512,
                inputHeight = 512,
                cameraWidth = 1280,
                cameraHeight = 720,
                resizeMode = YoloResizeMode.LETTERBOX,
                yoloThreads = 4,
                mlKitEnabled = false,
                geometryFilterEnabled = true,
                adaptiveTemporalEnabled = false,
                confidenceThreshold = 0.2f,
                nmsThreshold = 0.45f,
                presetName = "FIELD TEST - LETTERBOX",
                settingsSchemaVersion = 3,
                modelSha256 = "abc123",
                requestedCameraWidth = 1920,
                requestedCameraHeight = 1080,
                appVersion = "1.2.3",
                settingsSnapshot = "camera.detectIntervalMs=0\nyolo.maxCandidates=100",
                sessionId = "test-session", startedElapsedNs = 123L,
                execution = InferenceExecutionInfo(cpuThreads = 2, xnnpackRequested = true),
                inputTensorShape = "[1, 3, 512, 512]", inputTensorType = "FLOAT32",
                outputTensorShape = "[1, 13, 5376]", outputTensorType = "FLOAT32"
            )
        )

        assertTrue(text.contains("Model: model.tflite"))
        assertTrue(text.contains("Input Size: 512x512"))
        assertTrue(text.contains("Input Tensor: [1, 3, 512, 512] (FLOAT32)"))
        assertTrue(text.contains("Output Tensor: [1, 13, 5376] (FLOAT32)"))
        assertTrue(text.contains("Camera Resolution: 1280x720"))
        assertTrue(text.contains("cameraPreviewEnabled=true"))
        assertTrue(text.contains("Overlay Draw: AVAILABLE_WHEN_DRAWN"))
        assertTrue(text.contains("resizeMode=LETTERBOX"))
        assertTrue(text.contains("ML Kit Enabled: false"))
        assertTrue(text.contains("Preset: FIELD TEST - LETTERBOX"))
        assertTrue(text.contains("Model SHA256: abc123"))
        assertTrue(text.contains("Requested Camera Resolution: 1920x1080"))
        assertTrue(text.contains("yolo.maxCandidates=100"))
        assertTrue(text.contains("Session ID: test-session"))
        assertTrue(text.contains("Start Elapsed Ns: 123"))
        assertTrue(text.contains("requestedBackend=CPU appliedBackend=CPU cpuThreads=2"))
        assertTrue(text.contains("xnnpackRequested=true"))
        assertTrue(text.contains("XNNPACK Requested: true (CPU request only"))
        assertTrue(text.contains("delegateVerification=UNAVAILABLE_JAVA_API"))
        assertTrue(text.contains("gpuSupport=UNSUPPORTED_INTERPRETER_LITERT_2_1_1"))
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
                fps = 8, analyzerFps = 12.5, inferenceFps = 8.25,
                trace = FrameTrace("test-session", 42, 1000, 0, false, 1000, 100,
                    completedAtMs = 1100, completedElapsedNs = 200),
                totalProcessingMs = 117,
                cameraToBitmapNs = 1_125_000, rotationNs = 0,
                inputBufferMs = 10, getPixelsNs = 300_000, rgbWriteNs = 1_900_000,
                inputDiagnosticsNs = 0, outputDiagnosticsNs = 0, nativeInferenceNs = 85_100_000,
                mlKitPrepareNs = 25_000, mlKitInFlight = true,
                execution = InferenceExecutionInfo(cpuThreads = 4, xnnpackRequested = true,
                    lastInference = "SUCCESS", inferenceErrors = 1)
            )
        )

        assertTrue(text.startsWith("FRAME 000123"))
        assertTrue(text.contains("inference=87ms"))
        assertTrue(text.contains("frameAgeOverlay=137ms"))
        assertTrue(!text.contains('\n'))
        assertTrue(text.contains("sessionId=test-session frameId=42 status=SUCCESS"))
        assertTrue(text.contains("analyzerFps=12.5 | inferenceFps=8.25"))
        assertTrue(text.contains("totalProcessing=117ms"))
        assertTrue(text.contains("cameraToBitmap=1.125ms | rotation=0.000ms"))
        assertTrue(text.contains("inputBuffer=10ms | getPixels=0.300ms | rgbWrite=1.900ms"))
        assertTrue(text.contains("nativeInvoke=85.100ms"))
        assertTrue(text.contains("mlKitPrepare=0.025ms | mlKitInFlight=true"))
        assertTrue(text.contains("lastInference=SUCCESS inferenceErrors=1 cpuFallbackCount=0"))
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
