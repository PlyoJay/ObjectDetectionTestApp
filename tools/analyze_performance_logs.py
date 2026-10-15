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
    return dict(re.findall(r"(?:^|[\s|])(\w+)=([^\s|]+)", line))


def stats(records, key):
    values = sorted(int(r[key].removesuffix("ms")) for r in records
                    if key in r and int(r[key].removesuffix("ms")) >= 0)
    return {"samples": len(values), "averageMs": sum(values) / len(values) if values else None,
            "p95Ms": values[math.ceil(len(values) * 0.95) - 1] if values else None}


def detail_stats(records, key):
    """Overlapping detail fields are reported separately, never added to total latency."""
    values = sorted(float(r[key].removesuffix("ms")) for r in records
                    if key in r and float(r[key].removesuffix("ms")) >= 0)
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
                    "averageDischargingCurrentMa": by_status.get("Discharging", {}).get("batteryCurrentMa", {}).get("average", "N/A"),
                    "averageEstimatedDischargingPowerMw": by_status.get("Discharging", {}).get("estimatedBatteryPowerMw", {}).get("average", "N/A"),
                    "fullSessionSummary": summary if start == 0 and end == duration and summary else "N/A",
                    "sampleLogComplete": len(batteries) == int(summary["samples"])
                    if start == 0 and end == duration and summary and "samples" in summary else "N/A",
                    "fullSessionStatusSummaries": [fields(line) for line in lines if line.startswith("BATTERY_STATUS_SUMMARY |")]
                    if start == 0 and end == duration else [],
                    "note": "Device battery, signed current/power; estimates are not app consumption. Means use valid recorded samples grouped by status. Battery temperature is not CPU/GPU temperature. 0% level delta does not mean zero power. Polls can miss transitions; headroom may be cached. See fullSessionSummary for authoritative full-session counts/endpoints if sample lines were dropped."}}


def analyze(text, start_seconds=0.0, exclude_tail_seconds=0.0):
    lines = text.splitlines()
    cumulative = next((fields(line) for line in lines if line.startswith("CUMULATIVE_SUMMARY |")), None)
    if cumulative is None:
        raise ValueError("A finalized log with CUMULATIVE_SUMMARY is required for an exact end time")
    duration = float(cumulative["endSeconds"])
    end = duration - exclude_tail_seconds
    if not 0 <= start_seconds < end <= duration:
        raise ValueError("The requested measurement window is empty or outside the recording")
    elapsed_start = re.search(r"Start Elapsed Ns: (\d+)", text)
    start_ns = int(elapsed_start.group(1)) if elapsed_start else None

    def elapsed(record):
        if start_ns is not None and record.get("completedElapsedNs", "null") != "null":
            return (int(record["completedElapsedNs"]) - start_ns) / 1e9
        return float(record["elapsedSeconds"])

    all_frames = [fields(line) for line in lines if line.startswith("FRAME ")]
    frames = [r for r in all_frames if r.get("status", "SUCCESS") == "SUCCESS" and
              start_seconds <= elapsed(r) < end]
    errors = [fields(line) for line in lines if line.startswith("ERROR |")]
    errors_in_window = sum(start_seconds <= elapsed(r) < end for r in errors)
    dropped = re.search(r"Dropped Log Lines: (\d+)", text)
    dropped_count = int(dropped.group(1)) if dropped else None
    expected_count = int(cumulative["successfulFrames"])
    complete = dropped_count == 0 and len(all_frames) == expected_count
    execution_keys = ("requestedBackend", "appliedBackend", "cpuThreads", "xnnpackRequested",
                      "initialization", "delegateVerification", "gpuSupport", "runtimeVersion")
    configurations = sorted({tuple(r.get(key, "N/A") for key in execution_keys) for r in frames})
    return {
        "windowStartSeconds": start_seconds, "windowEndSeconds": end,
        "excludedTailSeconds": exclude_tail_seconds,
        "successfulFrameSamples": len(frames), "errorLinesInWindow": errors_in_window,
        "droppedLogLines": dropped_count, "frameLogComplete": complete,
        "fpsFromRecordedFrames": len(frames) / (end - start_seconds),
        "inference": stats(frames, "inference"), "pipeline": stats(frames, "pipeline"),
        "totalProcessing": stats(frames, "totalProcessing"),
        "preprocess": stats(frames, "preprocess"), "bitmap": stats(frames, "bitmap"),
        "details": {key: detail_stats(frames, key) for key in
                    ("cameraToBitmap", "rotation", "inputBuffer", "getPixels", "rgbWrite",
                     "inputDiagnostics", "outputDiagnostics", "nativeInvoke", "mlKitPrepare")},
        "preprocessByInputDiagnostics": {
            "off": stats([r for r in frames if r.get("inputDiagnostics") == "0.000ms"], "preprocess"),
            "on": stats([r for r in frames if "inputDiagnostics" in r and
                         float(r["inputDiagnostics"].removesuffix("ms")) > 0], "preprocess")},
        "execution": [dict(zip(execution_keys, values)) for values in configurations],
        "cpuFallbackCountLifetimeObserved": max((int(r["cpuFallbackCount"]) for r in frames if "cpuFallbackCount" in r), default=None),
        "diagnostics": diagnostic_analysis(lines, start_seconds, end, duration),
        "note": "Statistics use recorded successful FRAME samples. If frameLogComplete=false, FPS/P95/averages are incomplete. Error lines count only recorded errors; fallback counters have detector lifetime scope."
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", nargs="+", type=Path)
    parser.add_argument("--start-seconds", type=float, default=0.0)
    parser.add_argument("--exclude-tail-seconds", type=float, default=0.0)
    args = parser.parse_args()
    results = []
    for path in args.logs:
        result = analyze(path.read_text(encoding="utf-8-sig"), args.start_seconds, args.exclude_tail_seconds)
        results.append({"file": str(path), **result})
    print(json.dumps(results, ensure_ascii=False, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
