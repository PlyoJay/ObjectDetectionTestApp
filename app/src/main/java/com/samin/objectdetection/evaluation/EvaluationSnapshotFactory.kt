package com.samin.objectdetection.evaluation

import android.graphics.Bitmap
import android.graphics.Rect
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.metrics.FrameTrace
import com.samin.objectdetection.metrics.FrameRateMeter
import com.samin.objectdetection.warning.WarningCandidate

/** Test-app capture/recording snapshot; copied frames belong to the recorder/capture job. */
internal fun buildEvaluationSnapshot(
    bitmap: Bitmap,
    copyBitmap: Boolean,
    detections: List<DetectionResult>,
    evaluationDetections: List<DetectionResult>,
    frameTimestampMs: Long,
    roi: Rect,
    rawDetectionCount: Int,
    visibleDetectionCount: Int,
    warningDetectionCount: Int,
    topDetection: DetectionResult?,
    selectedWarningCandidate: WarningCandidate?,
    inferenceTimeMs: Long,
    fps: Int,
    userLocationSnapshot: com.samin.objectdetection.location.UserLocationSnapshot,
    stageCounts: com.samin.objectdetection.detector.DetectionStageCounts?,
    rawCoordinateMin: Float?,
    rawCoordinateMax: Float?,
    trace: FrameTrace,
    rates: FrameRateMeter.Rates
): DetectionFrameSnapshot {
    return DetectionFrameSnapshot(
        bitmap = if (copyBitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap,
        detections = detections,
        evaluationDetections = evaluationDetections,
        frameTimestampMs = frameTimestampMs,
        imageWidth = bitmap.width,
        imageHeight = bitmap.height,
        roiApplied = roi.left != 0 || roi.top != 0 || roi.right != bitmap.width || roi.bottom != bitmap.height,
        roi = Rect(roi),
        rawDetectionCount = rawDetectionCount,
        visibleDetectionCount = visibleDetectionCount,
        warningDetectionCount = warningDetectionCount,
        topDetection = topDetection,
        selectedWarningCandidate = selectedWarningCandidate,
        inferenceTimeMs = inferenceTimeMs,
        fps = fps,
        userLocationSnapshot = userLocationSnapshot,
        stageCounts = stageCounts,
        rawCoordinateMin = rawCoordinateMin,
        rawCoordinateMax = rawCoordinateMax,
        trace = trace, analyzerFps = rates.analyzerFps, inferenceFps = rates.inferenceFps
    )
}

