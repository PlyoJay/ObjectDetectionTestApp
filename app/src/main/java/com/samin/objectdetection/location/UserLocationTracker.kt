package com.samin.objectdetection.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

data class UserLocationSnapshot(
    val motionState: UserMotionState = UserMotionState.UNKNOWN,
    val speedMps: Float? = null,
    val accuracyMeters: Float? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

class UserLocationTracker(
    private val context: Context
) {
    @Volatile
    var currentSnapshot: UserLocationSnapshot = UserLocationSnapshot()
        private set

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val recentLocations = ArrayDeque<Location>()
    private val recentSpeeds = ArrayDeque<Float>()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location)
        }

        override fun onProviderDisabled(provider: String) {
            currentSnapshot = UserLocationSnapshot()
        }
    }

    fun start() {
        if (!hasLocationPermission()) {
            currentSnapshot = UserLocationSnapshot()
            return
        }

        try {
            val providers = buildList {
                if (hasFineLocationPermission()) {
                    add(LocationManager.GPS_PROVIDER)
                }
                add(LocationManager.NETWORK_PROVIDER)
            }
                .filter { provider -> locationManager.isProviderEnabled(provider) }

            if (providers.isEmpty()) {
                currentSnapshot = UserLocationSnapshot()
                return
            }

            providers.forEach { provider ->
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        LOCATION_UPDATE_INTERVAL_MS,
                        MIN_LOCATION_DISTANCE_METERS,
                        locationListener,
                        Looper.getMainLooper()
                    )
                    locationManager.getLastKnownLocation(provider)?.let(::handleLocation)
                } catch (_: SecurityException) {
                    currentSnapshot = UserLocationSnapshot()
                } catch (_: IllegalArgumentException) {
                    currentSnapshot = UserLocationSnapshot()
                }
            }
        } catch (_: SecurityException) {
            currentSnapshot = UserLocationSnapshot()
        } catch (_: IllegalArgumentException) {
            currentSnapshot = UserLocationSnapshot()
        }
    }

    fun stop() {
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: SecurityException) {
            // Ignore; stopping location updates must never break Activity shutdown.
        }
    }

    private fun handleLocation(location: Location) {
        if (!location.hasAccuracy() || location.accuracy > MAX_ACCEPTABLE_ACCURACY_METERS) {
            currentSnapshot = UserLocationSnapshot(
                motionState = UserMotionState.UNKNOWN,
                accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                timestampMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
            return
        }

        val speed = resolveSpeed(location)
        val averagedSpeed = speed?.let(::recordAndAverageSpeed)
        val motionState = when {
            averagedSpeed == null -> UserMotionState.UNKNOWN
            averagedSpeed >= MOVING_SPEED_THRESHOLD_MPS -> UserMotionState.MOVING
            averagedSpeed < STATIONARY_SPEED_THRESHOLD_MPS -> UserMotionState.STATIONARY
            else -> UserMotionState.UNKNOWN
        }

        currentSnapshot = UserLocationSnapshot(
            motionState = motionState,
            speedMps = averagedSpeed,
            accuracyMeters = location.accuracy,
            timestampMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
    }

    private fun resolveSpeed(location: Location): Float? {
        if (location.hasSpeed()) {
            addRecentLocation(location)
            return location.speed
        }

        val previous = recentLocations.lastOrNull()
        addRecentLocation(location)
        if (previous == null) return null

        val deltaTimeSec = ((location.time - previous.time) / 1_000f).takeIf { it > 0f } ?: return null
        return previous.distanceTo(location) / deltaTimeSec
    }

    private fun addRecentLocation(location: Location) {
        recentLocations.addLast(location)
        while (recentLocations.size > MAX_LOCATION_HISTORY_SIZE) {
            recentLocations.removeFirst()
        }
    }

    private fun recordAndAverageSpeed(speedMps: Float): Float {
        recentSpeeds.addLast(speedMps)
        while (recentSpeeds.size > MAX_SPEED_HISTORY_SIZE) {
            recentSpeeds.removeFirst()
        }
        return recentSpeeds.sum() / recentSpeeds.size.coerceAtLeast(1)
    }

    private fun hasLocationPermission(): Boolean {
        return hasFineLocationPermission() || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        private const val LOCATION_UPDATE_INTERVAL_MS = 1_500L
        private const val MIN_LOCATION_DISTANCE_METERS = 0f
        private const val MAX_ACCEPTABLE_ACCURACY_METERS = 15f
        private const val MOVING_SPEED_THRESHOLD_MPS = 0.5f
        private const val STATIONARY_SPEED_THRESHOLD_MPS = 0.3f
        private const val MAX_LOCATION_HISTORY_SIZE = 3
        private const val MAX_SPEED_HISTORY_SIZE = 3
    }
}
