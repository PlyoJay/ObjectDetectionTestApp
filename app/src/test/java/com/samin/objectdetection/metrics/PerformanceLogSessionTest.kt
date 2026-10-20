package com.samin.objectdetection.metrics

import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedWriter
import java.io.IOException
import java.io.StringWriter
import java.io.Writer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.samin.objectdetection.detector.InferenceExecutionInfo

class PerformanceLogSessionTest {
    @Test fun timedSessionsExcludeOutOfWindowFramesAndPersistStopReasonExactlyOnce() {
        for (preview in listOf(false, true)) for (threads in listOf(1, 4)) for (minutes in listOf(3, 5, 10)) {
            var now = 1_000_000_000L
            val header = SystemPerformanceDiagnosticsTest.header(false).copy(startedElapsedNs = now,
                cameraPreviewEnabled = preview, yoloThreads = threads, durationMinutes = minutes)
            val output = StringWriter()
            val session = PerformanceLogSession(header, BufferedWriter(output), null, { now }, { 0 })
            session.start()
            session.recordFrame(PerformanceIntervalCollectorTest.frame(101).copy(trace = trace(1, now - 1)))
            session.recordFrame(PerformanceIntervalCollectorTest.frame(102).copy(trace = trace(2, now + 1)))
            now += minutes * 60_000_000_000L
            session.recordFrame(PerformanceIntervalCollectorTest.frame(103).copy(trace = trace(3, now)))
            session.recordEvent(trace(4, now).copy(status = "ERROR"), InferenceExecutionInfo(cpuThreads = 16, xnnpackRequested = true))
            val closed = session.close(snapshot(), PerformanceStopReason.AUTO_DURATION)
            assertSame(closed, session.close(snapshot(), PerformanceStopReason.MANUAL))
            closed.get(5, TimeUnit.SECONDS)
            val text = output.toString()
            assertTrue(text.contains("inference=102ms"))
            assertFalse(text.contains("inference=101ms"))
            assertFalse(text.contains("inference=103ms"))
            assertFalse(text.contains("ERROR |"))
            assertTrue(text.contains("configuredDurationMs=${minutes * 60_000L} actualDurationMs=${minutes * 60_000L} stopReason=AUTO_DURATION"))
            assertEquals(1, text.lineSequence().count { it.startsWith("TEST_SESSION |") })
        }
    }

    @Test fun manualStopRecordsActualElapsedTimeInsteadOfConfiguredDuration() {
        var now = 1_000_000_000L
        val output = StringWriter()
        val session = PerformanceLogSession(SystemPerformanceDiagnosticsTest.header(false).copy(startedElapsedNs = now),
            BufferedWriter(output), null, { now }, { 0 })
        session.start()
        now += 12_345_000_000L
        session.close(snapshot(), PerformanceStopReason.MANUAL).get(5, TimeUnit.SECONDS)
        assertTrue(output.toString().contains("configuredDurationMs=300000 actualDurationMs=12345 stopReason=MANUAL"))
    }

    @Test fun previewOffRecordsSuccessfulFramesAndDiagnosticsWithoutOverlayEventsOrFailures() {
        val output = StringWriter()
        var now = 1_000_000_000L
        val sampled = CountDownLatch(1)
        val source = object : SystemPerformanceDiagnosticsTest.FakeSource() {
            override fun cpuFrequency(): String? { sampled.countDown(); return "policy0:currentKHz=1400000" }
        }
        val header = SystemPerformanceDiagnosticsTest.header(true).copy(cameraPreviewEnabled = false)
        output.append(PerformanceLogFormatter.formatHeader(header))
        val session = PerformanceLogSession(header, BufferedWriter(output), source, { now }, { 0 })
        val metrics = DetectionMetricsCollector()
        metrics.startPerformanceSession(0, now)
        session.start()
        try {
            assertTrue(sampled.await(5, TimeUnit.SECONDS))
            now = 2_000_000_000L
            metrics.recordFrameReceived(now)
            metrics.recordFrameAnalyzed()
            metrics.recordInferenceSucceeded(now)
            metrics.recordPerformance(10, 100, 5, 115, -1, 1)
            session.recordFrame(PerformanceIntervalCollectorTest.frame(100).copy(
                frameAgeAtOverlayMs = -1, trace = trace(1, now), detectionCount = 2))
            val snapshot = metrics.stopPerformanceSessionSnapshot(1000, now)
            assertEquals(1L, snapshot.successfulFrames)
            assertEquals(0L, snapshot.failedFrames)
            assertEquals(0L, snapshot.droppedFrames)
            assertEquals(0, snapshot.overlayLatencySamples)
            session.close(snapshot).get(5, TimeUnit.SECONDS)
            val text = output.toString()
            assertTrue(text.contains("cameraPreviewEnabled=false"))
            assertTrue(text.contains("NOT_DISPLAYED_PREVIEW_OFF"))
            assertTrue(text.contains("status=SUCCESS"))
            assertTrue(text.contains("successfulFrames=1 inferenceAvgMs=100.000"))
            assertTrue(text.contains("SYSTEM |"))
            assertFalse(text.contains("ERROR |"))
            assertFalse(text.contains("OVERLAY |"))
        } finally { session.close(snapshot()).get(5, TimeUnit.SECONDS) }
    }

    @Test fun recreatedOnOffSessionsKeepExecutionAndStatisticsSeparateAfterDrain() {
        val outputs = listOf(StringWriter(), StringWriter())
        for ((index, enabled) in listOf(true, false).withIndex()) {
            var now = 1_000_000_000L
            val execution = InferenceExecutionInfo(cpuThreads = 4, xnnpackRequested = enabled, lastInference = "SUCCESS")
            val header = SystemPerformanceDiagnosticsTest.header().copy(sessionId = "xnnpack-$enabled", execution = execution)
            val session = PerformanceLogSession(header, BufferedWriter(outputs[index]), null, { now }, { 0 })
            session.start()
            now = 2_000_000_000L
            session.recordFrame(PerformanceIntervalCollectorTest.frame(if (enabled) 546 else 266)
                .copy(execution = execution, trace = trace(1, now).copy(sessionId = header.sessionId)))
            session.close(snapshot()).get(5, TimeUnit.SECONDS)
            // The closed old recorder cannot append a later frame from another configuration.
            session.recordFrame(PerformanceIntervalCollectorTest.frame(999).copy(execution = execution.copy(xnnpackRequested = !enabled)))
        }
        val on = outputs[0].toString(); val off = outputs[1].toString()
        assertTrue(on.contains("sessionId=xnnpack-true")); assertTrue(off.contains("sessionId=xnnpack-false"))
        assertTrue(on.contains("inferenceAvgMs=546.000 inferenceP95Ms=546"))
        assertTrue(off.contains("inferenceAvgMs=266.000 inferenceP95Ms=266"))
        assertTrue(on.contains("cpuThreads=4 xnnpackRequested=true"))
        assertTrue(off.contains("requestedBackend=CPU appliedBackend=CPU cpuThreads=4 xnnpackRequested=false"))
        assertTrue(off.contains("delegateVerification=UNAVAILABLE_JAVA_API"))
        for (text in listOf(on, off)) {
            assertTrue(text.contains("preprocessAvgMs=")); assertTrue(text.contains("pipelineAvgMs="))
            assertTrue(text.contains("totalProcessingAvgMs=")); assertTrue(text.contains("inferenceFps="))
            assertFalse(text.contains("inference=999ms"))
        }
        assertFalse(on.contains("xnnpackRequested=false")); assertFalse(off.contains("xnnpackRequested=true"))
    }

    @Test fun offPreservesFrameOverlayLinksAndFlushesFinalPartialAndExactCumulative() {
        val output = StringWriter(); var now = 1_000_000_000L
        val source = object : SystemPerformanceDiagnosticsTest.FakeSource() {
            override fun thermalStatus(): Int? = error("OFF must not query")
            override fun listenThermal(executor: java.util.concurrent.Executor, onStatus: (Int) -> Unit): AutoCloseable = error("OFF must not register")
        }
        val session = PerformanceLogSession(SystemPerformanceDiagnosticsTest.header(), BufferedWriter(output), source, { now }, { 0 })
        session.start()
        session.recordFrame(PerformanceIntervalCollectorTest.frame(100).copy(trace = trace(42, now)))
        session.append { "OVERLAY | sessionId=test-session frameId=42" }
        now = 31_000_000_000L
        session.recordFrame(PerformanceIntervalCollectorTest.frame(600).copy(trace = trace(43, now)))
        now = 35_000_000_000L
        val done = session.close(snapshot()); done.get(5, TimeUnit.SECONDS)
        assertSame(done, session.close(snapshot()))
        session.recordFrame(PerformanceIntervalCollectorTest.frame(999))
        val text = output.toString()
        assertTrue(text.contains("sessionId=test-session frameId=42 status=SUCCESS"))
        assertTrue(text.contains("OVERLAY | sessionId=test-session frameId=42"))
        assertTrue(text.contains("elapsedSeconds=1.000"))
        assertTrue(text.contains("CUMULATIVE_SUMMARY")); assertTrue(text.contains("inferenceAvgMs=350.000 inferenceP95Ms=600"))
        assertTrue(text.contains("startSeconds=30.000 endSeconds=35.000"))
        assertTrue(text.contains("thermalStatus=N/A thermalHeadroom=N/A"))
        assertFalse(text.contains("SYSTEM |")); assertFalse(text.contains("inference=999ms"))
    }

    @Test fun boundedDiskQueueDoesNotLoseActualFrameStatistics() {
        val output = StringWriter(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val session = PerformanceLogSession(SystemPerformanceDiagnosticsTest.header(), BufferedWriter(output), null,
            { 2_000_000_000L }, { 0 }, queueCapacity = 1)
        try {
            session.append { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); "BLOCKED" }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(20) { session.recordFrame(PerformanceIntervalCollectorTest.frame(100 + it.toLong())) }
            assertEquals(20L, session.droppedLogLines)
            val done = session.close(snapshot()); assertFalse(done.isDone)
            release.countDown(); done.get(5, TimeUnit.SECONDS)
            assertTrue(output.toString().contains("successfulFrames=20 inferenceAvgMs=109.500 inferenceP95Ms=118"))
        } finally { release.countDown(); session.close(snapshot()).get(5, TimeUnit.SECONDS) }
    }

    @Test fun writeFailureStopsDiagnosticsWithoutHangingAndSurfacesExistingFailureCallback() {
        val registered = CountDownLatch(1); val unregistered = CountDownLatch(1); val failures = AtomicInteger()
        val source = object : SystemPerformanceDiagnosticsTest.FakeSource() {
            override fun listenThermal(executor: java.util.concurrent.Executor, onStatus: (Int) -> Unit): AutoCloseable {
                registered.countDown(); return AutoCloseable { unregistered.countDown() }
            }
        }
        val broken = object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) { throw IOException("disk full") }
            override fun flush() {}
            override fun close() {}
        }
        val session = PerformanceLogSession(SystemPerformanceDiagnosticsTest.header(true), BufferedWriter(broken), source,
            { 1_000_000_000L }, { 0 }, { failures.incrementAndGet() })
        session.start()
        assertTrue(registered.await(5, TimeUnit.SECONDS))
        assertTrue(unregistered.await(5, TimeUnit.SECONDS))
        try { session.close(snapshot()).get(5, TimeUnit.SECONDS); fail("Expected I/O failure") }
        catch (error: ExecutionException) { assertTrue(error.cause is IOException) }
        assertEquals(1, failures.get())
    }

    @Test fun enabledLogsMetricsWithoutDetectorDebugOrPngAndRemovesListenerOnNormalClose() {
        val output = StringWriter(); val sampled = CountDownLatch(1); val closed = CountDownLatch(1)
        val source = object : SystemPerformanceDiagnosticsTest.FakeSource() {
            override fun cpuFrequency(): String? { sampled.countDown(); return "policy0:currentKHz=1400000" }
            override fun listenThermal(executor: java.util.concurrent.Executor, onStatus: (Int) -> Unit) = AutoCloseable { closed.countDown() }
        }
        val session = PerformanceLogSession(SystemPerformanceDiagnosticsTest.header(true), BufferedWriter(output), source,
            { 1_000_000_000L }, { 0 })
        session.start()
        assertTrue(sampled.await(5, TimeUnit.SECONDS))
        // close is ordered behind collection and includes any accepted sample.
        session.close(snapshot()).get(5, TimeUnit.SECONDS)
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertTrue(output.toString().contains("THERMAL_STATUS |"))
        assertFalse(output.toString().contains("INPUT_TENSOR")); assertFalse(output.toString().contains("PNG"))
    }

    private fun trace(id: Long, completed: Long) = FrameTrace("test-session", id, 0, 0, false, 0, 0,
        completedAtMs = 0, completedElapsedNs = completed)

    private fun snapshot(): PerformanceSnapshot {
        val stats = PerformanceMetricStats(0, 0)
        return PerformanceSnapshot(35_000, 2, 2, 0, 0, stats, stats, stats, stats, stats, 0, 0, 0, 0)
    }
}
