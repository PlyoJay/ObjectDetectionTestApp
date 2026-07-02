package com.samin.objectdetection.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.ObjectDetector
import com.samin.objectdetection.location.UserLocationSnapshot
import com.samin.objectdetection.motion.ObjectMotionTracker
import com.samin.objectdetection.policy.OverlayObjectFilter
import com.samin.objectdetection.policy.SmallBoxFilterPolicy
import com.samin.objectdetection.policy.YoloDefaultPolicyRegistry
import com.samin.objectdetection.warning.WarningPolicy

class DetectionPipeline(
    private val detector: ObjectDetector,
    private val config: DetectionConfig,
    private val objectMotionTracker: ObjectMotionTracker,
    private val userLocationSnapshotProvider: () -> UserLocationSnapshot
) {

    fun process(
        bitmap: Bitmap,
        timestampMs: Long
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
                ).also { WarningPolicy.logDebug(it) }
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
                WarningPolicy.applyScenarioFeedback(detection)
            }
            val warningDetections = overlayDetections.filter { detection ->
                val policy = YoloDefaultPolicyRegistry.get(detection.label)
                !detection.isIgnored && policy != null && detection.confidence >= policy.minConfidence
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
}
