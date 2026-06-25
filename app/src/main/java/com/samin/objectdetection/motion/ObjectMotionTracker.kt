package com.samin.objectdetection.motion

import com.samin.objectdetection.detector.DetectionResult
import com.samin.objectdetection.location.UserMotionState
import kotlin.math.hypot

class ObjectMotionTracker(
    private val maxHistorySize: Int = 5,
    private val minHistorySize: Int = 3,
    private val minAbsoluteAreaChange: Float = 0.005f,
    private val minRelativeAreaChangeRatio: Float = 0.15f,
    private val minAbsoluteHeightChange: Float = 0.03f,
    private val minRelativeHeightChangeRatio: Float = 0.10f,
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

            val areaChange = calculateAreaChange(track.records)
            val userObjectRelation = estimateUserObjectRelation(areaChange, userMotionState)
            val motionDirection = estimateDirection(areaChange, userMotionState, userObjectRelation)
            detection.copy(
                motionDirection = motionDirection,
                approachSpeedLevel = estimateApproachSpeedLevel(track.records, motionDirection),
                objectMovementState = estimateObjectMovementState(
                    track.records,
                    motionDirection,
                    userMotionState,
                    userObjectRelation
                ),
                userObjectRelation = userObjectRelation
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
        areaChange: AreaChange,
        userMotionState: UserMotionState,
        userObjectRelation: UserObjectRelation
    ): MotionDirection {
        if (userMotionState == UserMotionState.UNKNOWN) {
            return estimateDirectionFromAreaChange(areaChange)
        }

        return when (userObjectRelation) {
            UserObjectRelation.OBJECT_APPROACHING_USER -> MotionDirection.APPROACHING
            UserObjectRelation.OBJECT_LEAVING_USER -> MotionDirection.LEAVING
            UserObjectRelation.USER_APPROACHING_OBJECT,
            UserObjectRelation.USER_LEAVING_OBJECT,
            UserObjectRelation.STABLE_OR_DISTANT -> MotionDirection.STABLE
            UserObjectRelation.UNKNOWN -> MotionDirection.UNKNOWN
        }
    }

    private fun estimateDirectionFromAreaChange(areaChange: AreaChange): MotionDirection {
        if (!areaChange.hasEnoughSamples) return MotionDirection.UNKNOWN

        // Require both absolute and relative area changes to reduce false motion from YOLO bbox jitter.
        return when {
            areaChange.isIncreasing -> MotionDirection.APPROACHING
            areaChange.isDecreasing -> MotionDirection.LEAVING
            else -> MotionDirection.STABLE
        }
    }

    private fun calculateAreaChange(records: List<MotionSnapshot>): AreaChange {
        if (records.size < minHistorySize) {
            return AreaChange(
                areaDelta = 0f,
                relativeChangeRatio = 0f,
                isIncreasing = false,
                isDecreasing = false,
                hasEnoughSamples = false
            )
        }

        val first = records.first()
        val last = records.last()
        val areaDelta = last.areaRatio - first.areaRatio
        val relativeChangeRatio = areaDelta / first.areaRatio.coerceAtLeast(MIN_RELATIVE_AREA_BASE)
        val heightDelta = last.heightRatio - first.heightRatio
        val relativeHeightChangeRatio = heightDelta / first.heightRatio.coerceAtLeast(MIN_RELATIVE_AREA_BASE)
        val isAreaIncreasing = areaDelta >= minAbsoluteAreaChange &&
            relativeChangeRatio >= minRelativeAreaChangeRatio
        val isAreaDecreasing = areaDelta <= -minAbsoluteAreaChange &&
            relativeChangeRatio <= -minRelativeAreaChangeRatio
        val isHeightIncreasing = heightDelta >= minAbsoluteHeightChange &&
            relativeHeightChangeRatio >= minRelativeHeightChangeRatio
        val isHeightDecreasing = heightDelta <= -minAbsoluteHeightChange &&
            relativeHeightChangeRatio <= -minRelativeHeightChangeRatio

        return AreaChange(
            areaDelta = areaDelta,
            relativeChangeRatio = relativeChangeRatio,
            isIncreasing = isAreaIncreasing || isHeightIncreasing,
            isDecreasing = isAreaDecreasing || isHeightDecreasing,
            hasEnoughSamples = true
        )
    }

    private fun estimateUserObjectRelation(
        areaChange: AreaChange,
        userMotionState: UserMotionState
    ): UserObjectRelation {
        if (!areaChange.hasEnoughSamples) return UserObjectRelation.UNKNOWN

        return when (userMotionState) {
            UserMotionState.MOVING -> when {
                areaChange.isIncreasing -> UserObjectRelation.USER_APPROACHING_OBJECT
                areaChange.isDecreasing -> UserObjectRelation.USER_LEAVING_OBJECT
                else -> UserObjectRelation.STABLE_OR_DISTANT
            }
            UserMotionState.STATIONARY -> when {
                areaChange.isIncreasing -> UserObjectRelation.OBJECT_APPROACHING_USER
                areaChange.isDecreasing -> UserObjectRelation.OBJECT_LEAVING_USER
                else -> UserObjectRelation.STABLE_OR_DISTANT
            }
            UserMotionState.UNKNOWN -> UserObjectRelation.UNKNOWN
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

    private fun estimateObjectMovementState(
        records: List<MotionSnapshot>,
        motionDirection: MotionDirection,
        userMotionState: UserMotionState,
        userObjectRelation: UserObjectRelation
    ): ObjectMovementState {
        if (records.size < minHistorySize) return ObjectMovementState.UNKNOWN
        if (userMotionState == UserMotionState.UNKNOWN) return ObjectMovementState.UNKNOWN

        return when (userObjectRelation) {
            UserObjectRelation.USER_APPROACHING_OBJECT,
            UserObjectRelation.USER_LEAVING_OBJECT,
            UserObjectRelation.STABLE_OR_DISTANT -> ObjectMovementState.STATIONARY_LIKELY
            UserObjectRelation.OBJECT_APPROACHING_USER -> {
                if (motionDirection == MotionDirection.UNKNOWN) {
                    ObjectMovementState.UNKNOWN
                } else {
                    ObjectMovementState.APPROACHING_USER
                }
            }
            UserObjectRelation.OBJECT_LEAVING_USER -> {
                if (motionDirection == MotionDirection.UNKNOWN) {
                    ObjectMovementState.UNKNOWN
                } else {
                    ObjectMovementState.LEAVING_USER
                }
            }
            UserObjectRelation.UNKNOWN -> ObjectMovementState.UNKNOWN
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
            heightRatio = boxHeight / frameHeight.coerceAtLeast(1).toFloat(),
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
        val heightRatio: Float,
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
        val isDecreasing: Boolean,
        val hasEnoughSamples: Boolean
    )
}

private const val FAST_AREA_VELOCITY = 0.05f
private const val MEDIUM_AREA_VELOCITY = 0.02f
private const val SLOW_AREA_VELOCITY = 0.005f
private const val MIN_RELATIVE_AREA_BASE = 0.0001f
