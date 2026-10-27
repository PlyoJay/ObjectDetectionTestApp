package com.samin.objectdetection.settings

import com.samin.objectdetection.camera.CameraBackendMode
import com.samin.objectdetection.camera.cameraBackendMode
import com.samin.objectdetection.detector.YoloModel
import com.samin.objectdetection.distance.*
import org.junit.Assert.*
import org.junit.Test

class MainDistanceModeTest {
    @Test fun defaultAndOffAlwaysUseCameraXWhileOnlyOnArCoreSelectsArCore() {
        val base = AppSettings()
        assertFalse(base.overlay.showDistance)
        for (method in ObjectDistanceMethod.entries) {
            val off = base.copy(overlay = base.overlay.copy(showDistance = false, distanceMethod = method))
            assertEquals(CameraBackendMode.CAMERAX, off.cameraBackendMode())
            val on = off.copy(overlay = off.overlay.copy(showDistance = true))
            assertEquals(if (method == ObjectDistanceMethod.ARCORE_DEPTH) CameraBackendMode.ARCORE else CameraBackendMode.CAMERAX, on.cameraBackendMode())
            assertEquals(CameraBackendMode.CAMERAX, on.copy(overlay = on.overlay.copy(showDistance = false)).cameraBackendMode())
        }
    }
    @Test fun schemaEightGroundDistanceOnMigratesWithoutStartingArCoreOrLosingCalibration() {
        val old = "schemaVersion=8\noverlay.showDistance=true\ndistance.cameraHeightCm=153\ndistance.downwardPitchDegrees=12\npresetName=MY CUSTOM"
        val migrated = AppSettingsCodec.decode(old)
        assertEquals(ObjectDistanceMethod.GROUND_PLANE, migrated.overlay.distanceMethod)
        assertTrue(migrated.overlay.showDistance); assertEquals(CameraBackendMode.CAMERAX, migrated.cameraBackendMode())
        assertEquals(153f, migrated.distance.cameraHeightCm!!, 0f); assertEquals("MY CUSTOM", migrated.presetName)
        assertEquals(migrated, AppSettingsCodec.decode(AppSettingsCodec.encode(migrated)))
        val off = AppSettingsCodec.decode("schemaVersion=8\noverlay.showDistance=false")
        assertFalse(off.overlay.showDistance); assertEquals(ObjectDistanceMethod.ARCORE_DEPTH, off.overlay.distanceMethod)
    }
    @Test fun methodAndRawOptionsRoundTripForModelsAndUserPresetsWithoutChangingDetectorContract() {
        for (model in YoloModel.entries) for (method in ObjectDistanceMethod.entries) for (input in DepthInputMode.entries) {
            val base = AppSettings(yolo = YoloSettings(model = model))
            val selected = base.copy(presetName = "MY DEPTH PRESET", overlay = base.overlay.copy(showDistance = true, distanceMethod = method, depthInputMode = input))
            val decoded = AppSettingsCodec.decode(AppSettingsCodec.encode(selected))
            assertEquals(selected, decoded); assertEquals(base.toDetectionConfig(), decoded.toDetectionConfig())
            assertEquals(base.warning, decoded.warning); assertTrue(decoded.validationErrors().isEmpty())
        }
    }
    @Test fun unknownEnumsUseSafeDefaultsAndArCoreRequiresNoMountValues() {
        val value = AppSettingsCodec.decode("schemaVersion=9\noverlay.distanceMethod=unknown\noverlay.depthInputMode=unknown\noverlay.showDistance=true")
        assertEquals(ObjectDistanceMethod.ARCORE_DEPTH, value.overlay.distanceMethod)
        assertEquals(DepthInputMode.FULL, value.overlay.depthInputMode)
        assertEquals(DistanceSettings(), value.distance); assertTrue(value.validationErrors().isEmpty())
    }
}
