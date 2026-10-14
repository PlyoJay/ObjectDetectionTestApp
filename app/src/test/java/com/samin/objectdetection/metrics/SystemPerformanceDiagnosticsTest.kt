package com.samin.objectdetection.metrics

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SystemPerformanceDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun processCpuUsageUsesElapsedDeltaAndCanExceedOneHundredPercent() {
        val meter = ProcessCpuUsageMeter()
        assertNull(meter.measure(1_000_000_000, 100).first)
        val result = meter.measure(6_000_000_000, 12_600)
        assertEquals(250.0, result.first!!, 0.0)
        assertEquals(5000.0, result.second!!, 0.0)
        assertNull(meter.measure(7_000_000_000, null).first)
        assertNull(meter.measure(8_000_000_000, 13_000).first)
        assertNull(meter.measure(9_000_000_000, 10).first) // counter reset is not negative utilization
    }

    @Test fun unsupportedDeniedAndNonFiniteMetricsProduceNAAndDoNotStopOtherCollection() {
        val source = object : FakeSource() {
            override fun thermalStatus(): Int? = throw UnsupportedOperationException()
            override fun thermalHeadroom() = Float.NaN
            override fun cpuFrequency(): String? = throw SecurityException()
        }
        var now = 0L
        val reader = SystemMetricsReader(source, { now }, { 1000 }) {}
        val first = reader.sample()
        now = 5_000_000_000
        source.cpu = 100
        val next = reader.sample()
        assertNull(first.thermalStatus); assertNull(first.thermalHeadroom); assertNull(first.cpuFrequency)
        assertNull(first.headroomSampleElapsedNs)
        assertEquals(2.0, next.processCpuUsage!!, 0.0)
        val text = PerformanceLogFormatter.formatSystemSample(header(), next)
        assertTrue(text.contains("thermalStatus=N/A thermalHeadroom=N/A"))
        assertTrue(text.contains("cpuFrequency=N/A"))
    }

    @Test fun headroomIsThrottledIndependentlyOfTheSystemSampleIntervalAndKeepsAge() {
        val source = FakeSource(); var now = 0L
        val reader = SystemMetricsReader(source, { now }, { 0 }) {}
        assertEquals(0.5f, reader.sample().thermalHeadroom)
        for (i in 1..9) { now = i * 1_000_000_000L; assertEquals(0L, reader.sample().headroomSampleElapsedNs) }
        assertEquals(1, source.headroomCalls)
        now = 10_000_000_000L
        assertEquals(now, reader.sample().headroomSampleElapsedNs)
        assertEquals(2, source.headroomCalls)
    }

    @Test fun thermalChangesKeepReceiptTimesAndDeduplicateInitialListenerAndPollingValues() {
        val events = mutableListOf<ThermalStatusEvent>(); var now = 100L
        val reader = SystemMetricsReader(FakeSource(), { now }, { 5000 }) { events += it }
        reader.thermalChanged(0, "listener_receipt"); reader.sample()
        now = 200; reader.thermalChanged(3, "listener_receipt"); reader.thermalChanged(99, "poll")
        assertEquals(3, events.size)
        assertEquals(200L, events[1].elapsedNs)
        assertEquals(0, events[1].previous)
        assertNull(events[2].status)
    }

    @Test fun policyReaderNeverSubstitutesMaximumForUnavailableCurrentClockAndCachesDenials() {
        val root = temporary.newFolder("cpu")
        val policy = File(root, "cpufreq/policy0").apply { mkdirs() }
        val calls = AtomicInteger()
        val reader = CpuFrequencyReader(root) { file ->
            calls.incrementAndGet()
            if (file.name == "scaling_max_freq") "2000000" else throw SecurityException("denied")
        }
        val first = reader.read()!!
        assertTrue(first.contains("currentKHz=N/A")); assertTrue(first.contains("scalingMaxKHz=2000000"))
        val initialCalls = calls.get(); reader.read()
        assertEquals(initialCalls + 1, calls.get()) // only the readable attribute is queried again
        assertTrue(policy.isDirectory)
        assertNull(CpuFrequencyReader(root) { throw SecurityException() }.read())
    }

    @Test fun frequencyFallbackPreservesSourceAndMissingTopologyIsSafe() {
        val root = temporary.newFolder("fallback")
        File(root, "cpu2/cpufreq").mkdirs()
        val reader = CpuFrequencyReader(root) { file -> if (file.name == "cpuinfo_cur_freq") "1200000" else null }
        assertTrue(reader.read()!!.contains("cpu2:currentKHz=1200000,source=cpuinfo_cur_freq"))
        assertNull(CpuFrequencyReader(temporary.newFolder("empty")).read())
    }

    @Test fun offNeverQueriesSourceAndStopIsIdempotent() {
        val ticks = CountDownLatch(1)
        val diagnostics = SystemPerformanceDiagnostics(null, 5000, { 0 }, { 0 }, { fail("OFF") }, { fail("OFF") }, { ticks.countDown() })
        diagnostics.start()
        assertTrue(ticks.await(5, TimeUnit.SECONDS))
        val done = diagnostics.stop(); done.get(5, TimeUnit.SECONDS)
        assertSame(done, diagnostics.stop())
    }

    @Test fun stopWaitsForInFlightSamplingAndUnregistersExactlyOnce() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closed = AtomicInteger()
        val source = object : FakeSource() {
            override fun thermalStatus(): Int { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return 0 }
            override fun listenThermal(executor: Executor, onStatus: (Int) -> Unit) = AutoCloseable { closed.incrementAndGet() }
        }
        val diagnostics = SystemPerformanceDiagnostics(source, 5000, { 0 }, { 0 }, {}, {}, {})
        diagnostics.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val done = diagnostics.stop(); assertFalse(done.isDone)
            release.countDown(); done.get(5, TimeUnit.SECONDS)
            assertEquals(1, closed.get())
        } finally { release.countDown(); diagnostics.stop().get(5, TimeUnit.SECONDS) }
    }

    @Test fun listenerRegistrationFailureFallsBackToPollingAndLateCallbacksAreIgnored() {
        val sampled = CountDownLatch(1)
        val unsupported = object : FakeSource() {
            override fun listenThermal(executor: Executor, onStatus: (Int) -> Unit): AutoCloseable = throw SecurityException()
        }
        val fallback = SystemPerformanceDiagnostics(unsupported, 5000, { 0 }, { 0 }, { sampled.countDown() }, {}, {})
        fallback.start()
        try { assertTrue(sampled.await(5, TimeUnit.SECONDS)) }
        finally { fallback.stop().get(5, TimeUnit.SECONDS) }

        lateinit var callbackExecutor: Executor
        lateinit var callback: (Int) -> Unit
        val events = AtomicInteger(); val registered = CountDownLatch(1)
        val source = object : FakeSource() {
            override fun listenThermal(executor: Executor, onStatus: (Int) -> Unit): AutoCloseable {
                callbackExecutor = executor; callback = onStatus; registered.countDown()
                return AutoCloseable {}
            }
        }
        val diagnostics = SystemPerformanceDiagnostics(source, 5000, { 0 }, { 0 }, {}, { events.incrementAndGet() }, {})
        diagnostics.start()
        assertTrue(registered.await(5, TimeUnit.SECONDS))
        diagnostics.stop().get(5, TimeUnit.SECONDS)
        val before = events.get()
        callbackExecutor.execute { callback(3) }
        assertEquals(before, events.get())
    }

    open class FakeSource : SystemMetricSource {
        var cpu = 0L; var headroomCalls = 0
        override fun thermalStatus(): Int? = 0
        override fun thermalHeadroom(): Float? { headroomCalls++; return 0.5f }
        override fun processCpuTimeMs(): Long? = cpu
        override fun cpuFrequency(): String? = null
    }

    companion object {
        fun header(enabled: Boolean = false) = PerformanceLogHeader(0, "best.tflite", "Detector", 640, 640,
            1280, 720, com.samin.objectdetection.camera.YoloResizeMode.LETTERBOX, 4, false, false, false, 0.2f, 0.45f,
            sessionId = "test-session", startedElapsedNs = 0, systemDiagnosticsEnabled = enabled)
    }
}
