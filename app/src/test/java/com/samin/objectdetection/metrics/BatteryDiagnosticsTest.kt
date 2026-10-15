package com.samin.objectdetection.metrics

import com.samin.objectdetection.settings.*
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedWriter
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BatteryDiagnosticsTest {
    @Test fun unitsSentinelsAndSignsArePreserved() {
        assertEquals(-123.456, BatteryUnits.currentMa(-123456)!!, 0.000001)
        assertEquals(123.456, BatteryUnits.currentMa(123456)!!, 0.000001)
        assertEquals(0.0, BatteryUnits.currentMa(0)!!, 0.0)
        assertNull(BatteryUnits.currentMa(Int.MIN_VALUE)); assertNull(BatteryUnits.currentMa(Int.MAX_VALUE))
        assertNull(BatteryUnits.chargeUah(-1)); assertNull(BatteryUnits.energyNwh(Long.MIN_VALUE))
        assertNull(BatteryUnits.energyNwh(Long.MAX_VALUE)); assertNull(BatteryUnits.currentMa(null))
        assertEquals(3000000, BatteryUnits.chargeUah(3000000))
        assertEquals(12000000000L, BatteryUnits.energyNwh(12000000000L))
        assertEquals(35.7, BatteryUnits.temperatureC(357)!!, 0.0)
        assertNull(BatteryUnits.temperatureC(Int.MIN_VALUE))
        assertEquals(50.0, BatteryUnits.levelPct(100, 200)!!, 0.0)
        assertNull(BatteryUnits.levelPct(1, 0)); assertNull(BatteryUnits.levelPct(101, 100))
        assertEquals(-800.0, BatteryReading(voltageMv = 4000, currentMa = -200.0).estimatedPowerMw!!, 0.0)
        assertNull(BatteryReading(currentMa = -200.0).estimatedPowerMw)
    }

    @Test fun settingsRoundTripDefaultAndInferenceConfigurationRemainIndependent() {
        val original = AppSettings()
        assertFalse(original.performanceLog.batteryDiagnosticsEnabled)
        assertFalse(AppSettingsCodec.decode("schemaVersion=3").performanceLog.batteryDiagnosticsEnabled)
        for (system in listOf(false, true)) for (battery in listOf(false, true)) {
            val settings = original.copy(performanceLog = PerformanceLogSettings(system, 5000, battery))
            assertEquals(settings, AppSettingsCodec.decode(AppSettingsCodec.encode(settings)))
            assertEquals(original.toDetectionConfig(), settings.toDetectionConfig())
        }
    }

    @Test fun batteryPollsAreThrottledWithoutDuplicatingThermalReadsAndRecoverAfterFailure() {
        var now = 0L; var calls = 0
        val source = object : SystemPerformanceDiagnosticsTest.FakeSource() {
            override fun battery(): BatteryReading { calls++; if (calls == 1) throw SecurityException(); return reading() }
        }
        val reader = BatteryMetricsReader(source) { now }
        assertNull(reader.sample(null)!!.reading.currentMa)
        repeat(49) { now += 100_000_000; assertNull(reader.sample(null)) }
        now = 5_000_000_000
        assertEquals(-200.0, reader.sample(null)!!.reading.currentMa!!, 0.0)
        assertEquals(2, calls); assertEquals(0, source.headroomCalls)
        assertNotNull(reader.sample(null, force = true))
    }

    @Test fun summarySeparatesChargingAndDischargingAndDoesNotInventEndpoints() {
        val summary = BatterySummary()
        summary.record(BatterySample(0, reading(), null))
        summary.record(BatterySample(5_000_000_000, BatteryReading(chargingStatus = "Discharging"), null))
        summary.record(BatterySample(10_000_000_000, reading().copy(temperatureC = 38.0, currentMa = 300.0,
            chargingStatus = "Charging", pluggedType = "USB"), null))
        summary.thermal(ThermalStatusEvent(0, 0, null, 0, "poll"))
        summary.thermal(ThermalStatusEvent(1, 0, 0, 3, "listener"))
        val text = summary.format(SystemPerformanceDiagnosticsTest.header(), 10_000_000_000)
        assertTrue(text.contains("batteryLevelDeltaPct=0.000"))
        assertTrue(text.contains("batteryTemperatureDeltaC=3.000"))
        assertTrue(text.contains("maxBatteryTemperatureC=38.000"))
        assertTrue(text.contains("chargingStatusChanged=true pluggedTypeChanged=true thermalStatusChanged=true"))
        assertTrue(text.contains("currentValidSamples=2 currentUnavailableSamples=1"))
        assertTrue(text.contains("chargingStatus=Discharging averageBatteryCurrentMa=-200.000"))
        assertTrue(text.contains("chargingStatus=Charging averageBatteryCurrentMa=300.000"))
        summary.record(BatterySample(11_000_000_000, BatteryReading(), null))
        assertTrue(summary.format(SystemPerformanceDiagnosticsTest.header(), 11_000_000_000).contains("endBatteryTemperatureC=N/A"))
    }

    @Test fun sessionGatesBatteryAndDrainsFinalEndpointWithoutChangingFrames() {
        for ((system, battery) in listOf(false to true, true to false, true to true)) {
            val output = StringWriter(); val calls = AtomicInteger(); val ready = CountDownLatch(1)
            val source = object : SystemPerformanceDiagnosticsTest.FakeSource() {
                override fun cpuFrequency(): String? { ready.countDown(); return null }
                override fun battery(): BatteryReading { calls.incrementAndGet(); return reading() }
            }
            val header = SystemPerformanceDiagnosticsTest.header(system).copy(batteryDiagnosticsEnabled = battery)
            val session = PerformanceLogSession(header, BufferedWriter(output), source, { 1_000_000_000 }, { 0 })
            session.start()
            if (system) assertTrue(ready.await(5, TimeUnit.SECONDS))
            session.recordFrame(PerformanceIntervalCollectorTest.frame(100))
            val snapshot = DetectionMetricsCollector().stopPerformanceSessionSnapshot(stoppedElapsedNs = 1_000_000_000)
            session.close(snapshot).get(5, TimeUnit.SECONDS)
            assertSame(session.close(snapshot), session.close(snapshot))
            val text = output.toString()
            assertEquals(system && battery, text.contains("BATTERY_SAMPLE |"))
            assertEquals(system && battery, text.contains("BATTERY_SUMMARY |"))
            assertEquals(if (system && battery) 2 else 0, calls.get())
            assertTrue(text.contains("inference=100ms")); assertTrue(text.contains("CUMULATIVE_SUMMARY"))
        }
    }

    @Test fun manySamplesKeepCountsAndNoUnsupportedValuesBecomeZero() {
        val summary = BatterySummary()
        repeat(100_000) { summary.record(BatterySample(it * 5_000_000_000L, BatteryReading(), null)) }
        val text = summary.format(SystemPerformanceDiagnosticsTest.header(), 500_000_000_000_000)
        assertTrue(text.contains("samples=100000 validSamples=0 unavailableSamples=100000"))
        assertTrue(text.contains("averageBatteryCurrentMa=N/A")); assertTrue(text.contains("averageEstimatedBatteryPowerMw=N/A"))
    }

    private fun reading() = BatteryReading(80.0, 35.0, 4000, -200.0, -190.0, 3000000, 12000000000, "Discharging", "None")
}
