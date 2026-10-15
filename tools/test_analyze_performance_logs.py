import unittest
from analyze_performance_logs import analyze


class PerformanceLogAnalysisTest(unittest.TestCase):
    def log(self, dropped=0):
        return f"""Start Elapsed Ns: 1000000000
FRAME 000001 | inference=100ms | pipeline=120ms | totalProcessing=150ms | elapsedSeconds=1.000 | requestedBackend=CPU appliedBackend=CPU cpuThreads=2 xnnpackRequested=true cpuFallbackCount=0 | status=SUCCESS completedElapsedNs=2000000000
FRAME 000002 | inference=200ms | pipeline=230ms | totalProcessing=270ms | elapsedSeconds=26.999 | requestedBackend=CPU appliedBackend=CPU cpuThreads=2 xnnpackRequested=true cpuFallbackCount=0 | status=SUCCESS completedElapsedNs=27999999999
FRAME 000003 | inference=900ms | pipeline=950ms | totalProcessing=1000ms | elapsedSeconds=27.000 | status=SUCCESS completedElapsedNs=28000000000
ERROR | status=ERROR completedElapsedNs=3000000000 inferenceErrors=1
ERROR | status=ERROR completedElapsedNs=30000000000 inferenceErrors=2
Dropped Log Lines: {dropped}
CUMULATIVE_SUMMARY | endSeconds=50.000 successfulFrames=3
"""

    def test_power_saving_tail_is_excluded_using_monotonic_timestamps(self):
        result = analyze(self.log(), exclude_tail_seconds=23)
        self.assertEqual(2, result["successfulFrameSamples"])
        self.assertEqual(150, result["inference"]["averageMs"])
        self.assertEqual(200, result["inference"]["p95Ms"])
        self.assertEqual(210, result["totalProcessing"]["averageMs"])
        self.assertEqual(1, result["errorLinesInWindow"])
        self.assertAlmostEqual(2 / 27, result["fpsFromRecordedFrames"])
        self.assertTrue(result["frameLogComplete"])
        self.assertEqual("2", result["execution"][0]["cpuThreads"])

    def test_missing_frame_lines_are_reported_as_incomplete(self):
        self.assertFalse(analyze(self.log(dropped=1))["frameLogComplete"])
        self.assertFalse(analyze(self.log().replace("successfulFrames=3", "successfulFrames=4"))["frameLogComplete"])

    def test_decimal_details_and_diagnostics_groups_do_not_change_aggregate_scope(self):
        text = self.log().replace("inference=100ms", "inference=100ms preprocess=70ms rgbWrite=2.125ms inputDiagnostics=0.000ms")
        text = text.replace("inference=200ms", "inference=200ms preprocess=80ms rgbWrite=2.375ms inputDiagnostics=10.000ms")
        text = text.replace("inference=900ms", "inference=900ms preprocess=-1ms rgbWrite=-1ms inputDiagnostics=-1ms")
        result = analyze(text)
        self.assertEqual(75, result["preprocess"]["averageMs"])
        self.assertEqual(2.25, result["details"]["rgbWrite"]["averageMs"])
        self.assertEqual(2, result["details"]["rgbWrite"]["samples"])
        self.assertEqual(70, result["preprocessByInputDiagnostics"]["off"]["averageMs"])
        self.assertEqual(80, result["preprocessByInputDiagnostics"]["on"]["averageMs"])
        self.assertEqual(0, result["details"]["nativeInvoke"]["samples"])
        self.assertEqual(analyze(self.log())["totalProcessing"], result["totalProcessing"])

    def test_unknown_total_time_and_invalid_windows_are_not_invented(self):
        result = analyze(self.log().replace("totalProcessing=150ms", "totalProcessing=-1ms"), exclude_tail_seconds=23)
        self.assertEqual(1, result["totalProcessing"]["samples"])
        self.assertEqual(270, result["totalProcessing"]["p95Ms"])
        with self.assertRaises(ValueError):
            analyze(self.log(), exclude_tail_seconds=50)
        with self.assertRaises(ValueError):
            analyze("FRAME 000001 | inference=100ms")

    def test_battery_on_off_on_and_unsupported_values(self):
        off = analyze(self.log())
        self.assertEqual("N/A", off["diagnostics"]["battery"]["averageDischargingCurrentMa"])
        samples = """SYSTEM | elapsedSeconds=1 processCpuUsage=120 thermalStatus=NONE thermalHeadroom=0.2
THERMAL_STATUS | elapsedSeconds=2 previousThermalStatus=NONE thermalStatus=LIGHT
BATTERY_SAMPLE | elapsedSeconds=1 batteryLevelPct=80 batteryTemperatureC=35 batteryCurrentMa=-200 estimatedBatteryPowerMw=-800 chargingStatus=Discharging
BATTERY_SAMPLE | elapsedSeconds=5 batteryLevelPct=80 batteryTemperatureC=36 batteryCurrentMa=N/A estimatedBatteryPowerMw=N/A chargingStatus=Discharging
BATTERY_SAMPLE | elapsedSeconds=10 batteryLevelPct=80 batteryTemperatureC=37 batteryCurrentMa=100 estimatedBatteryPowerMw=400 chargingStatus=Charging
BATTERY_SAMPLE | elapsedSeconds=50.001 batteryLevelPct=79 batteryTemperatureC=38 batteryCurrentMa=-300 estimatedBatteryPowerMw=-1200 chargingStatus=Discharging
BATTERY_SUMMARY | samples=4 lastSampleSeconds=50.001
"""
        on = analyze(self.log() + samples)
        self.assertEqual(on, analyze(self.log() + samples))
        self.assertEqual(off["inference"], on["inference"])
        d = on["diagnostics"]
        self.assertEqual(120, d["processCpuUsage"]["average"])
        self.assertEqual(1, len(d["thermalStatusEvents"]))
        self.assertEqual(-1, d["battery"]["levelPct"]["delta"])
        self.assertEqual(3, d["battery"]["temperatureC"]["delta"])
        self.assertEqual(-250, d["battery"]["averageDischargingCurrentMa"])
        self.assertEqual(-1000, d["battery"]["averageEstimatedDischargingPowerMw"])
        self.assertEqual(1, d["battery"]["byChargingStatus"]["Discharging"]["batteryCurrentMa"]["unavailableSamples"])
        cropped = analyze(self.log() + samples, exclude_tail_seconds=23)["diagnostics"]["battery"]
        self.assertEqual(3, cropped["sampleCount"])
        self.assertEqual(0, cropped["levelPct"]["delta"])
        self.assertEqual("N/A", cropped["fullSessionSummary"])

    def test_unavailable_battery_endpoints_and_nonfinite_power_stay_na(self):
        sample = "BATTERY_SAMPLE | elapsedSeconds=1 batteryTemperatureC=35 batteryCurrentMa=NaN estimatedBatteryPowerMw=Infinity chargingStatus=Discharging\n"
        sample += "BATTERY_SAMPLE | elapsedSeconds=2 batteryTemperatureC=N/A chargingStatus=Discharging\n"
        result = analyze(self.log() + sample)["diagnostics"]["battery"]
        self.assertEqual("N/A", result["temperatureC"]["delta"])
        self.assertEqual("N/A", result["averageDischargingCurrentMa"])
        self.assertEqual("N/A", result["averageEstimatedDischargingPowerMw"])


if __name__ == "__main__":
    unittest.main()
