"""Summarize manually labelled CAMERA_FORWARD_Z records from existing JSON/JSONL/perf logs.

No ground truth is synthesized. Coverage is among detected, unambiguous labelled objects,
not detector recall. Delay is observation/processing + depth age, not physical motion onset.
"""
import argparse
import json
import math
import statistics
from pathlib import Path


def finite_positive(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value > 0


def measurements(value, inherited=None):
    if isinstance(value, list):
        for item in value:
            yield from measurements(item, inherited)
    elif isinstance(value, dict):
        context = dict(inherited or {})
        for key in ("sessionId", "frameId", "frameTimestampNs"):
            if key in value:
                context[key] = value[key]
        if "groundTruthMeters" in value:
            yield {**context, **value}
        for key in ("detections", "evaluationDetections"):
            if key in value:
                yield from measurements(value[key], context)


def read_records(path):
    body = path.read_text(encoding="utf-8-sig")
    try:
        yield from measurements(json.loads(body))
        return
    except json.JSONDecodeError:
        pass
    decoder = json.JSONDecoder()
    for line in body.splitlines():
        try:
            yield from measurements(json.loads(line))
            continue
        except json.JSONDecodeError:
            pass
        marker = "depthObjects="
        if marker in line:
            try:
                import re
                context = dict(re.findall(r"\b(sessionId|frameId)=([^\s|]+)", line))
                payload, _ = decoder.raw_decode(line.split(marker, 1)[1])
                yield from measurements(payload, context)
            except json.JSONDecodeError:
                continue


def distribution(values):
    values = sorted(values)
    return {"count": len(values), "mean": statistics.mean(values) if values else None,
            "median": statistics.median(values) if values else None,
            "p90": values[math.ceil(len(values) * .9) - 1] if values else None}


def error_metrics(rows, key):
    pairs = [(r[key] - r["groundTruthMeters"]) for r in rows if finite_positive(r.get(key))]
    absolute = distribution([abs(v) for v in pairs])
    return {"count": absolute["count"], "maeMeters": absolute["mean"], "medianAbsoluteErrorMeters": absolute["median"],
            "p90AbsoluteErrorMeters": absolute["p90"], "meanSignedErrorMeters": statistics.mean(pairs) if pairs else None}


def analyze(records):
    rows = []
    seen = set()
    skipped = 0
    for r in records:
        truth = r.get("groundTruthMeters")
        if not finite_positive(truth) or r.get("groundTruthDefinition") != "CAMERA_FORWARD_Z" or r.get("groundTruthStatus") != "UNIQUE_TARGET":
            skipped += 1
            continue
        label = r.get("objectLabel", r.get("label"))
        if not label:
            skipped += 1
            continue
        # Existing capture/recording/performance exports may contain the same frame twice.
        frame = r.get("frameId", r.get("frameTimestampNs"))
        identity = (str(r.get("sessionId", "unknown")), str(frame), label, str(r.get("depthTrackId", r.get("detectionIndex", ""))))
        if frame is not None and identity in seen:
            continue
        if frame is not None:
            seen.add(identity)
        rows.append(r)
    valid = [r for r in rows if finite_positive(r.get("estimatedDistanceMeters"))]
    paired = [r for r in valid if finite_positive(r.get("rawDepthMeters"))]
    delays = [r["distanceUpdateDelayMs"] for r in rows if isinstance(r.get("distanceUpdateDelayMs"), (int, float))
              and math.isfinite(r["distanceUpdateDelayMs"]) and r["distanceUpdateDelayMs"] >= 0]
    per_distance = {}
    for truth in sorted({r["groundTruthMeters"] for r in rows}):
        subset = [r for r in rows if r["groundTruthMeters"] == truth]
        per_distance[f"{truth:g}m"] = {"attempts": len(subset), "raw": error_metrics(subset, "rawDepthMeters"),
                                     "displayed": error_metrics(subset, "estimatedDistanceMeters")}
    methods = {}
    for method in sorted({str(r.get("depthMethod")) for r in rows}):
        subset = [r for r in rows if str(r.get("depthMethod")) == method]
        methods[method] = {"attempts": len(subset), "raw": error_metrics(subset, "rawDepthMeters")}
    comparisons = []
    for row in rows:
        detail = row.get("depthAnalysis") or {}
        comparisons.append({**row, "fullCandidateMeters": (detail.get("full") or {}).get("meters"),
                            "rawCandidateMeters": (detail.get("raw") or {}).get("meters")})
    both = [r for r in comparisons if finite_positive(r.get("fullCandidateMeters")) and finite_positive(r.get("rawCandidateMeters"))]
    selected_methods = {}
    for method in sorted({str(r.get("selectedDepthMethod") or (r.get("depthMethod") if finite_positive(r.get("estimatedDistanceMeters")) else "NONE")) for r in rows}):
        subset = [r for r in rows if str(r.get("selectedDepthMethod") or (r.get("depthMethod") if finite_positive(r.get("estimatedDistanceMeters")) else "NONE")) == method]
        selected_methods[method] = {"attempts": len(subset), "displayed": error_metrics(subset, "estimatedDistanceMeters")}
    return {"definition": "CAMERA_FORWARD_Z", "labelledAttempts": len(rows), "skippedUnlabelledOrAmbiguous": skipped,
            "displayedCount": len(valid), "validDisplayRatio": len(valid) / len(rows) if rows else None,
            "raw": error_metrics(rows, "rawDepthMeters"), "displayed": error_metrics(rows, "estimatedDistanceMeters"),
            "pairedRaw": error_metrics(paired, "rawDepthMeters"), "pairedDisplayed": error_metrics(paired, "estimatedDistanceMeters"),
            "distanceUpdateDelayMs": distribution(delays), "byGroundTruth": per_distance, "byAttemptedMethod": methods, "bySelectedMethod": selected_methods,
            "fullCandidate": error_metrics(comparisons, "fullCandidateMeters"), "rawCandidate": error_metrics(comparisons, "rawCandidateMeters"),
            "pairedFullCandidate": error_metrics(both, "fullCandidateMeters"), "pairedRawCandidate": error_metrics(both, "rawCandidateMeters"),
            "limitations": "Detected UNIQUE_TARGET records only; validDisplayRatio means valid-distance availability, not measured Canvas draws or detector recall. No physical motion-onset latency. Raw/display denominators may differ; paired metrics share records. P90 uses nearest rank. No measurements means null metrics."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = analyze(r for p in args.inputs for r in read_records(p))
    rendered = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    else:
        print(rendered)


if __name__ == "__main__":
    main()
