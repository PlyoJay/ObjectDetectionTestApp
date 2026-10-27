import unittest

from analyze_performance_logs import analyze, fields


class LatencyAnalysisTest(unittest.TestCase):
    def test_arcore_depth_json_and_backend_fields_do_not_change_legacy_latency_metrics(self):
        extra = ('cameraBackend=ARCORE | depthObjects=[{"label":"stop sign","confidence":0.9,'
                 '"bbox":[1,2,3,4],"estimatedDistanceMeters":2.3,"depthMethod":"RAW",'
                 '"depthTimestampNs":1050000000,"distanceDefinition":"CAMERA_FORWARD_Z"}]')
        baseline = analyze(self.log())
        enriched = analyze(self.log(extra))
        self.assertEqual(baseline["latency"], enriched["latency"])
        self.assertEqual(baseline["details"], enriched["details"])
        self.assertEqual("ARCORE", fields(extra)["cameraBackend"])

    def log(self, frame_extra="", events=""):
        return (
            "Start Elapsed Ns: 1000000000\nDropped Log Lines: 0\n"
            "FRAME 1 | elapsedSeconds=0.600 inference=300ms pipeline=450ms totalProcessing=500ms "
            "sessionId=A frameId=1 status=SUCCESS cameraTimestampNs=1050000000 captureTimestampReliable=true "
            "analyzerElapsedNs=1100000000 inferenceStartElapsedNs=1200000000 "
            "completedElapsedNs=1600000000 " + frame_extra + "\n" + events +
            "CUMULATIVE_SUMMARY | endSeconds=3 successfulFrames=1\n"
        )

    def test_duplicate_historical_rotation_keys_recover_duration_and_angle_in_either_order(self):
        for values in ("rotation=15.179ms rotation=90", "rotation=90 rotation=15.179ms"):
            record = fields(values)
            self.assertEqual("15.179", record["rotationMs"])
            self.assertEqual("90", record["rotationDegrees"])
            result = analyze(self.log(values + " frame=1080x1440 roi=[108,288,972,1440]"))
            self.assertEqual(15.179, result["details"]["rotation"]["averageMs"])
            self.assertEqual("90", result["measurement"]["observedFrameRois"][0]["rotation"])

    def test_new_rotation_keys_take_precedence_and_preserve_existing_analysis_output_keys(self):
        result = analyze(self.log("rotationMs=3.125 rotationDegrees=270 rotation=9.000ms frame=720x1280 roi=[0,0,720,1280]"))
        self.assertEqual(3.125, result["details"]["rotation"]["p95Ms"])
        self.assertEqual("270", result["measurement"]["observedFrameRois"][0]["rotation"])

    def test_angle_only_is_not_a_duration_and_duration_only_is_not_an_angle(self):
        angle = analyze(self.log("rotation=90"))["details"]["rotation"]
        self.assertEqual(0, angle["samples"])
        self.assertIsNone(angle["averageMs"])
        duration = analyze(self.log("rotation=4.250ms frame=10x20 roi=[0,0,10,20]"))
        self.assertEqual(4.25, duration["details"]["rotation"]["averageMs"])
        self.assertEqual("N/A", duration["measurement"]["observedFrameRois"][0]["rotation"])

    def test_missing_negative_nan_and_na_rotation_are_unavailable_not_zero(self):
        for raw in ("", "rotationMs=N/A", "rotationMs=-1.000", "rotationMs=null", "rotationMs=NaN"):
            result = analyze(self.log(raw))["details"]["rotation"]
            self.assertEqual(0, result["samples"])
            self.assertIsNone(result["p95Ms"])

    def test_legacy_log_does_not_fabricate_interpreter_return_or_event_spans(self):
        result = analyze(self.log())["latency"]
        self.assertEqual(0, result["inferenceCall"]["samples"])
        self.assertEqual(0, result["inferenceEndToPipelineComplete"]["samples"])
        self.assertEqual(550, result["captureToPipelineComplete"]["averageMs"])
        self.assertTrue(all(n == 0 for n in result["eventCounts"].values()))

    def events(self):
        return "\n".join((
            "LATENCY | sessionId=A frameId=1 eventElapsedNs=1100000000 stage=CAMERA_RECEIVED analyzerReceivedElapsedNs=1100000000 cameraTimestampNs=1050000000 captureTimestampReliable=true",
            "LATENCY | sessionId=A frameId=1 eventElapsedNs=1670000000 stage=ANALYZER analyzerWorkEndElapsedNs=1640000000 imageProxyCloseStartElapsedNs=1650000000 imageProxyCloseEndElapsedNs=1660000000 analyzerCallbackEndElapsedNs=1670000000 closeSucceeded=true outcome=DELIVERED",
            "LATENCY | sessionId=A frameId=1 eventElapsedNs=1450000000 stage=MLKIT mlKitDispatchedElapsedNs=1150000000 mlKitRequestElapsedNs=1180000000 mlKitCompletedElapsedNs=1450000000 outcome=SUCCESS completionBasis=TASK_COMPLETION_LISTENER",
            "LATENCY | sessionId=A frameId=1 eventElapsedNs=1630000000 stage=WARNING warningCandidateFinalizedElapsedNs=1610000000 warningSubmittedElapsedNs=1620000000 warningSubmitReturnedElapsedNs=1630000000 accepted=true candidatePresent=true",
            "LATENCY | sessionId=A frameId=1 eventElapsedNs=1710000000 stage=OUTPUT kind=TTS observation=API_REQUEST outputRequestElapsedNs=1700000000 outputRequestReturnedElapsedNs=1710000000 outputStartedElapsedNs=-1 physicalOutputStartedElapsedNs=-1 pulseIndex=0 accepted=true",
            "LATENCY | sessionId=A frameId=1 eventElapsedNs=1800000000 stage=OUTPUT kind=TTS observation=TTS_ON_START outputRequestElapsedNs=1700000000 outputStartedElapsedNs=1800000000 physicalOutputStartedElapsedNs=-1",
        )) + "\n"

    def test_full_timeline_keeps_overlapping_scopes_separate_and_does_not_change_fps(self):
        result = analyze(self.log("inferenceEndElapsedNs=1500000000 pipelineCompletedElapsedNs=1600000000", self.events()))
        latency = result["latency"]
        self.assertEqual(300, latency["inferenceCall"]["averageMs"])
        self.assertEqual(100, latency["inferenceEndToPipelineComplete"]["averageMs"])
        self.assertEqual(570, latency["analyzerCallback"]["averageMs"])
        self.assertEqual(10, latency["imageProxyClose"]["averageMs"])
        self.assertEqual(30, latency["mlKitDispatchToRequest"]["averageMs"])
        self.assertEqual(270, latency["mlKitRequestToCompletionObservation"]["averageMs"])
        self.assertEqual(10, latency["warningFinalizeToSubmit"]["averageMs"])
        self.assertEqual(10, latency["warningSubmitCall"]["averageMs"])
        tts = latency["outputs"]["TTS"]
        self.assertEqual(80, tts["warningSubmitToFirstRequest"]["averageMs"])
        self.assertEqual(100, tts["requestToStartCallback"]["averageMs"])
        self.assertEqual(750, tts["captureToStartCallback"]["averageMs"])
        self.assertEqual("N/A", tts["physicalStart"])
        self.assertEqual(500, result["totalProcessing"]["averageMs"])
        self.assertEqual(1 / 3, result["fpsFromRecordedFrames"])
        self.assertTrue(result["frameLogComplete"])

    def test_row_order_does_not_determine_timeline(self):
        events = "\n".join(reversed(self.events().splitlines())) + "\n"
        self.assertEqual(analyze(self.log(events=self.events()))["latency"],
                         analyze(self.log(events=events))["latency"])

    def test_same_frame_id_in_another_session_cannot_supply_capture_or_warning_time(self):
        events = self.events().replace("sessionId=A frameId=1 eventElapsedNs=1800000000",
                                       "sessionId=B frameId=1 eventElapsedNs=1800000000")
        result = analyze(self.log(events=events))["latency"]["outputs"]["TTS"]
        self.assertEqual(1, result["requestToStartCallback"]["samples"])
        self.assertEqual(0, result["captureToStartCallback"]["samples"])
        self.assertIsNone(result["captureToStartCallback"]["averageMs"])

    def test_beep_and_vibration_have_only_api_boundaries_and_all_beep_pulses_are_counted(self):
        events = ""
        for kind in ("BEEP", "VIBRATION"):
            for pulse in range(3 if kind == "BEEP" else 1):
                events += (f"LATENCY | sessionId=A frameId=1 eventElapsedNs=1700000000 stage=OUTPUT kind={kind} "
                           f"observation=API_REQUEST outputRequestElapsedNs=1690000000 outputRequestReturnedElapsedNs=1700000000 "
                           f"outputStartedElapsedNs=-1 physicalOutputStartedElapsedNs=-1 accepted=true pulseIndex={pulse}\n")
        result = analyze(self.log(events=events))["latency"]["outputs"]
        self.assertEqual(3, result["BEEP"]["requestObservations"])
        for kind in ("BEEP", "VIBRATION"):
            self.assertEqual(10, result[kind]["apiCall"]["averageMs"])
            self.assertEqual(0, result[kind]["requestToStartCallback"]["samples"])
            self.assertEqual("N/A", result[kind]["physicalStart"])

    def test_event_observation_window_and_exclusive_end_are_independent_of_frame_window(self):
        events = self.events() + "LATENCY | sessionId=A frameId=1 eventElapsedNs=4000000000 stage=OUTPUT kind=TTS observation=TTS_ON_START\n"
        result = analyze(self.log(events=events), start_seconds=.65)["latency"]
        self.assertEqual(0, result["eventCounts"]["CAMERA_RECEIVED"])
        self.assertEqual(0, result["eventCounts"]["MLKIT"])
        self.assertEqual(2, result["eventCounts"]["OUTPUT"])
        self.assertEqual(1, result["eventCounts"]["ANALYZER"])
        self.assertEqual(0, result["outputs"]["TTS"]["captureToStartCallback"]["samples"])

    def test_negative_reversed_or_missing_times_remain_unavailable(self):
        result = analyze(self.log("inferenceEndElapsedNs=1199999999 pipelineCompletedElapsedNs=-1", self.events().replace(
            "mlKitRequestElapsedNs=1180000000", "mlKitRequestElapsedNs=-1")))["latency"]
        self.assertIsNone(result["inferenceCall"]["averageMs"])
        self.assertIsNone(result["inferenceEndToPipelineComplete"]["averageMs"])
        self.assertEqual(0, result["mlKitRequestToCompletionObservation"]["samples"])

    def test_unplaced_events_and_unreliable_capture_are_not_reported_as_valid_latency(self):
        text = self.log(events=self.events()).replace("Start Elapsed Ns: 1000000000\n", "")
        result = analyze(text)["latency"]
        self.assertEqual(6, result["unplacedEventLines"])
        self.assertTrue(all(n == 0 for n in result["eventCounts"].values()))
        unreliable = analyze(self.log(events=self.events()).replace("captureTimestampReliable=true", "captureTimestampReliable=false"))["latency"]
        self.assertIsNone(unreliable["captureToPipelineComplete"]["averageMs"])
        self.assertIsNone(unreliable["outputs"]["TTS"]["captureToStartCallback"]["averageMs"])

    def test_old_null_and_new_minus_one_completion_use_available_legacy_elapsed_placement(self):
        for missing in ("null", "-1", "N/A"):
            text = self.log().replace("completedElapsedNs=1600000000", "completedElapsedNs=" + missing)
            result = analyze(text)
            self.assertEqual(1, result["successfulFrameSamples"])
            self.assertEqual(0, result["latency"]["captureToPipelineComplete"]["samples"])

    def test_unplaceable_frame_cannot_be_reported_as_measured_zero_fps(self):
        text = self.log().replace("completedElapsedNs=1600000000", "completedElapsedNs=-1").replace("elapsedSeconds=0.600", "elapsedSeconds=N/A")
        result = analyze(text)
        self.assertEqual(1, result["unplacedFrameLines"])
        self.assertFalse(result["frameLogComplete"])
        self.assertIsNone(result["fpsFromRecordedFrames"])


if __name__ == "__main__":
    unittest.main()
