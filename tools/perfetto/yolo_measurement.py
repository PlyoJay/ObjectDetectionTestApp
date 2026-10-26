"""Build provenance and offline analysis for the unchanged YOLO app.

identity needs the existing tools/inspect_tflite.py dependencies. report/tests use
only stdlib. An optional native Trace Processor executable enables trace checks.
No command here installs/launches the app or starts an Android measurement.
"""
import argparse
import csv
from datetime import datetime, timezone
import hashlib
import io
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from analyze_performance_logs import analyze, fields

MODEL_SHA = "cc189262fd705e7d0b48246ebe42f868ff9b7370d97a0a6d7a7f756f16d4bad1"
SECTIONS = ("CameraX.toBitmap", "CameraX.rotate", "YOLO.resize", "YOLO.getPixels",
            "YOLO.rgbWrite", "YOLO.Interpreter.run", "MLKit.InputImage")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def source_identity():
    files = sorted(set(git("ls-files", "--cached", "--others", "--exclude-standard", "-z").decode().split("\0")) - {""})
    hashes = {name: sha((ROOT / name).read_bytes()) if (ROOT / name).is_file() else "MISSING" for name in files}
    return {"head": git("rev-parse", "HEAD").decode().strip(),
            "statusPorcelain": git("status", "--porcelain=v1").decode(),
            "filesSha256": hashes,
            "treeFingerprintSha256": sha(json.dumps(hashes, sort_keys=True).encode())}


def identity(args):
    from inspect_tflite import inspect
    args.output.mkdir(parents=True, exist_ok=True)
    before = source_identity()
    model = inspect(ROOT / "app/src/main/assets/best_512.tflite")
    labels = (ROOT / "app/src/main/assets/labels.txt").read_bytes()
    if (model["sha256"] != MODEL_SHA or model["inputs"][0]["shape"] != [1, 3, 512, 512]
            or model["outputs"][0]["shape"] != [1, 15, 5376]
            or labels.decode().split()[-2:] != ["cat", "dog"]):
        raise ValueError("Prepared contract requires the inspected 2026-10-26 11-class 512 model")
    save(args.output / "model-inspection.json", model)
    command = [str(ROOT / "gradlew.bat"), "assembleDebug", "--offline", "--console=plain"]
    if args.build_apk:
        with (args.output / "build.txt").open("w", encoding="utf-8") as log:
            subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    after = source_identity()
    if before != after:
        raise ValueError("Sources changed while preparing/building; repeat with a stable working tree")
    apk_path = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    apk = {"path": str(apk_path), "sha256": "N/A", "sourceBinding": "UNPROVEN_EXISTING_ARTIFACT"}
    if apk_path.exists():
        with zipfile.ZipFile(apk_path) as archive:
            apk["modelSha256"] = sha(archive.read("assets/best_512.tflite"))
            apk["labelsSha256"] = sha(archive.read("assets/labels.txt"))
        apk["sha256"] = sha(apk_path.read_bytes())
        if args.build_apk:
            if apk["modelSha256"] != model["sha256"] or apk["labelsSha256"] != sha(labels):
                raise ValueError("Built APK assets do not match the inspected model/labels")
            apk["sourceBinding"] = "BUILT_FROM_RECORDED_SOURCES"
    manifest = {"createdUtc": datetime.now(timezone.utc).isoformat(), "source": after,
                "buildCommand": command if args.build_apk else "NOT_RUN",
                "apk": apk, "model": {k: model[k] for k in ("sha256", "bytes", "inputs", "outputs", "inspectionRuntime")},
                "labelsSha256": sha(labels), "labels": labels.decode().splitlines(),
                "oldNineClassLogComparison": "Different model; historical context only, never same-model A/B"}
    save(args.output / "build-manifest.json", manifest)
    print(json.dumps({"head": after["head"], "modelSha256": model["sha256"], "apk": apk}, indent=2))


def header(text):
    def get(pattern):
        match = re.search(pattern, text, re.M)
        if not match:
            raise ValueError("Missing header: " + pattern)
        return match.group(1).strip()
    return {"sessionId": get(r"^Session ID: (.+)$"),
            "startNs": int(get(r"Start Elapsed Ns: (\d+)")),
            "startUnixMs": int(get(r"Start Unix Ms: (\d+)")),
            "modelSha256": get(r"^Model SHA256: (.+)$"),
            "input": get(r"^Input Tensor: (.+)$"), "output": get(r"^Output Tensor: (.+)$"),
            "settings": get(r"^--- Applied AppSettings Snapshot ---\r?\n([\s\S]*?)^--- End AppSettings Snapshot ---")}


def window_stats(text, start, end):
    start_ns = int(re.search(r"Start Elapsed Ns: (\d+)", text)[1])
    rows = [fields(line) for line in text.splitlines() if line.startswith("FRAME ")]
    rows = [r for r in rows if r.get("status", "SUCCESS") == "SUCCESS"
            and start <= (int(r.get("completedElapsedNs", "-1")) - start_ns) / 1e9 < end]
    result = {"startSeconds": start, "endSeconds": end, "successfulFrames": len(rows), "fps": len(rows) / (end - start)}
    for key in ("inference", "nativeInvoke", "totalProcessing"):
        values = sorted(float(r[key].removesuffix("ms")) for r in rows if key in r and float(r[key].removesuffix("ms")) >= 0)
        result[key] = {"meanMs": sum(values) / len(values) if values else None,
                       "p95Ms": values[math.ceil(.95 * len(values)) - 1] if values else None}
    return result


def clock_offset(rows):
    # Do not assume the UI/processor's trace timeline is always BOOTTIME.
    offsets = {int(r["ts"]) - int(r["clock_value"]) for r in rows if int(r["clock_id"]) == 6}
    if len(offsets) != 1:
        raise ValueError("N/A: missing/nonconstant BOOTTIME ClockSnapshot mapping; inspect clocks manually")
    return offsets.pop()


def loss_check(rows):
    findings = []
    known = any(r["name"] == "ftrace_cpu_overrun_end" for r in rows)
    indexed = {(r["name"], r["idx"]): int(r["value"]) for r in rows}
    for row in rows:
        name, value = row["name"], int(row["value"])
        if row["severity"] in ("data_loss", "error") and value:
            findings.append(row)
        elif any(x in name for x in ("overrun_end", "dropped_events_end")):
            if value > indexed.get((name.removesuffix("_end") + "_begin", row["idx"]), 0):
                findings.append(row)
        elif "overwritten" in name and value:
            findings.append(row)
    return {"status": "INVALID" if findings else "NO_RECORDED_LOSS" if known else "N/A",
            "findings": findings, "note": "Also inspect all stats/setup errors; missing counters do not prove zero loss."}


def trace_report(exe, trace, output):
    queries = {
        "bounds": "SELECT start_ts,end_ts FROM trace_bounds",
        "clocks": "SELECT ts,clock_id,clock_value FROM clock_snapshot WHERE clock_id=6",
        "stats": "SELECT name,idx,severity,source,value FROM stats ORDER BY name,idx",
        "metadata": "SELECT name,str_value,int_value FROM metadata WHERE name GLOB '*ftrace*' OR name GLOB '*clock*' OR name GLOB '*tracing*'",
        "cpu-tracks": "SELECT t.name,COUNT(*) AS samples FROM counter c JOIN cpu_counter_track t ON c.track_id=t.id GROUP BY t.id,t.name",
        "events": "SELECT name,COUNT(*) AS samples FROM ftrace_event WHERE name IN ('sched_switch','sched_waking','cpu_frequency','cpu_idle') GROUP BY name",
        "slices": "SELECT s.name,COUNT(*) AS count,AVG(s.dur)/1e6 AS mean_ms FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t USING(utid) JOIN process p USING(upid) WHERE p.name='com.samin.objectdetection' AND s.dur>0 GROUP BY s.name",
        "inference-states": """SELECT s.id,s.ts,s.dur,t.tid,t.name,
          SUM(CASE WHEN st.state='Running' THEN MIN(s.ts+s.dur,st.ts+st.dur)-MAX(s.ts,st.ts) ELSE 0 END)/1e6 AS running_ms,
          SUM(CASE WHEN st.state IN ('R','R+') THEN MIN(s.ts+s.dur,st.ts+st.dur)-MAX(s.ts,st.ts) ELSE 0 END)/1e6 AS runnable_ms,
          SUM(CASE WHEN st.state NOT IN ('Running','R','R+') THEN MIN(s.ts+s.dur,st.ts+st.dur)-MAX(s.ts,st.ts) ELSE 0 END)/1e6 AS other_ms
          FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
          LEFT JOIN thread_state st ON st.utid=t.utid AND st.dur>0 AND st.ts<s.ts+s.dur AND st.ts+st.dur>s.ts
          WHERE p.name='com.samin.objectdetection' AND s.name='YOLO.Interpreter.run' AND s.dur>0 GROUP BY s.id""",
    }
    tables = {}
    for name, query in queries.items():
        sql = output / (trace.stem + "-" + name + ".sql")
        sql.write_text(query + ";\n", encoding="utf-8")
        completed = subprocess.run([str(exe), "-q", str(sql), str(trace)], capture_output=True, text=True, encoding="utf-8", check=True)
        (output / (trace.stem + "-" + name + ".csv")).write_text(completed.stdout, encoding="utf-8")
        (output / (trace.stem + "-" + name + "-processor.txt")).write_text(completed.stderr, encoding="utf-8")
        tables[name] = list(csv.DictReader(io.StringIO(completed.stdout)))
    result = {"file": trace.name, "loss": loss_check(tables["stats"]),
              "sections": {name: next((r for r in tables["slices"] if r["name"] == name), "N/A: not observed (path disabled, app trace unavailable or no complete slice)") for name in SECTIONS},
              "gcCandidates": [r for r in tables["slices"] if "gc" in r["name"].lower() or "suspendall" in r["name"].lower()],
              "gcNote": "Candidate slices only; inspect ART GC and pause nesting. Empty means N/A/not observed, not proven zero GC.",
              "eventEvidence": {name: next((r for r in tables["events"] if r["name"] == name),
                  "N/A: not observed; check support/setup and compact_sched before declaring unsupported")
                  for name in ("sched_switch", "sched_waking", "cpu_frequency", "cpu_idle")},
              "frequencyTracks": tables["cpu-tracks"],
              "frequencyNote": "cpufreq tracks include linux.sys_stats polling; track presence alone does not prove a kernel cpu_frequency event."}
    setup = [r for r in tables["stats"] if r["name"] == "ftrace_setup_errors" and int(r["value"]) > 0]
    result["setupErrors"] = setup
    result["analysisStatus"] = "CHECK_SETUP_ERRORS" if setup else "CHECK_COVERAGE_AND_EVENTS"
    try:
        offset = clock_offset(tables["clocks"])
        bounds = tables["bounds"][0]
        result.update(offsetNs=offset, bootStartNs=int(bounds["start_ts"]) - offset,
                      bootEndNs=int(bounds["end_ts"]) - offset)
    except (ValueError, IndexError, KeyError) as error:
        result["clockMapping"] = str(error)
    return result


def join_inferences(text, trace, states):
    """The existing generic slices have no frame ID: require a unique enclosing log interval."""
    frames = [fields(line) for line in text.splitlines() if line.startswith("FRAME ")]
    joined = []
    for row in states:
        start = int(row["ts"]) - trace["offsetNs"]
        end = start + int(row["dur"])
        matches = [f for f in frames if f.get("status", "SUCCESS") == "SUCCESS"
                   and 0 < int(f.get("inferenceStartElapsedNs", "-1")) <= start
                   and int(f.get("inferenceEndElapsedNs", "-1")) >= end]
        joined.append({**row, "sliceStartBootNs": start, "sliceEndBootNs": end,
                       "frameId": matches[0]["frameId"] if len(matches) == 1 else "N/A",
                       "matchCount": len(matches), "nativeInvoke": matches[0].get("nativeInvoke", "N/A") if len(matches) == 1 else "N/A"})
    return joined


def report(args):
    run = json.loads((args.run / "run.json").read_text(encoding="utf-8-sig"))
    text = (args.run / "performance.txt").read_text(encoding="utf-8-sig")
    info = header(text)
    if info["modelSha256"] != MODEL_SHA or info["sessionId"] != run["session"]["sessionId"]:
        raise ValueError("Session/model mismatch")
    summary = analyze(text, interval_seconds=30)
    stop = next((fields(line) for line in text.splitlines() if line.startswith("TEST_SESSION |")), {})
    if stop.get("configuredDurationMs") != "300000" or stop.get("stopReason") != "AUTO_DURATION":
        raise ValueError("A finalized five-minute AUTO_DURATION session is required")
    result = {"header": info, "performance": summary, "traces": [], "traceImpact": "N/A: need matched new-model baseline and actual trace bounds"}
    baseline_text = None
    if args.baseline:
        base_run = json.loads((args.baseline / "run.json").read_text(encoding="utf-8-sig"))
        baseline_text = (args.baseline / "performance.txt").read_text(encoding="utf-8-sig")
        base_info = header(baseline_text)
        base_summary = analyze(baseline_text)
        if (base_run["mode"] != "Baseline" or base_run["apkSha256"] != run["apkSha256"]
                or any(base_info[k] != info[k] for k in ("modelSha256", "input", "output", "settings"))):
            raise ValueError("Comparison requires Baseline mode, identical APK/model/shapes/settings. Old nine-class logs are not A/B.")
        if not base_summary["frameLogComplete"] or not summary["frameLogComplete"]:
            raise ValueError("Dropped/missing FRAME lines: exact FPS/P95 impact unavailable")
        result["traceImpact"] = []
    for capture in run.get("captures", []):
        if not args.trace_processor:
            result["traces"].append({"file": capture.get("traceFile", "N/A"), "status": "N/A: supply --trace-processor for clocks, slices, stats and data loss"})
            continue
        trace = trace_report(args.trace_processor, args.run / capture["traceFile"], args.run)
        if "bootStartNs" in trace:
            state_csv = args.run / (Path(capture["traceFile"]).stem + "-inference-states.csv")
            trace["inferenceFrameJoins"] = join_inferences(text, trace, list(csv.DictReader(io.StringIO(state_csv.read_text(encoding="utf-8")))))
            start = (trace["bootStartNs"] - info["startNs"]) / 1e9
            end = (trace["bootEndNs"] - info["startNs"]) / 1e9
            lo, hi = (0, 30) if capture["name"] == "early" else (180, 210)
            trace["sessionWindowSeconds"] = [start, end]
            valid = lo <= start < end <= hi and 18 <= end - start <= 23
            trace["coverage"] = "IN_REQUESTED_WINDOW" if valid else "INVALID_WINDOW_OR_DURATION"
            if valid and baseline_text:
                base = window_stats(baseline_text, start, end)
                replay = window_stats(text, start, end)
                result["traceImpact"].append({"window": capture["name"], "baseline": base, "replay": replay,
                    "fpsChangePercent": (replay["fps"] / base["fps"] - 1) * 100 if base["fps"] else None,
                    "traceLossStatus": trace["loss"]["status"],
                    "note": "Descriptive paired window difference; includes setup/transfer/thermal/order effects, not isolated causal overhead. Review conditions.txt and repeated alternate runs."})
        result["traces"].append(trace)
    save(args.run / "analysis.json", result)
    print("Wrote", args.run / "analysis.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prep = sub.add_parser("identity")
    prep.add_argument("--output", type=Path, required=True)
    prep.add_argument("--build-apk", action="store_true")
    analysis = sub.add_parser("report")
    analysis.add_argument("--run", type=Path, required=True)
    analysis.add_argument("--baseline", type=Path)
    analysis.add_argument("--trace-processor", type=Path, help="Native trace_processor_shell.exe, not the Python download wrapper")
    args = parser.parse_args()
    (identity if args.command == "identity" else report)(args)


if __name__ == "__main__":
    main()
