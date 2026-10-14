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

class PerformanceLogSessionTest {
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
