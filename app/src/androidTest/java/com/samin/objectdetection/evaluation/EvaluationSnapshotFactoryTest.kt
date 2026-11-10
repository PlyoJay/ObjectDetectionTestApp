package com.samin.objectdetection.evaluation

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.location.UserLocationSnapshot
import com.samin.objectdetection.metrics.FrameRateMeter
import com.samin.objectdetection.metrics.FrameTrace
import org.junit.Assert.*
import org.junit.Test

class EvaluationSnapshotFactoryTest {
    @Test fun captureSurvivesFrameReuseWhileRecordingBorrowsTheCurrentFrame() {
        val source = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val roi = Rect(1, 2, 7, 10)
        val detection = DetectionResult("person", .9f, 2f, 3f, 6f, 9f)
        val trace = FrameTrace("snapshot-test", 1, 1234, 0, false, 1234, 1_000_000)
        fun snapshot(copy: Boolean) = buildEvaluationSnapshot(source, copy, listOf(detection), listOf(detection),
            1234, roi, 1, 1, 0, detection, null, 10, 20, UserLocationSnapshot(), null, null, null,
            trace, FrameRateMeter.Rates(30.0, 20.0))
        val capture = snapshot(true)
        try {
            val recording = snapshot(false)
            assertSame(source, recording.bitmap)
            assertNotSame(source, capture.bitmap)
            source.eraseColor(Color.BLUE) // Camera rotation/ROI storage may be reused immediately.
            roi.setEmpty()
            assertEquals(Color.RED, capture.bitmap.getPixel(0, 0))
            assertEquals(Color.BLUE, recording.bitmap.getPixel(0, 0))
            assertEquals(Rect(1, 2, 7, 10), capture.roi)
            assertEquals(capture.roi, recording.roi)
            assertTrue(capture.roiApplied)
            assertSame(trace, capture.trace)
            assertEquals(listOf(detection), capture.evaluationDetections)
            assertEquals(30.0, capture.analyzerFps, 0.0)
            assertEquals(20.0, capture.inferenceFps, 0.0)
        } finally {
            capture.bitmap.recycle()
            source.recycle()
        }
    }
}
