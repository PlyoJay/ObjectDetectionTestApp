package com.samin.objectdetection.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.ObjectDetector
import com.samin.objectdetection.location.UserLocationSnapshot
import com.samin.objectdetection.motion.ObjectMotionTracker
import com.samin.objectdetection.policy.ObjectTuningPolicyRegistry
import com.samin.objectdetection.policy.OverlayObjectFilter
import com.samin.objectdetection.policy.SmallBoxFilterPolicy
import com.samin.objectdetection.warning.WarningPolicy

class DetectionPipeline(
    private val detector: ObjectDetector,
    private val config: DetectionConfig,
    private val objectMotionTracker: ObjectMotionTracker,
    private val userLocationSnapshotProvider: () -> UserLocationSnapshot
) {

    fun process(
        bitmap: Bitmap,
        timestampMs: Long,
        rotationDegrees: Int
    ): DetectionPipelineResult {
        val width = bitmap.width
        val height = bitmap.height
        val cropRect = createCenterSquareCrop(width, height)
        val detectionStartTimeMs = System.currentTimeMillis()
        var cropped: Bitmap? = null

        try {
            cropped = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
            val croppedResults = detector.detect(cropped)
            val detectionEndTimeMs = System.currentTimeMillis()
            val mappedDetections = croppedResults.map { result ->
                val detection = mapToOriginalFrame(result, cropRect, timestampMs)
                WarningPolicy.evaluate(
                    detection = detection,
                    frameWidth = width,
                    frameHeight = height
                ).also { detection ->
                    if (config.enableDetectorDiagnostics) WarningPolicy.logDebug(detection)
                }
            }
            val visibleDetections = SmallBoxFilterPolicy.filter(
                detections = mappedDetections,
                frameWidth = width,
                frameHeight = height,
                config = config
            )
            val overlayCandidates = visibleDetections.filter { detection ->
                OverlayObjectFilter.isAllowed(detection.label)
            }
            val ignoredLabels = visibleDetections
                .filterNot { detection -> OverlayObjectFilter.isAllowed(detection.label) }
                .map { detection -> OverlayObjectFilter.normalize(detection.label) }
                .distinct()
                .sorted()
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

            return DetectionPipelineResult(
                frameWidth = width,
                frameHeight = height,
                cropRect = cropRect,
                mappedDetections = mappedDetections,
                visibleDetections = visibleDetections,
                overlayDetections = overlayDetections,
                warningDetections = warningDetections,
                ignoredLabels = ignoredLabels,
                inferenceTimeMs = detectionEndTimeMs - detectionStartTimeMs,
                topOverlayObject = overlayDetections.maxByOrNull { it.confidence },
                userLocationSnapshot = userLocationSnapshot
            )
        } finally {
            if (cropped != null && cropped !== bitmap && !cropped.isRecycled) {
                cropped.recycle()
            }
        }
    }

    private fun createCenterSquareCrop(width: Int, height: Int): Rect {
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
                "preprocess=center_square_crop_then_stretch_${config.inputSize}x${config.inputSize}_rgb_float_0_to_1 " +
                "inferenceTimeMs=${stats?.inferenceTimeMs ?: -1} rawTop5=${stats?.rawTopConfidences ?: emptyList<Float>()} " +
                "rawCandidates=${stats?.rawCandidateCount ?: -1} confidencePassed=${stats?.confidencePassedCount ?: -1} " +
                "invalidBox=${stats?.invalidBoxCount ?: -1} detectorAreaRejected=${stats?.detectorAreaRejectedCount ?: -1} " +
                "nmsInput=${stats?.nmsInputCount ?: -1} nmsAfter=${stats?.nmsOutputCount ?: -1} " +
                "smallBoxAfter=${visibleDetections.size} overlayAfter=${overlayDetections.size} finalDetections=${warningDetections.size}"
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
