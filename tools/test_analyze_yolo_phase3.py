import unittest

from analyze_yolo_phase3 import phase3_analysis, window_correlations


class Phase3AnalysisTest(unittest.TestCase):
    def window(self, start, end, cpu, inference, headroom="N/A"):
        return dict(startSeconds=start, endSeconds=end, frames=10,
                    processCpuUsage={"average": cpu}, thermalHeadroom={"average": headroom},
                    inference={"averageMs": inference}, cpuPolicies={})

    def test_correlations_require_three_nonconstant_complete_windows(self):
        windows = [self.window(0, 30, 100, 300), self.window(30, 60, 110, 200)]
        self.assertEqual("N/A", window_correlations(windows)["processCpuUsage"]["pearsonR"])
        windows.append(self.window(60, 90, 120, 100))
        self.assertAlmostEqual(-1, window_correlations(windows)["processCpuUsage"]["pearsonR"])
        self.assertEqual(0, window_correlations(windows)["thermalHeadroom"]["pairedWindows"])

    def test_missing_and_partial_windows_are_not_filled_or_correlated(self):
        windows = [self.window(0, 30, "N/A", 300), self.window(30, 60, 100, 200),
                   self.window(60, 90, 100, 100), self.window(90, 90.001, 9999, 9999)]
        value = window_correlations(windows)["processCpuUsage"]
        self.assertEqual(2, value["pairedWindows"])
        self.assertEqual("N/A", value["pearsonR"])

    def test_policy_correlations_keep_missing_and_constant_limits_unavailable(self):
        windows = [self.window(i * 30, (i + 1) * 30, 100, 30 - i * 10) for i in range(3)]
        for i, window in enumerate(windows):
            window["cpuPolicies"] = {"policy0": {"currentKHz": {"average": i + 1},
                                                "scalingMaxKHz": {"average": 10}}}
        windows.append(self.window(90, 120, 100, 999))  # no policy data, not a zero-frequency sample
        values = window_correlations(windows)
        self.assertEqual(3, values["policy0.currentKHz"]["pairedWindows"])
        self.assertAlmostEqual(-1, values["policy0.currentKHz"]["pearsonR"])
        self.assertEqual("N/A", values["policy0.scalingMaxKHz"]["pearsonR"])

    def test_short_log_has_no_fabricated_boundary_windows_or_native_time(self):
        result = phase3_analysis("Model: probe.tflite\nModel SHA256: abc\n" + self.log(20))
        self.assertFalse(result["boundaries"]["available"])
        self.assertEqual("abc", result["headers"]["Model SHA256"])
        self.assertEqual(0, result["whole"]["details"]["nativeInvoke"]["samples"])

    def test_first_and_last_complete_windows_have_exact_boundaries(self):
        result = phase3_analysis(self.log(90))
        self.assertTrue(result["boundaries"]["available"])
        self.assertEqual(1, result["boundaries"]["first30"]["successfulFrameSamples"])
        self.assertEqual(1, result["boundaries"]["last30"]["successfulFrameSamples"])
        self.assertEqual(100, result["boundaries"]["first30"]["inference"]["averageMs"])
        self.assertEqual(300, result["boundaries"]["last30"]["inference"]["averageMs"])

    def log(self, duration):
        return ("Start Elapsed Ns: 1000000000\n"
                "FRAME 1 | elapsedSeconds=1 inference=100ms pipeline=110ms status=SUCCESS\n"
                "FRAME 2 | elapsedSeconds=30 inference=200ms pipeline=210ms status=SUCCESS\n"
                "FRAME 3 | elapsedSeconds=60 inference=300ms pipeline=310ms status=SUCCESS\n"
                f"TEST_SESSION | actualDurationMs={duration * 1000} stopReason=MANUAL\n"
                f"CUMULATIVE_SUMMARY | startSeconds=0 endSeconds={duration} successfulFrames=3\n")


if __name__ == "__main__":
    unittest.main()
