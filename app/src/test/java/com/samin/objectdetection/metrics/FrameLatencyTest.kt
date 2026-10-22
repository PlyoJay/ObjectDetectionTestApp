package com.samin.objectdetection.metrics

import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class FrameLatencyTest {
    private fun source(frameId: Long = 7, receivedNs: Long = 1_000_000_001L) =
        FrameTrace("test-session", frameId, 0, receivedNs - 1, true, 0, receivedNs)

    private fun snapshot() = DetectionMetricsCollector().stopPerformanceSessionSnapshot(stoppedElapsedNs = 2_000_000_000L)

    @Test fun analyzerCloseRunsOnceAndExitObservationFollowsClose() {
        var clock = 100L
        val events = mutableListOf<FrameLatencyEvent>()
        val recorder = FrameLatencyRecorder(source(), { ++clock }, events::add)
        var closes = 0
        finishAnalyzerFrame(recorder, "INTERVAL_SKIPPED") { closes++; clock += 10 }
        val event = events.single() as FrameLatencyEvent.Analyzer
        assertEquals(1, closes)
        assertTrue(event.closeSucceeded)
        assertEquals("INTERVAL_SKIPPED", event.outcome)
        assertTrue(event.workEndedElapsedNs <= event.closeStartedElapsedNs)
        assertTrue(event.closeStartedElapsedNs < event.closeEndedElapsedNs)
        assertTrue(event.closeEndedElapsedNs <= event.observedElapsedNs)
    }

    @Test fun failedCloseAndApiExceptionAreObservedWithoutChangingTheirExceptions() {
        val failure = IllegalStateException("original")
        val events = mutableListOf<FrameLatencyEvent>()
        var clock = 100L
        val recorder = FrameLatencyRecorder(source(), { ++clock }, events::add)
        try { finishAnalyzerFrame(recorder, "ERROR") { throw failure }; fail() }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertFalse((events.single() as FrameLatencyEvent.Analyzer).closeSucceeded)
        try { observeOutputRequest(recorder, OutputKind.BEEP) { throw failure }; fail() }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
        val api = events.last() as FrameLatencyEvent.Output
        assertNull(api.accepted)
        assertEquals("API_EXCEPTION", api.reason)
        assertEquals(-1L, api.startedElapsedNs)
    }

    @Test fun loggingOffCallsOriginalOperationsAndRecordingFailureCannotBreakThem() {
        var calls = 0
        finishAnalyzerFrame(null, "DELIVERED") { calls++ }
        assertFalse(observeOutputRequest(null, OutputKind.VIBRATION) { calls++; false })
        val recorder = FrameLatencyRecorder(source(), { 100 }, { error("writer unavailable") })
        assertTrue(observeOutputRequest(recorder, OutputKind.BEEP) { calls++; true })
        assertEquals(3, calls)
    }

    @Test fun beepAndVibrationApiRequestsNeverFabricateActualStartAndRetainPulseNumbers() {
        val events = mutableListOf<FrameLatencyEvent>()
        var clock = 100L
        val recorder = FrameLatencyRecorder(source(), { ++clock }, events::add)
        assertTrue(observeOutputRequest(recorder, OutputKind.BEEP, 2) { clock += 5; true })
        assertTrue(observeOutputRequest(recorder, OutputKind.VIBRATION) { true })
        val beep = events.first() as FrameLatencyEvent.Output
        assertEquals(2, beep.pulseIndex)
        assertEquals(-1L, beep.startedElapsedNs)
        assertTrue(beep.requestReturnedElapsedNs > beep.requestStartedElapsedNs)
        events.forEach {
            val text = PerformanceLogFormatter.formatLatency(source(), it)
            assertTrue(text.contains("physicalOutputStartedElapsedNs=-1"))
            assertTrue(text.contains("outputStartedElapsedNs=-1"))
        }
    }

    @Test fun ttsCallbackCanPrecedeSpeakReturnIsCorrelatedOnceAndKeepsOriginalFrame() {
        var clock = 100L
        val events = mutableListOf<FrameLatencyEvent>()
        val recorder = FrameLatencyRecorder(source(42), { ++clock }, events::add)
        val tracker = TtsStartTracker({ ++clock })
        val request = tracker.register("unique-utterance", recorder)
        tracker.onStart(request.id) // Engines may call back before speak() returns.
        tracker.requestReturned(request, true)
        tracker.onStart(request.id)
        tracker.terminal(request.id, "DONE")
        tracker.onStart("unknown")
        assertEquals(2, events.size)
        val started = events.first() as FrameLatencyEvent.Output
        assertEquals("TTS_ON_START", started.observation)
        assertEquals(started.observedElapsedNs, started.startedElapsedNs)
        assertEquals(request.requestedElapsedNs, started.requestStartedElapsedNs)
        assertTrue(PerformanceLogFormatter.formatLatency(recorder.trace, started).contains("sessionId=test-session frameId=42"))
    }

    @Test fun ttsStopErrorsOmittedCallbacksAndEvictionNeverInventStartOrKeepUnboundedAssociations() {
        var clock = 100L
        val events = mutableListOf<FrameLatencyEvent>()
        val recorder = FrameLatencyRecorder(source(), { ++clock }, events::add)
        val tracker = TtsStartTracker({ ++clock }, capacity = 2)
        tracker.register("evicted", recorder)
        tracker.register("stopped", recorder)
        tracker.register("error", recorder)
        tracker.onStart("evicted")
        tracker.terminal("stopped", "TTS_STOPPED_OR_FLUSHED")
        tracker.terminal("error", "TTS_ERROR")
        tracker.register("omitted", recorder)
        tracker.clear("RELEASE_WITHOUT_START")
        tracker.onStart("omitted")
        assertEquals(4, events.size)
        val outputs = events.map { it as FrameLatencyEvent.Output }
        assertTrue(outputs.all { it.observation == "TTS_START_UNAVAILABLE" && it.startedElapsedNs == -1L })
        assertEquals(listOf("ASSOCIATION_EVICTED", "TTS_STOPPED_OR_FLUSHED", "TTS_ERROR", "RELEASE_WITHOUT_START"), outputs.map { it.reason })
    }

    @Test fun failedTtsRequestIsRemovedAndLateCallbackCannotBeMisattributed() {
        var clock = 100L
        val events = mutableListOf<FrameLatencyEvent>()
        val recorder = FrameLatencyRecorder(source(), { ++clock }, events::add)
        val tracker = TtsStartTracker({ ++clock })
        val failed = tracker.register("failed", recorder)
        tracker.requestReturned(failed, false)
        tracker.onStart(failed.id)
        tracker.clear("STOP")
        assertEquals(1, events.size)
        assertEquals(false, (events.single() as FrameLatencyEvent.Output).accepted)
    }

    @Test fun simultaneousTtsStartCallbacksEmitExactlyOneObservation() {
        val events = ConcurrentLinkedQueue<FrameLatencyEvent>()
        val recorder = FrameLatencyRecorder(source(), { 100L }, { events.add(it) })
        val tracker = TtsStartTracker({ 100L })
        tracker.register("once", recorder)
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        try {
            repeat(8) { executor.execute { try { start.await(); tracker.onStart("once") } finally { done.countDown() } } }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(1, events.size)
        } finally { start.countDown(); executor.shutdownNow() }
    }

    @Test fun fullWriterQueueDropsTelemetryWithoutBlockingOrCorruptingFrameStatistics() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = StringWriter()
        val session = PerformanceLogSession(SystemPerformanceDiagnosticsTest.header(false), BufferedWriter(output),
            null, { 2_000_000_000L }, { 0 }, queueCapacity = 1)
        try {
            session.append { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); "BUSY" }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val recorder = checkNotNull(session.beginFrameLatency(source())) // receipt event drops
            recorder.record(FrameLatencyEvent.Warning(1_100_000_000, 1_200_000_000, 1_300_000_000, false, false, "candidate_none"))
            session.recordFrame(PerformanceIntervalCollectorTest.frame(100).copy(trace = source().copy(completedElapsedNs = 1_500_000_000L)))
            assertEquals(3L, session.droppedLogLines)
            val closed = session.close(snapshot())
            assertFalse(closed.isDone)
            release.countDown()
            closed.get(5, TimeUnit.SECONDS)
            assertTrue(output.toString().contains("successfulFrames=1 inferenceAvgMs=100.000"))
        } finally { release.countDown(); session.close(snapshot()).get(5, TimeUnit.SECONDS) }
    }

    @Test fun sessionPinsAsyncEventsToOriginalFileAndExcludesOutsideWindowWithoutChangingFrameCounts() {
        var clock = 1_000_000_000L
        val header = SystemPerformanceDiagnosticsTest.header(false).copy(startedElapsedNs = clock, durationMinutes = 3)
        val first = StringWriter()
        val session = PerformanceLogSession(header, BufferedWriter(first), null, { clock }, { 0 })
        assertNull(session.beginFrameLatency(source(receivedNs = clock - 1)))
        assertNull(session.beginFrameLatency(source().copy(sessionId = "other-session")))
        val recorder = checkNotNull(session.beginFrameLatency(source()))
        clock += 100_000_000
        recorder.record(FrameLatencyEvent.Warning(clock - 2, clock - 1, clock, true, true, null))
        session.recordFrame(PerformanceIntervalCollectorTest.frame(100).copy(trace = source().copy(completedElapsedNs = clock)))
        recorder.record(FrameLatencyEvent.MlKit(clock, clock, header.startedElapsedNs + 180_000_000_000L,
            "SUCCESS", "TASK_COMPLETION_LISTENER")) // deadline is exclusive
        session.close(snapshot()).get(5, TimeUnit.SECONDS)

        val second = StringWriter()
        val next = PerformanceLogSession(header.copy(startedElapsedNs = clock), BufferedWriter(second), null, { clock }, { 0 })
        recorder.record(FrameLatencyEvent.Output(OutputKind.TTS, "TTS_ON_START", clock, startedElapsedNs = clock))
        next.close(snapshot()).get(5, TimeUnit.SECONDS)
        assertTrue(first.toString().contains("stage=CAMERA_RECEIVED"))
        assertTrue(first.toString().contains("stage=WARNING"))
        assertTrue(first.toString().contains("successfulFrames=1"))
        assertFalse(first.toString().contains("stage=MLKIT"))
        assertFalse(first.toString().contains("stage=OUTPUT"))
        assertFalse(second.toString().contains("LATENCY |"))
        assertTrue(second.toString().contains("successfulFrames=0"))
    }

    @Test fun latencyFormatterUsesOneLineAndExplicitUnavailableFields() {
        val events = listOf(
            FrameLatencyEvent.Received(100),
            FrameLatencyEvent.Analyzer(110, 111, 112, 113, false, "ERROR"),
            FrameLatencyEvent.MlKit(120, -1, 121, "ERROR", "BEFORE_PROCESS_REQUEST"),
            FrameLatencyEvent.Warning(130, 131, 132, false, false, "candidate_none"),
            FrameLatencyEvent.Output(OutputKind.TTS, "TTS_START_UNAVAILABLE", 140)
        )
        events.forEach {
            val line = PerformanceLogFormatter.formatLatency(source(), it)
            assertTrue(line.startsWith("LATENCY | sessionId=test-session frameId=7"))
            assertFalse(line.contains('\n'))
            val keys = Regex("(?:^|[ |])(\\w+)=").findAll(line).map { m -> m.groupValues[1] }.toList()
            assertEquals(keys.size, keys.distinct().size)
        }
        assertTrue(PerformanceLogFormatter.formatLatency(source(), events.last()).contains("accepted=N/A"))
    }
}
