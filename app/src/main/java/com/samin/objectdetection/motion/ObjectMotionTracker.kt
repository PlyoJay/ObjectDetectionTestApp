package com.samin.objectdetection.motion

import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.location.UserMotionState
import kotlin.math.hypot

class ObjectMotionTracker(
    private val maxHistorySize: Int = 5,
    private val minHistorySize: Int = 3,
    private val minAbsoluteAreaChange: Float = 0.005f,
    private val minRelativeAreaChangeRatio: Float = 0.15f,
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
        timestampMs: Long = System.currentTimeMillis(),
        userMotionState: UserMotionState = UserMotionState.UNKNOWN
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

            val motionDirection = estimateDirection(track.records, userMotionState)
            detection.copy(
                motionDirection = motionDirection,
                approachSpeedLevel = estimateApproachSpeedLevel(track.records, motionDirection),
                objectMovementState = estimateObjectMovementState(track.records, motionDirection, userMotionState)
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

    private fun estimateDirection(
        records: List<MotionSnapshot>,
        userMotionState: UserMotionState
    ): MotionDirection {
        if (records.size < minHistorySize) return MotionDirection.UNKNOWN

        val areaChange = calculateAreaChange(records)
        val bboxDirection = estimateDirectionFromAreaChange(areaChange)

        if (userMotionState == UserMotionState.UNKNOWN) {
            return bboxDirection
        }

        if (
            areaChange.isIncreasing &&
            (userMotionState == UserMotionState.MOVING || userMotionState == UserMotionState.STATIONARY)
        ) {
            return MotionDirection.APPROACHING
        }

        return bboxDirection
    }

    private fun estimateDirectionFromAreaChange(areaChange: AreaChange): MotionDirection {
        // Require both absolute and relative area changes to reduce false motion from YOLO bbox jitter.
        return when {
            areaChange.isIncreasing -> MotionDirection.APPROACHING
            areaChange.isDecreasing -> MotionDirection.LEAVING
            else -> MotionDirection.STABLE
        }
    }

    private fun calculateAreaChange(records: List<MotionSnapshot>): AreaChange {
        val first = records.first()
        val last = records.last()
        val areaDelta = last.areaRatio - first.areaRatio
        val relativeChangeRatio = areaDelta / first.areaRatio.coerceAtLeast(MIN_RELATIVE_AREA_BASE)

        return AreaChange(
            areaDelta = areaDelta,
            relativeChangeRatio = relativeChangeRatio,
            isIncreasing = areaDelta >= minAbsoluteAreaChange &&
                relativeChangeRatio >= minRelativeAreaChangeRatio,
            isDecreasing = areaDelta <= -minAbsoluteAreaChange &&
                relativeChangeRatio <= -minRelativeAreaChangeRatio
        )
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

    private fun estimateObjectMovementState(
        records: List<MotionSnapshot>,
        motionDirection: MotionDirection,
        userMotionState: UserMotionState
    ): ObjectMovementState {
        if (records.size < minHistorySize) return ObjectMovementState.UNKNOWN
        if (userMotionState == UserMotionState.UNKNOWN) return ObjectMovementState.UNKNOWN

        val areaChange = calculateAreaChange(records)
        return when (userMotionState) {
            UserMotionState.STATIONARY -> {
                if (motionDirection == MotionDirection.APPROACHING || areaChange.isDecreasing) {
                    ObjectMovementState.MOVING_OBJECT
                } else {
                    ObjectMovementState.STATIC_OBJECT
                }
            }
            UserMotionState.MOVING -> {
                if (motionDirection == MotionDirection.STABLE || motionDirection == MotionDirection.APPROACHING) {
                    ObjectMovementState.STATIC_OBJECT
                } else {
                    ObjectMovementState.UNKNOWN
                }
            }
            UserMotionState.UNKNOWN -> ObjectMovementState.UNKNOWN
        }
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

    private data class AreaChange(
        val areaDelta: Float,
        val relativeChangeRatio: Float,
        val isIncreasing: Boolean,
        val isDecreasing: Boolean
    )
}

private const val FAST_AREA_VELOCITY = 0.05f
private const val MEDIUM_AREA_VELOCITY = 0.02f
private const val SLOW_AREA_VELOCITY = 0.005f
private const val MIN_RELATIVE_AREA_BASE = 0.0001f
