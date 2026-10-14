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

    def test_unknown_total_time_and_invalid_windows_are_not_invented(self):
        result = analyze(self.log().replace("totalProcessing=150ms", "totalProcessing=-1ms"), exclude_tail_seconds=23)
        self.assertEqual(1, result["totalProcessing"]["samples"])
        self.assertEqual(270, result["totalProcessing"]["p95Ms"])
        with self.assertRaises(ValueError):
            analyze(self.log(), exclude_tail_seconds=50)
        with self.assertRaises(ValueError):
            analyze("FRAME 000001 | inference=100ms")


if __name__ == "__main__":
    unittest.main()
