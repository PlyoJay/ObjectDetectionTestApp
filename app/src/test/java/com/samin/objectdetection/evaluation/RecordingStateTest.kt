package com.samin.objectdetection.evaluation

import org.junit.Assert.*
import org.junit.Test

class RecordingStateTest {
    @Test fun oldStartTimeoutDoesNotAbortALaterStopHandshake() {
        val state = RecordingState()
        state.begin("a")
        assertTrue(state.isAwaiting("a", RecordingState.Phase.STARTING))
        state.started("a"); state.requestStop()
        assertFalse(state.isAwaiting("a", RecordingState.Phase.STARTING))
        assertTrue(state.isAwaiting("a", RecordingState.Phase.STOPPING))
    }
    @Test fun uiStateWaitsForStartStopAndLogDrainAcknowledgements() {
        val state = RecordingState()
        assertTrue(state.begin("a")); assertFalse(state.acceptingFrames)
        assertTrue(state.started("a")); assertTrue(state.acceptingFrames)
        assertTrue(state.requestStop()); assertTrue(state.busy); assertTrue(state.acceptingFrames)
        assertTrue(state.finalizing("a")); assertTrue(state.busy); assertFalse(state.acceptingFrames)
        assertTrue(state.finished("a")); assertFalse(state.busy)
    }
    @Test fun lateEventsCannotFinishNewSessionOrUndoPendingStop() {
        val state = RecordingState()
        state.begin("old"); state.finalizing("old"); state.finished("old")
        assertTrue(state.begin("new")); assertFalse(state.begin("duplicate"))
        assertFalse(state.started("old")); assertFalse(state.finalizing("old"))
        state.requestStop()
        assertFalse(state.started("new"))
        assertEquals(RecordingState.Phase.STOPPING, state.phase)
        assertTrue(state.finalizing("new")); assertFalse(state.finalizing("new"))
    }
}
