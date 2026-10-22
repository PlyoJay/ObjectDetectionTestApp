"""Compare real FRAME samples, optionally excluding a known power-saving tail.

Only stdlib is needed. Dropped FRAME lines make statistics incomplete; never
reconstruct P95 from interval averages. Existing pipeline and totalProcessing
timings intentionally have different scopes.
"""
import argparse
import json
import math
import re
from pathlib import Path


def fields(line):
    pairs = re.findall(r"(?:^|[\s|])(\w+)=([^\s|]+)", line)
    result = dict(pairs)
    # Historical FRAME rows used rotation twice: a duration with 'ms', then an angle.
    # Recover both from the original pairs before dict's last-key-wins loses the duration.
    legacy_rotation = [value for key, value in pairs if key == "rotation"]
    if "rotationMs" not in result:
        duration = next((v for v in legacy_rotation if v.endswith("ms")), None)
        if duration is not None:
            result["rotationMs"] = duration.removesuffix("ms")
    if "rotationDegrees" not in result:
        angle = next((v for v in reversed(legacy_rotation) if not v.endswith("ms")), None)
        if angle is not None:
            result["rotationDegrees"] = angle
    # Preserve the analysis API's legacy ROI angle key, never use this alias as a duration.
    if "rotationDegrees" in result:
        result["rotation"] = result["rotationDegrees"]
    # relatedCpus contains spaces; the generic token parser truncated all later policies.
    frequency = re.search(r"(?:^|[\s|])cpuFrequency=(.*?)\s+cpuFrequencyScope=", line)
    if frequency:
        result["cpuFrequency"] = frequency.group(1)
    return result


def stats(records, key):
    values = sorted(int(r[key].removesuffix("ms")) for r in records
                    if key in r and int(r[key].removesuffix("ms")) >= 0)
    return {"samples": len(values), "averageMs": sum(values) / len(values) if values else None,
            "p95Ms": values[math.ceil(len(values) * 0.95) - 1] if values else None}


def detail_stats(records, key):
    """Overlapping detail fields are reported separately, never added to total latency."""
    values = []
    for record in records:
        try:
            value = float(record[key].removesuffix("ms"))
        except (KeyError, ValueError, TypeError):
            continue
        if math.isfinite(value) and value >= 0:
            values.append(value)
    values.sort()
    return {"samples": len(values), "averageMs": sum(values) / len(values) if values else None,
            "p95Ms": values[math.ceil(len(values) * 0.95) - 1] if values else None}


def numeric(record, key):
    try:
        value = float(record[key])
        return value if math.isfinite(value) else None
    except (KeyError, TypeError, ValueError):
        return None


def metric(records, key):
    values = [v for r in records if (v := numeric(r, key)) is not None]
    return {"validSamples": len(values), "unavailableSamples": len(records) - len(values),
            "average": sum(values) / len(values) if values else "N/A",
            "first": values[0] if values else "N/A", "last": values[-1] if values else "N/A",
            "maximum": max(values) if values else "N/A"}


def battery_consistency(records):
    """Independent cross-check, never auto-correct a vendor's units or attribute power to the app."""
    records = sorted(records, key=lambda r: numeric(r, "elapsedSeconds") or 0)
    charge_mah = current_mah = covered = 0.0
    pairs = 0
    for before, after in zip(records, records[1:]):
        if any(r.get("chargingStatus") != "Discharging" or r.get("pluggedType") != "None" for r in (before, after)):
            continue
        t0, t1 = numeric(before, "elapsedSeconds"), numeric(after, "elapsedSeconds")
        # New logs keep API arithmetic even when published current/power are suppressed.
        i0 = numeric(before, "batteryCurrentNowApiMa" if "batteryCurrentNowApiMa" in before else "batteryCurrentMa")
        i1 = numeric(after, "batteryCurrentNowApiMa" if "batteryCurrentNowApiMa" in after else "batteryCurrentMa")
        q0, q1 = numeric(before, "batteryChargeCounterUah"), numeric(after, "batteryChargeCounterUah")
        if None in (t0, t1, i0, i1, q0, q1) or not 0 < t1 - t0 <= 120 or q1 > q0 or max(i0, i1) > 0:
            continue  # missing values, transitions, counter reset/sign mismatch or large gap
        charge_mah += (q0 - q1) / 1000
        current_mah += -(i0 + i1) / 2 * (t1 - t0) / 3600
        covered += t1 - t0
        pairs += 1
    ratio = current_mah / charge_mah if charge_mah > 0 else None
    suspect = covered >= 60 and charge_mah >= 1 and ratio is not None and not 0.1 <= ratio <= 10
    return {"pairedIntervals": pairs, "coveredDischargingSeconds": covered,
            "chargeCounterDropMah": charge_mah if pairs else "N/A",
            "integratedCurrentDischargeMah": current_mah if pairs else "N/A",
            "currentToCounterRatio": ratio if ratio is not None else "N/A",
            "powerReliability": "SUSPECT_CURRENT_COUNTER_MISMATCH" if suspect else "NOT_VALIDATED",
            "note": "Trapezoidal sampled current vs charge counter on the same contiguous unplugged discharging intervals (gaps <=120s). A factor >10 mismatch after >=60s and >=1mAh counter drop is a diagnostic heuristic, not calibration. Do not multiply current by 1000 automatically. Counter/firmware errors, sampling and unit violations require device verification; power remains a device estimate."}


def counter_discharge(records):
    """Counter estimates are independent of CURRENT_NOW availability; positive means discharge."""
    records = sorted(records, key=lambda r: numeric(r, "elapsedSeconds") or 0)
    intervals = []
    seconds = charge_mah = energy_mwh = 0.0
    voltage_complete = True
    for before, after in zip(records, records[1:]):
        t0, t1 = numeric(before, "elapsedSeconds"), numeric(after, "elapsedSeconds")
        q0, q1 = numeric(before, "batteryChargeCounterUah"), numeric(after, "batteryChargeCounterUah")
        warning = None
        if any(r.get("chargingStatus") != "Discharging" or r.get("pluggedType") != "None" for r in (before, after)):
            warning = "NOT_UNPLUGGED_DISCHARGING_OR_TRANSITION"
        elif t0 is None or t1 is None or not 0 < t1 - t0 <= 120:
            warning = "INVALID_INTERVAL_OR_GAP"
        elif q0 is None or q1 is None or min(q0, q1) < 0:
            warning = "CHARGE_COUNTER_UNAVAILABLE"
        elif q1 > q0:
            warning = "CHARGE_COUNTER_INCREASE_OR_RESET"
        item = {"startSeconds": t0, "endSeconds": t1, "averageDischargeCurrentMa": "N/A",
                "estimatedDischargePowerMw": "N/A", "warning": warning}
        if warning is None:
            dt, drop = t1 - t0, (q0 - q1) / 1000
            seconds += dt
            charge_mah += drop
            v0, v1 = numeric(before, "batteryVoltageMv"), numeric(after, "batteryVoltageMv")
            mv = (v0 + v1) / 2 if v0 is not None and v1 is not None and min(v0, v1) > 0 else None
            if mv is None:
                voltage_complete = False
            else:
                energy_mwh += drop * mv / 1000
            item.update(chargeDropMah=drop, durationSeconds=dt)
            if drop > 0:
                current = drop * 3600 / dt
                item.update(averageDischargeCurrentMa=current,
                            estimatedDischargePowerMw=current * mv / 1000 if mv is not None else "N/A",
                            warning="SHORT_INTERVAL_COUNTER_QUANTIZATION" if dt < 30 else "COUNTER_ESTIMATE_NOT_CALIBRATED")
            else:
                item["warning"] = "COUNTER_UNCHANGED_OR_QUANTIZED"
        intervals.append(item)
    current = charge_mah * 3600 / seconds if seconds > 0 and charge_mah > 0 else "N/A"
    return {"coveredSeconds": seconds, "chargeDropMah": charge_mah if seconds else "N/A",
            "averageDischargeCurrentMa": current,
            "estimatedDischargePowerMw": energy_mwh * 3600 / seconds
            if current != "N/A" and voltage_complete else "N/A", "intervals": intervals,
            "note": "Device battery counter estimate, not calibrated or app-attributed. Only eligible adjacent intervals; unchanged intervals contribute elapsed time but do not assert zero power. Power uses charge-weighted endpoint voltages; short intervals reflect counter quantization."}


def cpu_policies(record):
    result = {}
    for encoded in record.get("cpuFrequency", "").split(";"):
        name, separator, attributes = encoded.partition(":")
        if separator:
            result[name] = dict(item.split("=", 1) for item in attributes.split(",") if "=" in item)
    return result


def correlation(pairs):
    if len(pairs) < 3:
        return "N/A"
    xs, ys = zip(*pairs)
    mx, my = sum(xs) / len(xs), sum(ys) / len(ys)
    xx, yy = sum((x - mx)**2 for x in xs), sum((y - my)**2 for y in ys)
    return sum((x - mx) * (y - my) for x, y in pairs) / math.sqrt(xx * yy) if xx > 0 and yy > 0 else "N/A"


def long_run_analysis(frames, systems, start, end, interval_seconds):
    windows = []
    policy_pairs = {}
    for index in range(math.ceil((end - start) / interval_seconds)):
        lo, hi = start + index * interval_seconds, min(end, start + (index + 1) * interval_seconds)
        group = [r for r in frames if lo <= r["_elapsed"] < hi]
        samples = [r for r in systems if (t := numeric(r, "elapsedSeconds")) is not None and lo <= t < hi]
        names = sorted({name for r in samples for name in cpu_policies(r)})
        policies = {}
        inference = stats(group, "inference")
        for name in names:
            values = [cpu_policies(r)[name] for r in samples if name in cpu_policies(r)]
            policies[name] = {key: metric(values, key) for key in ("currentKHz", "scalingMaxKHz", "cpuinfoMaxKHz")}
            policies[name]["governors"] = sorted({r["governor"] for r in values if "governor" in r})
            for key in ("currentKHz", "scalingMaxKHz"):
                value = policies[name][key]["average"]
                if value != "N/A" and inference["averageMs"] is not None:
                    policy_pairs.setdefault((name, key), []).append((value, inference["averageMs"]))
        windows.append({"startSeconds": lo, "endSeconds": hi, "frames": len(group),
                        "fps": len(group) / (hi - lo), "inference": inference,
                        "nativeInvoke": detail_stats(group, "nativeInvoke"),
                        "processCpuUsage": metric(samples, "processCpuUsage"),
                        "thermalHeadroom": metric(samples, "thermalHeadroom"), "cpuPolicies": policies})
    return {"intervalSeconds": interval_seconds, "windows": windows,
            "policyInferenceCorrelations": {f"{name}.{key}": {"pairedWindows": len(pairs), "pearsonR": correlation(pairs)}
                                            for (name, key), pairs in sorted(policy_pairs.items())},
            "inferenceByMlKitInFlightAtDispatch": {value: stats([r for r in frames if r.get("mlKitInFlight") == value], "inference")
                                                  for value in ("true", "false")},
            "note": "Correlations use equal-weight interval means of device cpufreq snapshots, not app core frequencies or causal evidence. Includes the final partial interval; time/thermal/ML Kit can confound correlations. mlKitInFlight is a dispatch-time hint, not verified overlap or CPU attribution. No missing policy current is filled with its maximum."}


def diagnostic_analysis(lines, start, end, duration):
    def select(prefix, include_final_endpoint=False):
        records = [fields(line) for line in lines if line.startswith(prefix)]
        return sorted((r for r in records if (t := numeric(r, "elapsedSeconds")) is not None
                       and start <= t and (t <= end or include_final_endpoint and start == 0 and end == duration)),
                      key=lambda r: numeric(r, "elapsedSeconds"))

    systems = select("SYSTEM |")
    batteries = select("BATTERY_SAMPLE |", include_final_endpoint=True)
    thermal_events = select("THERMAL_STATUS |")
    def endpoints(key):
        # Do not silently substitute an earlier reading for an unavailable endpoint.
        first = numeric(batteries[0], key) if batteries else None
        last = numeric(batteries[-1], key) if batteries else None
        return {"start": first if first is not None else "N/A", "end": last if last is not None else "N/A",
                "delta": last - first if first is not None and last is not None else "N/A"}

    by_status = {}
    for status in sorted({r.get("chargingStatus", "Unknown") for r in batteries}):
        group = [r for r in batteries if r.get("chargingStatus", "Unknown") == status]
        by_status[status] = {"batteryCurrentMa": metric(group, "batteryCurrentMa"),
                             "estimatedBatteryPowerMw": metric(group, "estimatedBatteryPowerMw")}
    # Full-session summary remains authoritative when bounded writer dropped sample lines.
    summary = next((fields(line) for line in lines if line.startswith("BATTERY_SUMMARY |")), None)
    consistency = battery_consistency(batteries)
    suspect = consistency["powerReliability"].startswith("SUSPECT") or any(
        r.get("batteryCurrentReliability", "").startswith("SUSPECT") for r in batteries)
    return {
        "systemSampleCount": len(systems), "processCpuUsage": metric(systems, "processCpuUsage"),
        "thermalStatusSamples": [r.get("thermalStatus", "N/A") for r in systems],
        "thermalStatusEvents": thermal_events,
        "thermalHeadroom": metric(systems, "thermalHeadroom"),
        "thermalTrend": [{k: r.get(k, "N/A") for k in
                          ("elapsedSeconds", "thermalStatus", "thermalHeadroom", "headroomAgeMs")} for r in systems],
        "battery": {"sampleCount": len(batteries), "levelPct": endpoints("batteryLevelPct"),
                    "temperatureC": {**endpoints("batteryTemperatureC"),
                                     "sampledMaximum": metric(batteries, "batteryTemperatureC")["maximum"]},
                    "byChargingStatus": by_status,
                    "consistency": consistency, "counterDischarge": counter_discharge(batteries),
                    "rawCurrentNow": metric(batteries, "batteryCurrentNowRaw"),
                    "apiCurrentNowMa": metric(batteries, "batteryCurrentNowApiMa"),
                    "averageDischargingCurrentMa": "N/A" if suspect else by_status.get("Discharging", {}).get("batteryCurrentMa", {}).get("average", "N/A"),
                    "averageEstimatedDischargingPowerMw": "N/A" if suspect else by_status.get("Discharging", {}).get("estimatedBatteryPowerMw", {}).get("average", "N/A"),
                    "fullSessionSummary": summary if start == 0 and end == duration and summary else "N/A",
                    "sampleLogComplete": len(batteries) == int(summary["samples"])
                    if start == 0 and end == duration and summary and "samples" in summary else "N/A",
                    "fullSessionStatusSummaries": [fields(line) for line in lines if line.startswith("BATTERY_STATUS_SUMMARY |")]
                    if start == 0 and end == duration else [],
                    "note": "Device battery, signed current/power; estimates are not app consumption. byChargingStatus and fullSessionStatusSummaries preserve recorded numbers even if legacy logs reported unreliable power; top-level current/power are suppressed on detected mismatch. API arithmetic is not independent device validation. Means use recorded samples grouped by status. Battery temperature is not CPU/GPU temperature. 0% level delta does not mean zero power. Polls can miss transitions; headroom may be cached. See fullSessionSummary for authoritative counts/endpoints if sample lines were dropped."}}


def analyze(text, start_seconds=0.0, exclude_tail_seconds=0.0, interval_seconds=None):
    lines = text.splitlines()
    cumulative = next((fields(line) for line in lines if line.startswith("CUMULATIVE_SUMMARY |")), None)
    if cumulative is None:
        raise ValueError("A finalized log with CUMULATIVE_SUMMARY is required for an exact end time")
    duration = float(cumulative["endSeconds"])
    end = duration - exclude_tail_seconds
    if not 0 <= start_seconds < end <= duration:
        raise ValueError("The requested measurement window is empty or outside the recording")
    if interval_seconds is not None and (not math.isfinite(interval_seconds) or interval_seconds < 1):
        raise ValueError("interval_seconds must be finite and >=1")
    elapsed_start = re.search(r"Start Elapsed Ns: (\d+)", text)
    start_ns = int(elapsed_start.group(1)) if elapsed_start else None

    def elapsed(record):
        try:
            completed_ns = int(record.get("completedElapsedNs", "-1"))
        except (TypeError, ValueError):
            completed_ns = -1
        if start_ns is not None and completed_ns > 0:
            return (completed_ns - start_ns) / 1e9
        try:
            return float(record["elapsedSeconds"])
        except (KeyError, TypeError, ValueError):
            return math.nan  # Unknown placement is excluded, never interpreted as zero latency.

    all_frames = [fields(line) for line in lines if line.startswith("FRAME ")]
    frames = [r for r in all_frames if r.get("status", "SUCCESS") == "SUCCESS" and
              start_seconds <= elapsed(r) < end]
    for record in frames:
        record["_elapsed"] = elapsed(record)
    errors = [fields(line) for line in lines if line.startswith("ERROR |")]
    errors_in_window = sum(start_seconds <= elapsed(r) < end for r in errors)
    dropped = re.search(r"Dropped Log Lines: (\d+)", text)
    dropped_count = int(dropped.group(1)) if dropped else None
    expected_count = int(cumulative["successfulFrames"])
    unplaced_frames = sum(not math.isfinite(elapsed(r)) for r in all_frames)
    complete = dropped_count == 0 and len(all_frames) == expected_count and unplaced_frames == 0
    execution_keys = ("requestedBackend", "appliedBackend", "cpuThreads", "xnnpackRequested",
                      "initialization", "delegateVerification", "gpuSupport", "runtimeVersion")
    configurations = sorted({tuple(r.get(key, "N/A") for key in execution_keys) for r in frames})
    timed_session = next((fields(line) for line in lines if line.startswith("TEST_SESSION |")), None)
    roi_settings = dict(line.split("=", 1) for line in lines if
                        line.startswith("camera.roi.") or line.startswith("camera.useCenterSquareCrop=") or
                        line.startswith("yolo.resizeMode="))
    result = {
        "measurement": {
            "session": timed_session if timed_session else "N/A",
            "roiSettings": roi_settings if roi_settings else "N/A",
            "observedFrameRois": [dict(zip(("frame", "rotation", "roi"), value)) for value in
                                  sorted({tuple(r.get(key, "N/A") for key in ("frame", "rotationDegrees", "roi"))
                                          for r in frames if "roi" in r})]},
        "windowStartSeconds": start_seconds, "windowEndSeconds": end,
        "excludedTailSeconds": exclude_tail_seconds,
        "successfulFrameSamples": len(frames), "errorLinesInWindow": errors_in_window,
        "droppedLogLines": dropped_count, "frameLogComplete": complete,
        "unplacedFrameLines": unplaced_frames,
        "fpsFromRecordedFrames": len(frames) / (end - start_seconds) if unplaced_frames == 0 else None,
        "inference": stats(frames, "inference"), "pipeline": stats(frames, "pipeline"),
        "totalProcessing": stats(frames, "totalProcessing"),
        "preprocess": stats(frames, "preprocess"), "bitmap": stats(frames, "bitmap"),
        "details": {key: detail_stats(frames, "rotationMs" if key == "rotation" else key) for key in
                    ("cameraToBitmap", "rotation", "inputBuffer", "getPixels", "rgbWrite",
                     "inputDiagnostics", "outputDiagnostics", "nativeInvoke", "mlKitPrepare")},
        "preprocessByInputDiagnostics": {
            "off": stats([r for r in frames if r.get("inputDiagnostics") == "0.000ms"], "preprocess"),
            "on": stats([r for r in frames if "inputDiagnostics" in r and
                         float(r["inputDiagnostics"].removesuffix("ms")) > 0], "preprocess")},
        "execution": [dict(zip(execution_keys, values)) for values in configurations],
        "cpuFallbackCountLifetimeObserved": max((int(r["cpuFallbackCount"]) for r in frames if "cpuFallbackCount" in r), default=None),
        "diagnostics": diagnostic_analysis(lines, start_seconds, end, duration),
        "latency": latency_analysis(lines, frames, start_ns, start_seconds, end),
        "note": "Statistics use recorded successful FRAME samples. If frameLogComplete=false, FPS/P95/averages are incomplete. Error lines count only recorded errors; fallback counters have detector lifetime scope."
    }
    if interval_seconds is not None:
        result["longRun"] = long_run_analysis(frames, [fields(l) for l in lines if l.startswith("SYSTEM |")],
                                              start_seconds, end, interval_seconds)
    return result


def latency_analysis(lines, frames, start_ns, start, end):
    """Join sparse events by both IDs. Never infer physical output start or sum overlapping spans."""
    def integer(record, key):
        try:
            value = int(record[key])
            return value if value > 0 else None
        except (KeyError, TypeError, ValueError):
            return None

    def key(record):
        return (record.get("sessionId"), record.get("frameId"))

    def delta(record, later, earlier):
        hi, lo = integer(record, later), integer(record, earlier)
        return (hi - lo) / 1e6 if hi is not None and lo is not None and hi >= lo else None

    def summary(values):
        values = sorted(v for v in values if v is not None and math.isfinite(v))
        return {"samples": len(values), "averageMs": sum(values) / len(values) if values else None,
                "p95Ms": values[math.ceil(.95 * len(values)) - 1] if values else None}

    events = []
    unplaced = 0
    for line in lines:
        if not line.startswith("LATENCY |"):
            continue
        event = fields(line)
        observed = integer(event, "eventElapsedNs")
        if start_ns is None or observed is None:
            unplaced += 1
            continue
        if start <= (observed - start_ns) / 1e9 < end:
            events.append(event)

    by_stage = {stage: [r for r in events if r.get("stage") == stage]
                for stage in ("CAMERA_RECEIVED", "ANALYZER", "MLKIT", "WARNING", "OUTPUT")}
    sources = {key(f): f for f in frames if all(key(f))}
    sources.update({key(r): r for r in by_stage["CAMERA_RECEIVED"] if all(key(r))})
    warnings = {key(r): r for r in by_stage["WARNING"] if all(key(r))}

    def joined_delta(event, later, source_field):
        source = sources.get(key(event), {})
        return delta({"later": event.get(later), "earlier": source.get(source_field)}, "later", "earlier")

    result = {
        "eventCounts": {stage: len(records) for stage, records in by_stage.items()},
        "unplacedEventLines": unplaced,
        "inferenceCall": summary(delta(f, "inferenceEndElapsedNs", "inferenceStartElapsedNs") for f in frames),
        "inferenceEndToPipelineComplete": summary(
            delta(f, "pipelineCompletedElapsedNs", "inferenceEndElapsedNs") for f in frames),
        "analyzerToInferenceStart": summary(delta(f, "inferenceStartElapsedNs", "analyzerElapsedNs") for f in frames),
        "captureToPipelineComplete": summary(delta(f, "completedElapsedNs", "cameraTimestampNs") for f in frames
                                              if f.get("captureTimestampReliable") == "true"),
        "analyzerCallback": summary(joined_delta(r, "analyzerCallbackEndElapsedNs", "analyzerReceivedElapsedNs")
                                    if "analyzerReceivedElapsedNs" in sources.get(key(r), {}) else
                                    joined_delta(r, "analyzerCallbackEndElapsedNs", "analyzerElapsedNs")
                                    for r in by_stage["ANALYZER"]),
        "imageProxyClose": summary(delta(r, "imageProxyCloseEndElapsedNs", "imageProxyCloseStartElapsedNs")
                                    for r in by_stage["ANALYZER"] if r.get("closeSucceeded") == "true"),
        "mlKitDispatchToRequest": summary(delta(r, "mlKitRequestElapsedNs", "mlKitDispatchedElapsedNs")
                                         for r in by_stage["MLKIT"]),
        "mlKitRequestToCompletionObservation": summary(delta(r, "mlKitCompletedElapsedNs", "mlKitRequestElapsedNs")
                                                       for r in by_stage["MLKIT"]),
        "warningFinalizeToSubmit": summary(delta(r, "warningSubmittedElapsedNs", "warningCandidateFinalizedElapsedNs")
                                           for r in by_stage["WARNING"]),
        "warningSubmitCall": summary(delta(r, "warningSubmitReturnedElapsedNs", "warningSubmittedElapsedNs")
                                     for r in by_stage["WARNING"]),
        "outputs": {},
        "note": "Events use observation-time windows and may arrive out of order. Joins require sessionId and frameId. "
                "Missing spans/callbacks, logging boundaries and queue drops stay unavailable; event counts are not FPS. "
                "Interpreter times bracket the Java call. TTS onStart is callback receipt. Beep/vibration only expose API requests. "
                "Physical actuator/audio onset is unavailable. All intervals overlap existing FRAME totals; never add them."
    }
    for kind in ("TTS", "BEEP", "VIBRATION"):
        records = [r for r in by_stage["OUTPUT"] if r.get("kind") == kind]
        requests = [r for r in records if r.get("observation") == "API_REQUEST"]
        starts = [r for r in records if r.get("observation") == "TTS_ON_START"]
        first_requests = [r for r in requests if r.get("pulseIndex", "0") == "0"]
        def submit_to_request(record):
            warning = warnings.get(key(record), {})
            return delta({"request": record.get("outputRequestElapsedNs"),
                          "submit": warning.get("warningSubmittedElapsedNs")}, "request", "submit")
        result["outputs"][kind] = {
            "requestObservations": len(requests), "startCallbackObservations": len(starts),
            "startUnavailableObservations": sum(r.get("observation") == "TTS_START_UNAVAILABLE" for r in records),
            "apiCall": summary(delta(r, "outputRequestReturnedElapsedNs", "outputRequestElapsedNs") for r in requests),
            "warningSubmitToFirstRequest": summary(submit_to_request(r) for r in first_requests),
            "requestToStartCallback": summary(delta(r, "outputStartedElapsedNs", "outputRequestElapsedNs") for r in starts),
            "captureToStartCallback": summary(joined_delta(r, "outputStartedElapsedNs", "cameraTimestampNs")
                                               for r in starts if sources.get(key(r), {}).get("captureTimestampReliable") == "true"),
            "physicalStart": "N/A"
        }
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", nargs="+", type=Path)
    parser.add_argument("--start-seconds", type=float, default=0.0)
    parser.add_argument("--exclude-tail-seconds", type=float, default=0.0)
    parser.add_argument("--interval-seconds", type=float, help="Optional long-run windows and device policy correlations (e.g. 30)")
    args = parser.parse_args()
    results = []
    for path in args.logs:
        result = analyze(path.read_text(encoding="utf-8-sig"), args.start_seconds, args.exclude_tail_seconds, args.interval_seconds)
        results.append({"file": str(path), **result})
    print(json.dumps(results, ensure_ascii=False, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
