package com.samin.objectdetection.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.DetectionStageCounts
import com.samin.objectdetection.detector.ObjectDetector
import com.samin.objectdetection.detector.DetectionDebugRecorder
import com.samin.objectdetection.location.UserLocationSnapshot
import com.samin.objectdetection.motion.ObjectMotionTracker
import com.samin.objectdetection.policy.ObjectTuningPolicyRegistry
import com.samin.objectdetection.policy.OverlayObjectFilter
import com.samin.objectdetection.policy.SmallBoxFilterPolicy
import com.samin.objectdetection.policy.AdaptiveTemporalValidator
import com.samin.objectdetection.policy.BollardGeometryValidator
import com.samin.objectdetection.warning.WarningPolicy

class DetectionPipeline(
    private val detector: ObjectDetector,
    private val config: DetectionConfig,
    private val objectMotionTracker: ObjectMotionTracker,
    private val userLocationSnapshotProvider: () -> UserLocationSnapshot,
    private val debugRecorder: DetectionDebugRecorder? = null
) {
    private val temporalValidator = AdaptiveTemporalValidator(
        immediateConfidence = config.temporalImmediateConfidence,
        confirmationConfidence = config.temporalConfirmationConfidence,
        matchIouThreshold = config.temporalMatchIouThreshold
    )

    fun process(
        bitmap: Bitmap,
        timestampMs: Long,
        rotationDegrees: Int
    ): DetectionPipelineResult {
        val pipelineStartedAtMs = System.currentTimeMillis()
        val pipelineStartNs = SystemClock.elapsedRealtimeNanos()
        val width = bitmap.width
        val height = bitmap.height
        val cropRect = createInferenceRect(width, height)
        val debugFrame = debugRecorder?.begin(timestampMs)
        detector.setDebugFrame(debugFrame)
        debugFrame?.sourceDescription = (
            "camera=${if (rotationDegrees % 180 == 0) width else height}x${if (rotationDegrees % 180 == 0) height else width} " +
            "rotated=${width}x$height rotation=$rotationDegrees rotationOperation=Matrix.postRotate_clockwise " +
            "roi=[${cropRect.left},${cropRect.top},${cropRect.right},${cropRect.bottom}] roiSpace=rotated_frame_pixels " +
            "confidenceThreshold=${config.confidenceThreshold} nmsThreshold=${config.nmsThreshold} " +
            "geometryEnabled=${config.bollardGeometryFilterEnabled} sizeFilter=${config.sizeFilterMode} temporalEnabled=${config.adaptiveTemporalEnabled}")
        debugFrame?.line("[FRAME_INPUT] frame=${debugFrame.id} ${debugFrame.sourceDescription}")
        var cropped: Bitmap? = null

        try {
            val cropStartNs = SystemClock.elapsedRealtimeNanos()
            cropped = if (cropRect.isFullFrame(width, height)) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
            }
            val cropTimeMs = elapsedMs(cropStartNs)
            val inferenceStartedAtMs = System.currentTimeMillis()
            val detectorStartNs = SystemClock.elapsedRealtimeNanos()
            val croppedResults = detector.detect(cropped)
            val detectorTimeMs = elapsedMs(detectorStartNs)
            val postprocessStartNs = SystemClock.elapsedRealtimeNanos()
            val mappedDetections = croppedResults.map { result ->
                val detection = mapToOriginalFrame(result, cropRect, timestampMs)
                WarningPolicy.evaluate(
                    detection = detection,
                    frameWidth = width,
                    frameHeight = height,
                    veryNearHeightRatio = config.warningVeryNearHeightRatio,
                    nearHeightRatio = config.warningNearHeightRatio,
                    midHeightRatio = config.warningMidHeightRatio,
                    veryNearAreaRatio = config.warningVeryNearAreaRatio,
                    nearAreaRatio = config.warningNearAreaRatio,
                    midAreaRatio = config.warningMidAreaRatio
                ).also { detection ->
                    if (config.enableDetectorDiagnostics) WarningPolicy.logDebug(detection)
                }
            }
            val geometryFilteredDetections = if (config.bollardGeometryFilterEnabled) {
                mappedDetections.filter {
                    BollardGeometryValidator.isValid(
                        it,
                        width,
                        height,
                        config.bollardMinAreaRatio,
                        config.bollardMaxWidthToHeightRatio
                    )
                }
            } else {
                mappedDetections
            }
            val visibleDetections = SmallBoxFilterPolicy.filter(
                detections = geometryFilteredDetections,
                frameWidth = width,
                frameHeight = height,
                config = config
            )
            val overlayCandidatesBeforeTemporal = visibleDetections.filter { detection ->
                OverlayObjectFilter.isAllowed(detection.label)
            }
            val overlayCandidates = if (config.adaptiveTemporalEnabled) {
                temporalValidator.filter(overlayCandidatesBeforeTemporal)
            } else {
                overlayCandidatesBeforeTemporal
            }
            val ignoredLabels = visibleDetections
                .filterNot { detection -> OverlayObjectFilter.isAllowed(detection.label) }
                .map { detection -> OverlayObjectFilter.normalize(detection.label) }
                .distinct()
                .sorted()
            debugFrame?.removed(mappedDetections, geometryFilteredDetections, "GEOMETRY_FILTER")
            debugFrame?.removed(geometryFilteredDetections, visibleDetections, "SIZE_PIPELINE")
            debugFrame?.removed(visibleDetections, overlayCandidatesBeforeTemporal, "OVERLAY_CLASS")
            debugFrame?.removed(overlayCandidatesBeforeTemporal, overlayCandidates, "TEMPORAL")
            val userLocationSnapshot = userLocationSnapshotProvider()
            val overlayDetections = objectMotionTracker.update(
                detections = overlayCandidates,
                frameWidth = width,
                frameHeight = height,
                timestampMs = timestampMs,
                userMotionState = userLocationSnapshot.motionState
            ).map { detection ->
                ObjectTuningPolicyRegistry.applyVoiceTuning(
                    WarningPolicy.applyScenarioFeedback(detection)
                )
            }
            val warningDetections = overlayDetections.filter { detection ->
                ObjectTuningPolicyRegistry.shouldWarn(detection)
            }
            if (config.enableDetectorDiagnostics) {
                logFrameDiagnostics(
                    timestampMs = timestampMs,
                    rotationDegrees = rotationDegrees,
                    frameWidth = width,
                    frameHeight = height,
                    cropRect = cropRect,
                    detections = mappedDetections,
                    visibleDetections = visibleDetections,
                    overlayDetections = overlayDetections,
                    warningDetections = warningDetections
                )
            }
            val postprocessTimeMs = elapsedMs(postprocessStartNs)
            val detectorDiagnostics = detector.frameDiagnostics()
            debugFrame?.line("[DETECTION_FLOW] frame=${debugFrame.id} " +
                "rawCandidates=${detectorDiagnostics?.rawCandidateCount ?: -1} " +
                "afterConfidence=${detectorDiagnostics?.confidencePassedCount ?: -1} " +
                "invalidBox=${detectorDiagnostics?.invalidBoxCount ?: -1} " +
                "detectorSizeRejected=${detectorDiagnostics?.detectorAreaRejectedCount ?: -1} " +
                "afterCandidateLimit=${detectorDiagnostics?.nmsInputCount ?: -1} " +
                "afterNms=${croppedResults.size} afterGeometry=${geometryFilteredDetections.size} " +
                "afterSize=${visibleDetections.size} afterClass=${overlayCandidatesBeforeTemporal.size} " +
                "afterTemporal=${overlayCandidates.size} visible=${overlayDetections.size} visibleMeaning=submitted_to_ui")

            return DetectionPipelineResult(
                debugFrameId = debugFrame?.id,
                stageCounts = DetectionStageCounts(
                    rawCandidateCount = detectorDiagnostics?.rawCandidateCount,
                    confidencePassedCount = detectorDiagnostics?.confidencePassedCount,
                    invalidBoxCount = detectorDiagnostics?.invalidBoxCount,
                    detectorSizeRejectedCount = detectorDiagnostics?.detectorAreaRejectedCount,
                    nmsInputCount = detectorDiagnostics?.nmsInputCount,
                    nmsPassedCount = croppedResults.size,
                    geometryPassedCount = geometryFilteredDetections.size,
                    sizeFilterPassedCount = visibleDetections.size,
                    overlayClassPassedCount = overlayCandidatesBeforeTemporal.size,
                    temporalPassedCount = overlayCandidates.size,
                    finalDetectionCount = overlayDetections.size
                ),
                frameWidth = width,
                frameHeight = height,
                cropRect = cropRect,
                mappedDetections = mappedDetections,
                visibleDetections = visibleDetections,
                overlayDetections = overlayDetections,
                warningDetections = warningDetections,
                ignoredLabels = ignoredLabels,
                inferenceTimeMs = detectorDiagnostics?.inferenceTimeMs ?: detectorTimeMs,
                timing = PipelineTiming(
                    pipelineStartedAtMs = pipelineStartedAtMs,
                    inferenceStartedAtMs = inferenceStartedAtMs,
                    cropTimeMs = cropTimeMs,
                    detectorTimeMs = detectorTimeMs,
                    postprocessTimeMs = postprocessTimeMs,
                    pipelineTimeMs = elapsedMs(pipelineStartNs)
                ),
                topOverlayObject = overlayDetections.maxByOrNull { it.confidence },
                userLocationSnapshot = userLocationSnapshot
            )
        } catch (error: Exception) {
            debugFrame?.line("[PIPELINE_ERROR] frame=${debugFrame.id} error=$error")
            throw error
        } finally {
            detector.setDebugFrame(null)
            debugFrame?.let { debugRecorder?.finish(it) }
            if (cropped != null && cropped !== bitmap && !cropped.isRecycled) {
                cropped.recycle()
            }
        }
    }

    private fun elapsedMs(startNs: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L

    private fun createInferenceRect(width: Int, height: Int): Rect {
        if (!config.useCenterSquareCrop) {
            return Rect(0, 0, width, height)
        }
        val size = minOf(width, height)
        val left = (width - size) / 2
        val top = (height - size) / 2
        return Rect(left, top, left + size, top + size)
    }

    private fun mapToOriginalFrame(
        result: DetectionResult,
        cropRect: Rect,
        timestampMs: Long
    ): DetectionResult {
        return DetectionResult(
            label = result.label,
            confidence = result.confidence,
            left = result.left + cropRect.left,
            top = result.top + cropRect.top,
            right = result.right + cropRect.left,
            bottom = result.bottom + cropRect.top,
            frameTimestampMs = timestampMs
        )
    }

    private fun logFrameDiagnostics(
        timestampMs: Long,
        rotationDegrees: Int,
        frameWidth: Int,
        frameHeight: Int,
        cropRect: Rect,
        detections: List<DetectionResult>,
        visibleDetections: List<DetectionResult>,
        overlayDetections: List<DetectionResult>,
        warningDetections: List<DetectionResult>
    ) {
        val model = detector.modelIdentity()
        val stats = detector.frameDiagnostics()
        Log.d(
            BOLLARD_DIAGNOSTICS_TAG,
            "frameTimestamp=$timestampMs modelSha256=${model?.sha256Prefix ?: "unknown"} " +
                "inputImage=${frameWidth}x$frameHeight roi=[${cropRect.left},${cropRect.top},${cropRect.right},${cropRect.bottom}] " +
                "roiApplied=${!cropRect.isFullFrame(frameWidth, frameHeight)} rotationDegrees=$rotationDegrees " +
                "preprocess=${if (config.useCenterSquareCrop) "center_square_crop" else "full_frame"}" +
                "_then_${config.yoloResizeMode.name.lowercase()}_${config.inputSize}x${config.inputSize}_rgb_float_0_to_1 " +
                "inferenceTimeMs=${stats?.inferenceTimeMs ?: -1} rawTop5=${stats?.rawTopConfidences ?: emptyList<Float>()} " +
                "rawDetectionCount=${stats?.rawCandidateCount ?: -1} " +
                "confidenceFilteredCount=${stats?.confidencePassedCount ?: -1} " +
                "invalidBox=${stats?.invalidBoxCount ?: -1} detectorAreaRejected=${stats?.detectorAreaRejectedCount ?: -1} " +
                "nmsInput=${stats?.nmsInputCount ?: -1} nmsAfter=${stats?.nmsOutputCount ?: -1} " +
                "sizeFilterMode=${config.sizeFilterMode} sizeFilterInput=${detections.size} " +
                "sizeFilteredCount=${visibleDetections.size} sizeRejectedCount=${detections.size - visibleDetections.size} " +
                "overlayAfter=${overlayDetections.size} finalDetections=${warningDetections.size}"
        )
        val safeFrameWidth = frameWidth.coerceAtLeast(1).toFloat()
        detections.filter { ObjectTuningPolicyRegistry.normalize(it.label) == BOLLARD_LABEL }
            .forEach { detection ->
                val smallBoxPassed = visibleDetections.any { it.sameBoxAs(detection) }
                val shouldWarn = warningDetections.any { it.sameBoxAs(detection) }
                Log.d(
                    BOLLARD_DIAGNOSTICS_TAG,
                    "stage=pipeline label=${detection.label} confidence=${detection.confidence} " +
                        "bboxWidthRatio=${detection.bboxWidth / safeFrameWidth} " +
                        "bboxHeightRatio=${detection.bboxHeightRatio} " +
                        "bboxAreaRatio=${detection.bboxAreaRatio} " +
                        "centerXRatio=${detection.centerXRatio} centerYRatio=${detection.centerYRatio} " +
                        "smallBoxPassed=$smallBoxPassed warningPolicyRisk=${detection.riskLevel} " +
                        "isIgnored=${detection.isIgnored} shouldWarn=$shouldWarn"
                )
            }
    }

    private fun DetectionResult.sameBoxAs(other: DetectionResult): Boolean {
        return label == other.label &&
            left == other.left && top == other.top && right == other.right && bottom == other.bottom
    }

    private fun Rect.isFullFrame(frameWidth: Int, frameHeight: Int): Boolean {
        return left == 0 && top == 0 && right == frameWidth && bottom == frameHeight
    }

    private companion object {
        const val BOLLARD_LABEL = "bollard"
        const val BOLLARD_DIAGNOSTICS_TAG = "BollardDiagnostics"
    }
}
