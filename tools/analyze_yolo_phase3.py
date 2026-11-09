"""Summarize existing YOLO logs without treating missing data or short runs as long-run evidence."""
import argparse
import hashlib
import json
import math
from pathlib import Path

from analyze_performance_logs import analyze, correlation


def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def window_correlations(windows):
    """Equal-weight, complete 30-second windows; no partial tail, missing imputation or frame joins."""
    complete = [w for w in windows if w["frames"] > 0 and w["endSeconds"] - w["startSeconds"] >= 30 - 1e-6]
    result = {}
    for key in ("processCpuUsage", "thermalHeadroom"):
        pairs = [(w[key]["average"], w["inference"]["averageMs"]) for w in complete
                 if finite(w[key]["average"]) and finite(w["inference"]["averageMs"])]
        result[key] = {"pairedWindows": len(pairs), "pearsonR": correlation(pairs)}
    policy_keys = {(name, key) for w in complete for name in w["cpuPolicies"]
                   for key in ("currentKHz", "scalingMaxKHz")}
    for name, key in sorted(policy_keys):
        pairs = [(w["cpuPolicies"][name][key]["average"], w["inference"]["averageMs"]) for w in complete
                 if name in w["cpuPolicies"] and finite(w["cpuPolicies"][name][key]["average"])
                 and finite(w["inference"]["averageMs"])]
        result[f"{name}.{key}"] = {"pairedWindows": len(pairs), "pearsonR": correlation(pairs)}
    return result


def phase3_analysis(text):
    whole = analyze(text, interval_seconds=30)
    duration = whole["windowEndSeconds"]
    header_keys = {"Model", "Model SHA256", "Model Classes", "Input Tensor", "Output Tensor",
                   "YOLO Threads", "ML Kit Enabled", "Camera Backend", "Camera Resolution",
                   "Requested Camera Resolution", "Settings Schema Version", "Preset"}
    headers = {}
    for line in text.splitlines():
        key, sep, value = line.partition(":")
        if sep and key in header_keys:
            headers[key] = value.strip()
    if duration >= 60:
        first = analyze(text, exclude_tail_seconds=duration - 30)
        last = analyze(text, start_seconds=duration - 30)
        boundaries = {"available": True, "first30": first, "last30": last}
    else:
        boundaries = {"available": False, "reason": "Fewer than 60 seconds: no two complete non-overlapping 30-second windows"}
    inference, pipeline = whole["inference"], whole["pipeline"]
    share = (inference["averageMs"] / pipeline["averageMs"]
             if inference["samples"] == pipeline["samples"] > 0
             and finite(inference["averageMs"]) and finite(pipeline["averageMs"])
             and pipeline["averageMs"] > 0 else None)
    return {
        "headers": headers,
        "durationSeconds": duration,
        "inferenceFractionOfPipelineMean": share,
        "completeWindowCorrelations": window_correlations(whole["longRun"]["windows"]),
        "boundaries": boundaries,
        "whole": whole,
        "caution": "Correlations are not causal; CPU is process-wide, frequency is device-policy scope, "
                   "headroom can be cached. Different model hashes/settings must not be pooled. "
                   "Dropped FRAME rows make their statistics incomplete. No missing metric is replaced with zero."
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", nargs="+", type=Path)
    args = parser.parse_args()
    output = []
    for path in args.logs:
        raw = path.read_bytes()
        output.append({"file": str(path), "sha256": hashlib.sha256(raw).hexdigest(),
                       **phase3_analysis(raw.decode("utf-8-sig"))})
    print(json.dumps(output, ensure_ascii=False, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
