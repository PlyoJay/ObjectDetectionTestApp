import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from yolo_measurement import MODEL_SHA, clock_offset, header, join_inferences, loss_check, report, window_stats


def log_text(model=MODEL_SHA, session="test-session"):
    return f"""Session ID: {session}
Start Unix Ms: 1792975853302; Start Elapsed Ns: 100000000000
Model SHA256: {model}
Input Tensor: [1, 3, 512, 512] (FLOAT32)
Output Tensor: [1, 15, 5376] (FLOAT32)
--- Applied AppSettings Snapshot ---
yolo.model=YOLO_512
--- End AppSettings Snapshot ---
FRAME 1 | status=SUCCESS completedElapsedNs=104999999999 inference=100ms nativeInvoke=100.100ms totalProcessing=120ms
FRAME 2 | status=SUCCESS completedElapsedNs=105000000000 inference=200ms nativeInvoke=200.100ms totalProcessing=220ms
FRAME 3 | status=SUCCESS completedElapsedNs=124999999999 inference=300ms nativeInvoke=300.100ms totalProcessing=320ms
FRAME 4 | status=SUCCESS completedElapsedNs=125000000000 inference=400ms nativeInvoke=400.100ms totalProcessing=420ms
FRAME 5 | status=ERROR completedElapsedNs=110000000000 inference=999ms
TEST_SESSION | configuredDurationMs=300000 actualDurationMs=300025 stopReason=AUTO_DURATION
"""


class MeasurementTests(unittest.TestCase):
    def test_completion_window_is_half_open_and_excludes_errors(self):
        result = window_stats(log_text(), 5, 25)
        self.assertEqual(result["successfulFrames"], 2)
        self.assertEqual(result["fps"], .1)
        self.assertEqual(result["inference"], {"meanMs": 250, "p95Ms": 300})

    def test_wall_and_elapsed_are_independent(self):
        result = header(log_text().replace("\n", "\r\n"))
        self.assertEqual(result["startNs"], 100000000000)
        self.assertEqual(result["startUnixMs"], 1792975853302)
        self.assertEqual(result["settings"], "yolo.model=YOLO_512")

    def test_clock_mapping_uses_snapshots(self):
        self.assertEqual(clock_offset([{"ts": "110", "clock_value": "100", "clock_id": "6"},
                                       {"ts": "210", "clock_value": "200", "clock_id": "6"}]), 10)
        for rows in ([], [{"ts": "1", "clock_value": "0", "clock_id": "3"}],
                     [{"ts": "110", "clock_value": "100", "clock_id": "6"},
                      {"ts": "211", "clock_value": "200", "clock_id": "6"}]):
            with self.assertRaises(ValueError):
                clock_offset(rows)

    def test_loss_does_not_count_preexisting_kernel_overruns(self):
        rows = [{"name": "ftrace_cpu_overrun_" + suffix, "idx": "0", "severity": "info", "value": "9"}
                for suffix in ("begin", "end")]
        self.assertEqual(loss_check(rows)["status"], "NO_RECORDED_LOSS")
        rows[1]["value"] = "10"
        self.assertEqual(loss_check(rows)["status"], "INVALID")

    def test_loss_checks_service_and_importer(self):
        self.assertEqual(loss_check([])["status"], "N/A")
        for name, severity in (("traced_buf_bytes_overwritten", "info"), ("ftrace_cpu_has_data_loss", "data_loss"), ("parse_error", "error")):
            self.assertEqual(loss_check([{"name": name, "idx": "0", "severity": severity, "value": "1"}])["status"], "INVALID")

    def test_report_rejects_historical_nine_class_model(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            (path / "run.json").write_text(json.dumps({"session": {"sessionId": "test-session"}}))
            (path / "performance.txt").write_text(log_text(model="old-nine-class-hash"))
            with self.assertRaisesRegex(ValueError, "Session/model mismatch"):
                report(SimpleNamespace(run=path, baseline=None, trace_processor=None))

    def test_missing_trace_processor_stays_na(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            (path / "run.json").write_text(json.dumps({"session": {"sessionId": "test-session"},
                "captures": [{"traceFile": "early.perfetto-trace"}]}))
            (path / "performance.txt").write_text(log_text())
            with patch("yolo_measurement.analyze", return_value={"frameLogComplete": True}):
                report(SimpleNamespace(run=path, baseline=None, trace_processor=None))
            result = json.loads((path / "analysis.json").read_text())
            self.assertIn("N/A", result["traces"][0]["status"])

    def test_slice_join_requires_a_unique_enclosing_interval(self):
        frame = "FRAME 1 | frameId=42 status=SUCCESS inferenceStartElapsedNs=100 inferenceEndElapsedNs=300 nativeInvoke=0.001ms\n"
        states = [{"ts": "120", "dur": "100"}, {"ts": "50", "dur": "50"}]
        joined = join_inferences(frame, {"offsetNs": 10}, states)
        self.assertEqual(joined[0]["frameId"], "42")
        self.assertEqual(joined[1]["frameId"], "N/A")
        self.assertEqual(join_inferences(frame + frame, {"offsetNs": 10}, states)[0]["frameId"], "N/A")


if __name__ == "__main__":
    unittest.main()
