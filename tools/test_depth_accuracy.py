import json
import unittest
from unittest.mock import Mock
from analyze_depth_accuracy import analyze, read_records


class DepthAccuracyTest(unittest.TestCase):
    def test_both_sources_are_evaluated_on_the_same_manually_labelled_frames(self):
        value = self.row(1, 2, 2)
        value["depthAnalysis"] = {"full": {"meters": 4}, "raw": {"meters": 2}}
        result = analyze([value])
        self.assertEqual(2, result["pairedFullCandidate"]["maeMeters"])
        self.assertEqual(0, result["pairedRawCandidate"]["maeMeters"])
        self.assertEqual(1, result["pairedFullCandidate"]["count"])
    def row(self, frame, raw, displayed, truth=2):
        return dict(sessionId="s", frameId=frame, objectLabel="person", groundTruthMeters=truth,
                    groundTruthDefinition="CAMERA_FORWARD_Z", groundTruthStatus="UNIQUE_TARGET",
                    rawDepthMeters=raw, estimatedDistanceMeters=displayed, depthMethod="FULL", distanceUpdateDelayMs=250)

    def test_errors_coverage_and_p90_use_explicit_manual_truth(self):
        result = analyze([self.row(1, 1, 1.5), self.row(2, 3, None), self.row(3, 2, 2)])
        self.assertAlmostEqual(2 / 3, result["validDisplayRatio"])
        self.assertAlmostEqual(2 / 3, result["raw"]["maeMeters"])
        self.assertEqual(1, result["raw"]["medianAbsoluteErrorMeters"])
        self.assertEqual(1, result["raw"]["p90AbsoluteErrorMeters"])
        self.assertEqual(2, result["pairedRaw"]["count"])
        self.assertEqual(250, result["distanceUpdateDelayMs"]["median"])

    def test_no_truth_or_wrong_definition_cannot_fabricate_accuracy(self):
        result = analyze([dict(rawDepthMeters=2), {**self.row(1, 2, 2), "groundTruthDefinition": "SLANT_RANGE"},
                          {**self.row(2, 2, 2), "groundTruthStatus": "AMBIGUOUS_TARGET"}])
        self.assertEqual(0, result["labelledAttempts"])
        self.assertIsNone(result["raw"]["maeMeters"])
        self.assertIsNone(result["validDisplayRatio"])

    def test_sources_distances_duplicates_and_invalid_values_stay_separate(self):
        a = self.row(1, 1.2, 1.1, 1)
        b = {**self.row(2, float("nan"), None, 5), "depthMethod": "RAW"}
        result = analyze([a, a, b])
        self.assertEqual(2, result["labelledAttempts"])
        self.assertEqual({"1m", "5m"}, set(result["byGroundTruth"]))
        self.assertEqual({"FULL", "RAW"}, set(result["byAttemptedMethod"]))
        self.assertEqual({"FULL", "NONE"}, set(result["bySelectedMethod"]))
        self.assertEqual(1, result["raw"]["count"])
        json.dumps(result, allow_nan=False)

    def test_existing_json_jsonl_and_perf_depth_objects_are_read(self):
        row = self.row(1, 2.3, 2.2)
        for body in (json.dumps({"detections": [row]}),
                     json.dumps({"detections": [row]}) + "\n" + json.dumps({"type": "OVERLAY", "detections": []}),
                     "FRAME 1 | depthObjects=" + json.dumps([row]) + " | sessionId=s frameId=1"):
            p = Mock(); p.read_text.return_value = body
            self.assertEqual(1, analyze(read_records(p))["labelledAttempts"])


if __name__ == "__main__":
    unittest.main()
