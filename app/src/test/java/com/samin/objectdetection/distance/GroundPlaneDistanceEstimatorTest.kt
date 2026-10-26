package com.samin.objectdetection.distance

import com.samin.objectdetection.camera.FrameRoi
import com.samin.objectdetection.camera.RecognitionRoi
import com.samin.objectdetection.camera.YoloResizeMode
import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.detector.YoloModel
import com.samin.objectdetection.detector.YoloResizeTransform
import com.samin.objectdetection.warning.WarningPolicy
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GroundPlaneDistanceEstimatorTest {
    private val settings = DistanceSettings(150f, 0f)
    private val k = CameraIntrinsics(800f, 600f, 640f, 360f, "SYNTHETIC")
    private val geometry = AnalysisCameraGeometry(1280, 720, 0, automaticIntrinsics = k)
    private fun box(bottom: Float = 660f) = DetectionResult("bollard", .9f, 600f, 200f, 680f, bottom, 1234)

    @Test fun knownLevelCameraAndPitchedCameraRecoverForwardMeters() {
        assertEquals(3f, GroundPlaneDistanceEstimator(settings).estimate(box(), geometry).meters!!, 1e-5f)
        val pitched = GroundPlaneDistanceEstimator(settings.copy(downwardPitchDegrees = 30f))
        assertEquals((1.5 / kotlin.math.tan(Math.PI / 6)).toFloat(), pitched.estimate(box(360f), geometry).meters!!, 1e-5f)
        val theta = Math.toRadians(10.0)
        for (z in listOf(4.0, 8.0, 20.0, 100.0)) {
            val q = (1.5 * cos(theta) - z * sin(theta)) / (z * cos(theta) + 1.5 * sin(theta))
            val y = (k.cy + k.fy * q).toFloat()
            val result = GroundPlaneDistanceEstimator(settings.copy(downwardPitchDegrees = 10f))
                .estimate(box(y).copy(top = y - 50f), geometry)
            assertEquals(z.toFloat(), result.meters!!, .002f)
        }
    }

    @Test fun contactMovingTowardHorizonIncreasesDistanceAndLateralPositionDoesNotChangeForwardZ() {
        val estimator = GroundPlaneDistanceEstimator(settings)
        val distances = listOf(680f, 600f, 480f, 400f, 361f).map { estimator.estimate(box(it), geometry).meters!! }
        assertTrue(distances.zipWithNext().all { (a, b) -> b > a })
        assertEquals(distances.first(), estimator.estimate(box(680f).copy(left = 0f, right = 80f), geometry).meters)
        assertNull(estimator.estimate(box(360f), geometry).meters)
        assertNull(estimator.estimate(box(350f), geometry).meters)
    }

    @Test fun invalidBoxesAndClippedContactsHaveNoDistanceButDetectionSurvives() {
        val estimator = GroundPlaneDistanceEstimator(settings)
        val invalid = listOf(box().copy(left = Float.NaN), box().copy(bottom = Float.POSITIVE_INFINITY),
            box().copy(left = -1f), box().copy(top = -1f), box().copy(right = 1281f),
            box().copy(left = 700f), box().copy(top = 670f), box().copy(right = 600f),
            box(720f), box(719f), box(721f))
        for (detection in invalid) {
            val result = estimator.estimate(detection, geometry)
            assertNull(result.meters)
            val enriched = result.applyTo(detection)
            assertEquals(detection.label, enriched.label)
            assertEquals(detection.confidence, enriched.confidence)
            assertNull(enriched.estimatedDistanceMeters)
        }
        assertNotNull(estimator.estimate(box(718f), geometry).meters)
        assertEquals("CONTACT_CLIPPED", estimator.estimate(box(660f), geometry, ImageBounds(0f, 0f, 1280f, 660f)).status)
        assertEquals("CONTACT_CLIPPED", estimator.estimate(box(660f), geometry.copy(
            validBufferBounds = ImageBounds(0f, 0f, 1280f, 660f))).status)
    }

    @Test fun uncalibratedInvalidOpticsAndInvalidSettingsNeverGuessDistance() {
        for (invalid in listOf(DistanceSettings(), settings.copy(cameraHeightCm = null), settings.copy(downwardPitchDegrees = null),
            settings.copy(cameraHeightCm = 0f), settings.copy(cameraHeightCm = -1f),
            settings.copy(cameraHeightCm = Float.NaN), settings.copy(downwardPitchDegrees = Float.POSITIVE_INFINITY))) {
            assertNull(GroundPlaneDistanceEstimator(invalid).estimate(box(), geometry).meters)
        }
        val estimator = GroundPlaneDistanceEstimator(settings)
        assertNull(estimator.estimate(box(), null).meters)
        assertNull(estimator.estimate(box(), geometry.copy(rotationDegrees = 45)).meters)
        for (badK in listOf(k.copy(fy = 0f), k.copy(fx = -1f), k.copy(cy = Float.NaN), k.copy(fy = Float.POSITIVE_INFINITY))) {
            assertNull(estimator.estimate(box(), geometry.copy(automaticIntrinsics = badK)).meters)
        }
        assertNull(estimator.estimate(box(), geometry.copy(automaticIntrinsics = null)).meters)
        assertNull(GroundPlaneDistanceEstimator(settings.copy(opticsMode = DistanceOpticsMode.MANUAL)).estimate(box(), geometry).meters)
        // Looking almost straight down: a ray behind the camera's ground-forward axis is invalid.
        assertNull(GroundPlaneDistanceEstimator(settings.copy(downwardPitchDegrees = 89f)).estimate(box(), geometry).meters)
    }

    @Test fun sensorCropAspectRatioResolutionAndPrincipalPointUseActualBuffer() {
        val sensor = CameraIntrinsics(2000f, 2000f, 2000f, 1500f, "SENSOR")
        val crop = ImageBounds(0f, 0f, 4000f, 3000f)
        val wide = CameraIntrinsics.fromSensorCrop(sensor, crop, 1280, 720)!!
        assertEquals(640f, wide.fy, .001f)
        assertEquals(360f, wide.cy, .001f) // 4:3 sensor -> 16:9 stream center crop.
        val zoomed = CameraIntrinsics.fromSensorCrop(sensor, ImageBounds(1000f, 750f, 3000f, 2250f), 1280, 720)!!
        assertEquals(1280f, zoomed.fy, .001f)
        val half = CameraIntrinsics.fromSensorCrop(sensor, crop, 640, 360)!!
        assertEquals(wide.fy / 2, half.fy, .001f)
        val halfBox = box().copy(left = 300f, top = 100f, right = 340f, bottom = 330f)
        val estimator = GroundPlaneDistanceEstimator(settings)
        assertEquals(estimator.estimate(box(), geometry.copy(automaticIntrinsics = wide)).meters!!,
            estimator.estimate(halfBox, AnalysisCameraGeometry(640, 360, 0, automaticIntrinsics = half)).meters!!, .001f)
    }

    @Test fun quarterTurnsTransformPrincipalPointFocalAxesAndProxyCropExactlyOnce() {
        val raw = CameraIntrinsics(800f, 600f, 620f, 350f, "RAW")
        val expected = listOf(raw, CameraIntrinsics(600f, 800f, 370f, 620f, "RAW"),
            CameraIntrinsics(800f, 600f, 660f, 370f, "RAW"), CameraIntrinsics(600f, 800f, 350f, 660f, "RAW"))
        val bounds = ImageBounds(10f, 20f, 1200f, 700f)
        val expectedBounds = listOf(bounds, ImageBounds(20f, 10f, 700f, 1200f),
            ImageBounds(80f, 20f, 1270f, 700f), ImageBounds(20f, 80f, 700f, 1270f))
        for ((i, rotation) in listOf(0, 90, 180, 270).withIndex()) {
            val g = AnalysisCameraGeometry(1280, 720, rotation, bounds, raw)
            assertEquals(expected[i], g.intrinsics(settings))
            assertEquals(expectedBounds[i], g.uprightBounds())
            val upright = expected[i]
            val y = upright.cy + upright.fy * .2f
            val detection = DetectionResult("person", .8f, upright.cx - 20, y - 50, upright.cx + 20, y)
            assertEquals(7.5f, GroundPlaneDistanceEstimator(settings).estimate(detection, g).meters!!, .001f)
        }
    }

    @Test fun manualFovUsesUnrotatedFullBufferAndDoesNotSilentlyFallbackFromAuto() {
        val manual = settings.copy(opticsMode = DistanceOpticsMode.MANUAL, manualVerticalFovDegrees = 90f)
        val g = geometry.copy(rotationDegrees = 90, automaticIntrinsics = null)
        assertEquals(360f, g.intrinsics(manual)!!.fy, .001f)
        assertEquals(640f, g.intrinsics(manual)!!.cy, .001f)
        assertNull(g.intrinsics(manual.copy(opticsMode = DistanceOpticsMode.AUTO)))
    }

    @Test fun roiLetterboxAndStretchRoundTripAcrossBothModelClassSetsWithoutDoubleCrop() {
        val estimator = GroundPlaneDistanceEstimator(settings)
        for (model in YoloModel.entries) for (mode in YoloResizeMode.entries) for (enabled in listOf(false, true)) {
            val roi = FrameRoi.calculate(1280, 720, RecognitionRoi.PRESET.copy(enabled = enabled), false)
            val t = YoloResizeTransform.calculate(mode, roi.width, roi.height, model.inputSize, model.inputSize)
            val modelY = ((660f - roi.top) * t.scaleY + t.padTop) / model.inputSize
            val restoredBottom = roi.sourceY(t.sourceY(modelY))
            for (label in model.classLabels) {
                val d = box(restoredBottom).copy(label = label)
                assertEquals(3f, estimator.estimate(d, geometry).meters!!, .001f)
            }
        }
    }

    @Test fun addingDistancePreservesCopiesAndEveryWarningPolicyField() {
        val base = box()
        val enriched = GroundPlaneDistanceEstimator(settings).estimate(base, geometry).applyTo(base)
        assertEquals(base, enriched.copy(estimatedDistanceMeters = null, distanceEstimation = null))
        assertEquals(3f, enriched.copy(label = "person").estimatedDistanceMeters!!, .001f)
        assertEquals(WarningPolicy.evaluate(base, 1280, 720),
            WarningPolicy.evaluate(enriched, 1280, 720).copy(estimatedDistanceMeters = null, distanceEstimation = null))
    }
}
