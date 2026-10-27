package com.samin.objectdetection.camera

import com.samin.objectdetection.distance.ObjectDistanceMethod
import com.samin.objectdetection.settings.AppSettings
import java.util.concurrent.CompletableFuture

interface CameraBackend {
    fun start()
    /** Completes after camera release and all accepted analysis work. */
    fun stopAsync(): CompletableFuture<Unit>
}

enum class CameraBackendMode { CAMERAX, ARCORE }

fun AppSettings.cameraBackendMode(): CameraBackendMode = if (overlay.showDistance &&
    overlay.distanceMethod == ObjectDistanceMethod.ARCORE_DEPTH) CameraBackendMode.ARCORE else CameraBackendMode.CAMERAX
