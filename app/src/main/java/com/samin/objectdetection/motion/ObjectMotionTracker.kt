package com.samin.objectdetection.motion

import com.samin.objectdetection.detector.DetectionResult
import kotlin.math.hypot

class ObjectMotionTracker(
    private val maxHistorySize: Int = 5,
    private val minHistorySize: Int = 3,
    private val areaChangeThreshold: Float = 0.015f,
    private val maxMatchDistanceRatio: Float = 0.18f,
    private val minSampleIntervalMs: Long = 500L,
    private val staleTrackTimeoutMs: Long = 2_000L
) {
    private val tracks = mutableListOf<TrackedObject>()
    private var nextTrackId = 1L

    fun update(
        detections: List<DetectionResult>,
        frameWidth: Int,
        frameHeight: Int,
        timestampMs: Long = System.currentTimeMillis()
    ): List<DetectionResult> {
        if (detections.isEmpty()) {
            removeStaleTracks(timestampMs)
            return emptyList()
        }

        val frameDiagonal = hypot(
            frameWidth.coerceAtLeast(1).toFloat(),
            frameHeight.coerceAtLeast(1).toFloat()
        )
        val maxMatchDistance = frameDiagonal * maxMatchDistanceRatio
        val matchedTrackIds = mutableSetOf<Long>()

        val updated = detections.map { detection ->
            val snapshot = detection.toMotionSnapshot(frameWidth, frameHeight, timestampMs)
            val track = findNearestTrack(snapshot, matchedTrackIds, maxMatchDistance)
                ?: createTrack(snapshot)

            matchedTrackIds.add(track.id)
            addSnapshotIfNeeded(track, snapshot)
            track.lastUpdatedAtMs = timestampMs

            val motionDirection = estimateDirection(track.records)
            detection.copy(
                motionDirection = motionDirection,
                approachSpeedLevel = estimateApproachSpeedLevel(track.records, motionDirection)
            )
        }

        removeStaleTracks(timestampMs)
        return updated
    }

    private fun findNearestTrack(
        snapshot: MotionSnapshot,
        matchedTrackIds: Set<Long>,
        maxMatchDistance: Float
    ): TrackedObject? {
        return tracks
            .asSequence()
            .filter { it.label == snapshot.label && it.id !in matchedTrackIds }
            .map { track -> track to track.records.last().distanceTo(snapshot) }
            .filter { (_, distance) -> distance <= maxMatchDistance }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }

    private fun createTrack(snapshot: MotionSnapshot): TrackedObject {
        return TrackedObject(
            id = nextTrackId++,
            label = snapshot.label,
            lastUpdatedAtMs = snapshot.timestampMs
        ).also { track ->
            track.records.add(snapshot)
            tracks.add(track)
        }
    }

    private fun addSnapshotIfNeeded(track: TrackedObject, snapshot: MotionSnapshot) {
        val lastSnapshot = track.records.lastOrNull()
        if (lastSnapshot != null && snapshot.timestampMs - lastSnapshot.timestampMs < minSampleIntervalMs) {
            return
        }

        track.records.add(snapshot)
        while (track.records.size > maxHistorySize) {
            track.records.removeAt(0)
        }
    }

    private fun estimateDirection(records: List<MotionSnapshot>): MotionDirection {
        if (records.size < minHistorySize) return MotionDirection.UNKNOWN

        val first = records.first()
        val last = records.last()
        val delta = last.areaRatio - first.areaRatio

        return when {
            delta >= areaChangeThreshold -> MotionDirection.APPROACHING
            delta <= -areaChangeThreshold -> MotionDirection.LEAVING
            else -> MotionDirection.STABLE
        }
    }

    private fun estimateApproachSpeedLevel(
        records: List<MotionSnapshot>,
        motionDirection: MotionDirection
    ): ApproachSpeedLevel {
        if (motionDirection != MotionDirection.APPROACHING) return ApproachSpeedLevel.NONE
        if (records.size < minHistorySize) return ApproachSpeedLevel.UNKNOWN

        val areaVelocity = calculateAreaVelocity(records)
        return when {
            areaVelocity >= FAST_AREA_VELOCITY -> ApproachSpeedLevel.FAST
            areaVelocity >= MEDIUM_AREA_VELOCITY -> ApproachSpeedLevel.MEDIUM
            areaVelocity >= SLOW_AREA_VELOCITY -> ApproachSpeedLevel.SLOW
            else -> ApproachSpeedLevel.NONE
        }
    }

    // Relative approach speed from bbox areaRatio changes, not a real m/s speed.
    private fun calculateAreaVelocity(records: List<MotionSnapshot>): Float {
        val first = records.first()
        val last = records.last()
        val deltaTimeSec = ((last.timestampMs - first.timestampMs) / 1_000f).coerceAtLeast(0.001f)
        return (last.areaRatio - first.areaRatio) / deltaTimeSec
    }

    private fun removeStaleTracks(nowMs: Long) {
        tracks.removeAll { nowMs - it.lastUpdatedAtMs > staleTrackTimeoutMs }
    }

    private fun DetectionResult.toMotionSnapshot(
        frameWidth: Int,
        frameHeight: Int,
        timestampMs: Long
    ): MotionSnapshot {
        val boxWidth = (right - left).coerceAtLeast(0f)
        val boxHeight = (bottom - top).coerceAtLeast(0f)
        val imageArea = frameWidth.coerceAtLeast(1) * frameHeight.coerceAtLeast(1).toFloat()
        return MotionSnapshot(
            label = label,
            centerX = left + boxWidth / 2f,
            centerY = top + boxHeight / 2f,
            areaRatio = boxWidth * boxHeight / imageArea,
            timestampMs = timestampMs
        )
    }

    private data class TrackedObject(
        val id: Long,
        val label: String,
        var lastUpdatedAtMs: Long,
        val records: MutableList<MotionSnapshot> = mutableListOf()
    )

    private data class MotionSnapshot(
        val label: String,
        val centerX: Float,
        val centerY: Float,
        val areaRatio: Float,
        val timestampMs: Long
    ) {
        fun distanceTo(other: MotionSnapshot): Float {
            return hypot(centerX - other.centerX, centerY - other.centerY)
        }
    }
}

private const val FAST_AREA_VELOCITY = 0.05f
private const val MEDIUM_AREA_VELOCITY = 0.02f
private const val SLOW_AREA_VELOCITY = 0.005f
