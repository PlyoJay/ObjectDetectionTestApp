package com.samin.objectdetection.metrics

import org.junit.Assert.*
import org.junit.Test

class BatteryDischargeTrackerTest {
    private fun sample(seconds: Long, charge: Int? = 4_000_000, current: Double? = -2000.0, voltage: Int? = 4000,
                       status: String = "Discharging", plugged: String = "None") =
        BatterySample(seconds * 1_000_000_000, BatteryReading(voltageMv = voltage, currentMa = current,
            chargeCounterUah = charge, chargingStatus = status, pluggedType = plugged), null)

    @Test fun rawAndApiArithmeticAreDistinctFromUnvalidatedCurrentAndPower() {
        val reading = BatteryReading(voltageMv = 4000, currentMa = BatteryUnits.currentMa(-2000),
            currentAverageMa = BatteryUnits.currentMa(-1500), currentNowRaw = -2000, currentAverageRaw = -1500)
        val text = BatterySummary.formatSample(SystemPerformanceDiagnosticsTest.header(), BatterySample(0, reading, null))
        assertTrue(text.contains("batteryCurrentNowRaw=-2000 batteryCurrentAverageRaw=-1500"))
        assertTrue(text.contains("batteryCurrentNowApiMa=-2.000 batteryCurrentAverageApiMa=-1.500"))
        assertTrue(text.contains("batteryCurrentMa=N/A batteryCurrentAverageMa=N/A"))
        assertTrue(text.contains("estimatedBatteryPowerMw=N/A apiEstimatedBatteryPowerMw=-8.000"))
        assertTrue(text.contains("batteryWarnings=CURRENT_NOT_VALIDATED"))
        val unsupported = BatteryReading(currentNowRaw = Int.MIN_VALUE)
        val unavailable = BatterySummary.formatSample(SystemPerformanceDiagnosticsTest.header(), BatterySample(0, unsupported, null))
        assertTrue(unavailable.contains("batteryCurrentNowRaw=-2147483648"))
        assertTrue(unavailable.contains("batteryCurrentNowApiMa=N/A"))
        assertEquals(-2000.0, BatteryUnits.currentMa(-2_000_000)!!, 0.0)
        assertEquals(-2.0, BatteryUnits.currentMa(-2000)!!, 0.0) // Never infer mA from magnitude.
    }

    @Test fun counterDropOverThreeMinutesUsesTimeAndEndpointVoltageWithoutCurrentDependency() {
        val tracker = BatteryDischargeTracker()
        tracker.record(sample(0, current = null))
        repeat(3) { index ->
            val result = tracker.record(sample((index + 1) * 60L, 4_000_000 - (index + 1) * 10_000, null))
            assertEquals(600.0, result.counterDischargeMa!!, 0.000001)
            assertEquals(2400.0, result.counterDischargePowerMw!!, 0.000001)
            assertFalse(result.currentUsable)
        }
        assertTrue(tracker.formatTotals().contains("counterTotalDischargeMah=30.000"))
        assertTrue(tracker.formatTotals().contains("counterAverageDischargeCurrentMa=600.000"))
    }

    @Test fun largeMismatchSuppressesPublishedPowerAndLatchesWithoutRescaling() {
        val summary = BatterySummary()
        summary.record(sample(0, current = -2.0))
        val assessment = summary.record(sample(60, 3_966_667, -2.0))
        assertEquals("SUSPECT_CURRENT_COUNTER_MISMATCH", assessment.currentReliability)
        assertFalse(assessment.currentUsable)
        assertEquals(1999.98, assessment.counterDischargeMa!!, 0.0001)
        val text = BatterySummary.formatSample(SystemPerformanceDiagnosticsTest.header(), sample(60, 3_966_667, -2.0), assessment)
        assertTrue(text.contains("estimatedBatteryPowerMw=N/A apiEstimatedBatteryPowerMw=-8.000"))
        val totals = summary.format(SystemPerformanceDiagnosticsTest.header(), 60_000_000_000)
        assertTrue(totals.contains("averageBatteryCurrentMa=N/A"))
        assertTrue(totals.contains("averageApiBatteryCurrentMa=-2.000"))
        val later = summary.record(sample(120, 3_933_334, -2000.0))
        assertFalse(later.currentUsable)
    }

    @Test fun consistentCurrentRequiresEnoughTimeAndChargeAndKeepsAverageWindowUnknown() {
        val tracker = BatteryDischargeTracker()
        assertFalse(tracker.record(sample(0)).currentUsable)
        assertFalse(tracker.record(sample(30, 3_983_333)).currentUsable)
        val result = tracker.record(sample(60, 3_966_666))
        assertTrue(result.currentUsable)
        assertTrue(result.warnings.contains("CURRENT_AVERAGE_WINDOW_UNKNOWN"))
        val text = BatterySummary.formatSample(SystemPerformanceDiagnosticsTest.header(), sample(60, 3_966_666), result)
        assertTrue(text.contains("batteryCurrentMa=-2000.000 batteryCurrentAverageMa=N/A"))
        assertTrue(text.contains("estimatedBatteryPowerMw=-8000.000"))
    }

    @Test fun invalidIntervalsAreUnavailableAndNeverBridgeCounterOrPowerTransitions() {
        val cases = listOf(
            sample(0, 3_990_000) to "NON_POSITIVE_INTERVAL",
            sample(121, 3_990_000) to "SAMPLE_GAP",
            sample(60, null) to "CHARGE_COUNTER_UNAVAILABLE",
            sample(60, 4_010_000) to "CHARGE_COUNTER_INCREASE_OR_RESET",
            sample(60, 3_990_000, status = "Charging", plugged = "USB") to "POWER_STATE_CHANGED",
            sample(60, 3_990_000, plugged = "USB") to "POWER_STATE_CHANGED"
        )
        for ((after, warning) in cases) {
            val tracker = BatteryDischargeTracker()
            tracker.record(sample(0))
            val result = tracker.record(after)
            assertNull(warning, result.counterDischargeMa)
            assertNull(warning, result.counterDischargePowerMw)
            assertTrue(result.warnings.toString(), result.warnings.contains(warning))
            assertFalse(result.currentUsable)
        }
        val missing = BatteryDischargeTracker()
        missing.record(sample(0)); missing.record(sample(5, null))
        assertNull(missing.record(sample(10, 3_980_000)).counterDischargeMa)
    }

    @Test fun unchangedCounterDoesNotClaimZeroPowerAndMissingVoltageOnlySuppressesPower() {
        val tracker = BatteryDischargeTracker()
        tracker.record(sample(0, voltage = null))
        val unchanged = tracker.record(sample(5))
        assertNull(unchanged.counterDischargeMa)
        assertTrue(unchanged.warnings.contains("COUNTER_UNCHANGED_OR_QUANTIZED"))
        assertTrue(unchanged.warnings.contains("SHORT_INTERVAL_COUNTER_QUANTIZATION"))
        val currentOnly = BatteryDischargeTracker()
        currentOnly.record(sample(0, voltage = null))
        val result = currentOnly.record(sample(60, 3_990_000))
        assertEquals(600.0, result.counterDischargeMa!!, 0.0)
        assertNull(result.counterDischargePowerMw)
        assertTrue(currentOnly.formatTotals().contains("counterEstimatedDischargePowerMw=N/A"))
    }

    @Test fun positiveDischargingCurrentIsFlaggedAndZeroCurrentIsNotASentinel() {
        val tracker = BatteryDischargeTracker()
        assertEquals("SUSPECT_CURRENT_SIGN", tracker.record(sample(0, current = 2.0)).currentReliability)
        val zero = BatteryDischargeTracker()
        assertFalse(zero.record(sample(0, current = 0.0)).warnings.contains("CURRENT_NOW_UNAVAILABLE"))
        assertEquals("SUSPECT_CURRENT_COUNTER_MISMATCH", zero.record(sample(60, 3_990_000, 0.0)).currentReliability)
    }
}
