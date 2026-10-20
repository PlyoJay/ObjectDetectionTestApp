package com.samin.objectdetection.ui

import com.samin.objectdetection.metrics.FrameTrace
import org.junit.Assert.*
import org.junit.Test

class OverlayFrameStateTest {
    private fun ms(value: Long) = value * 1_000_000L
    private fun trace(id: Long = 1, captureMs: Long = 1000, receivedMs: Long = 1100, reliable: Boolean = true) =
        FrameTrace("session", id, 123, ms(captureMs), reliable, 456, ms(receivedMs), completedElapsedNs = ms(1800))

    @Test fun freshResultUsesCaptureTimeAndOnlyRemainingLifetime() {
        val state = OverlayFrameState<String>(1500)
        assertEquals(811L, trace().captureAgeMsAt(ms(1811)))
        assertEquals(OverlayFrameState.Update.ACCEPTED, state.update("box", 720, 1280, trace(), ms(1811)))
        assertEquals(811L, state.frame!!.ageMs(ms(1811)))
        assertEquals(689L, state.remainingDelayMs(ms(1811)))
        assertNull(state.expire(ms(2499)))
        assertNotNull(state.expire(ms(2500))) // Exact boundary is expired, with no further update needed.
        assertNull(state.frame)
    }

    @Test fun oldResultsAreRejectedEvenWhenNewResultsKeepArriving() {
        val state = OverlayFrameState<String>(1500)
        state.update("fresh", 1, 1, trace(), ms(1800))
        for (id in 2L..10L) {
            assertEquals(OverlayFrameState.Update.EXPIRED_AT_UPDATE,
                state.update("old-$id", 1, 1, trace(id, captureMs = 1000 + id), ms(3000 + id)))
            assertNull(state.frame)
            assertNull(state.remainingDelayMs(ms(3000 + id)))
        }
    }

    @Test fun olderOrDuplicateResultCannotReplaceNewerResultOrExtendItsDeadline() {
        val state = OverlayFrameState<String>(1500)
        state.update("new", 720, 1280, trace(5), ms(1800))
        assertEquals(OverlayFrameState.Update.OUT_OF_ORDER, state.update("old", 1, 1, trace(4), ms(1900)))
        assertEquals(OverlayFrameState.Update.OUT_OF_ORDER, state.update("duplicate", 1, 1, trace(5), ms(2000)))
        assertEquals("new", state.frame!!.value)
        assertEquals(500L, state.remainingDelayMs(ms(2000)))
        state.expire(ms(2500))
        assertEquals(OverlayFrameState.Update.OUT_OF_ORDER, state.update("revive", 1, 1, trace(5), ms(2600)))
        assertNull(state.frame)
    }

    @Test fun yoloAndMlKitExpireAndKeepDimensionsIndependently() {
        val yolo = OverlayFrameState<String>(1500)
        val mlKit = OverlayFrameState<String>(1500)
        yolo.update("yolo", 720, 1280, trace(4), ms(1800))
        mlKit.update("mlkit", 1080, 1920, trace(2, 1600, 1700), ms(1800))
        assertNotNull(yolo.expire(ms(2500)))
        assertNull(mlKit.expire(ms(2500)))
        assertEquals(1080, mlKit.frame!!.width)
        assertEquals(600L, mlKit.remainingDelayMs(ms(2500)))
        assertNotNull(mlKit.expire(ms(3100)))
    }

    @Test fun unreliableCameraUsesAnalyzerReceptionWithoutRenewingAtUiUpdate() {
        val state = OverlayFrameState<String>(1500)
        assertEquals(-1L, trace(reliable = false).captureAgeMsAt(ms(1800)))
        assertEquals(OverlayFrameState.Update.ACCEPTED,
            state.update("box", 1, 1, trace(captureMs = Long.MAX_VALUE / 1_000_000, reliable = false), ms(1800)))
        assertEquals("ANALYZER_RECEIVED", state.frame!!.timeBasis)
        assertEquals(800L, state.remainingDelayMs(ms(1800)))
        assertNotNull(state.expire(ms(2600)))
    }

    @Test fun missingOrFutureCameraTimeFallsBackAndInvalidAnalyzerTimeIsRejected() {
        for (cameraMs in listOf(0L, 1200L, 9000L)) {
            val state = OverlayFrameState<String>(1500)
            assertEquals(-1L, trace(captureMs = cameraMs).captureAgeMsAt(ms(1800)))
            assertEquals(OverlayFrameState.Update.ACCEPTED, state.update("box", 1, 1, trace(captureMs = cameraMs), ms(1800)))
            assertEquals("ANALYZER_RECEIVED", state.frame!!.timeBasis)
        }
        for (receivedMs in listOf(0L, -1L, 9000L)) {
            val state = OverlayFrameState<String>(1500)
            assertEquals(OverlayFrameState.Update.INVALID_TIME,
                state.update("box", 1, 1, trace(receivedMs = receivedMs, reliable = false), ms(1800)))
            assertNull(state.frame)
        }
    }

    @Test fun offOnClearsBoxesAndRejectsResultsCapturedBeforeReenable() {
        val state = OverlayFrameState<String>(1500)
        state.update("old", 1, 1, trace(), ms(1800))
        state.configure(false, 1500, ms(1900))
        assertNull(state.frame)
        assertEquals(OverlayFrameState.Update.DISABLED, state.update("off", 1, 1, trace(2, 1950, 1960), ms(2000)))
        state.configure(true, 1500, ms(2100))
        assertEquals(OverlayFrameState.Update.BEFORE_RESET, state.update("queued", 1, 1, trace(3, 2000, 2050), ms(2200)))
        assertEquals(OverlayFrameState.Update.ACCEPTED, state.update("on", 1, 1, trace(4, 2150, 2160), ms(2300)))
    }

    @Test fun timeoutChangesApplyToExistingCaptureAndNeverReviveExpiredPayload() {
        val state = OverlayFrameState<String>(1500)
        state.update("box", 1, 1, trace(), ms(1800))
        state.configure(true, 1000, ms(1900))
        assertEquals(100L, state.remainingDelayMs(ms(1900)))
        state.configure(true, 3000, ms(2000))
        assertNull(state.frame)
    }

    @Test fun shorteningTimeoutExpiresImmediatelyAndClearRejectsInFlightFrames() {
        val state = OverlayFrameState<String>(1500)
        state.update("box", 1, 1, trace(), ms(1800))
        state.configure(true, 500, ms(1900))
        assertNull(state.frame)
        state.clear(ms(2000))
        assertEquals(OverlayFrameState.Update.BEFORE_RESET,
            state.update("in-flight", 1, 1, trace(2, 1950, 1960), ms(2100)))
        val recreated = OverlayFrameState<String>(1500)
        assertNull(recreated.frame)
        assertEquals(OverlayFrameState.Update.ACCEPTED, recreated.update("new session", 1, 1, trace(), ms(1800)))
    }

    @Test fun emptyResultReplacesOldBoxesAndEarlyTimerRetainsRemainder() {
        val state = OverlayFrameState<List<String>>(1500)
        state.update(listOf("box"), 1, 1, trace(), ms(1800))
        state.update(emptyList(), 1, 1, trace(2, 1200, 1300), ms(1900))
        assertTrue(state.frame!!.value.isEmpty())
        assertNull(state.expire(ms(2699) + 1))
        assertEquals(1L, state.remainingDelayMs(ms(2699) + 1))
        assertNotNull(state.expire(ms(2700)))
    }
}
