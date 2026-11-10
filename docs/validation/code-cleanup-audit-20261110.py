"""Capture pre-edit lexical references, hashes and decisions; never delete automatically.

Run from the repository root. Counts are candidates, not proof of runtime reachability:
Manifest/XML, reflection, settings codecs and source-set roots require manual review.
The captured TSV files intentionally describe the baseline, including deleted paths.
"""
from collections import Counter
import csv
import hashlib
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path.cwd()
OUT = ROOT / "docs/validation"
tracked = subprocess.check_output(["git", "ls-files", "-z"]).decode().split("\0")
tracked = [p for p in tracked if p]
source = {p: (ROOT / p).read_text(encoding="utf-8-sig") for p in tracked
          if p.startswith("app/src/") and Path(p).suffix in {".kt", ".java", ".xml", ".txt"}}
tokens = {p: Counter(re.findall(r"\b[A-Za-z_]\w*\b", text)) for p, text in source.items()}
deletions = {
    "app/src/main/java/com/samin/objectdetection/distance/Camera2DistanceGeometry.kt":
        "CameraController does not bind it; only its own declaration; no Manifest/XML/reflection lookup",
    "app/src/main/java/com/samin/objectdetection/warning/output/CompositeWarningPlayer.kt":
        "Composite implementation has no caller; keep WarningPlayer interface in its own file",
    "app/src/main/res/values/colors.xml": "No R.color, @color or dynamic resource lookup in app sources",
    "hs_err_pid21060.log": "Generated JVM native-memory crash dump; no build/runtime/tool consumer",
}
for p in tracked:
    if p.startswith(".guardian-work/"):
        deletions[p] = "Unrelated pas Spring/JSP/SQL scratch copies/classes; not included in Gradle/CI/tools"
    if p.startswith("app/src/main/java/com/samin/objectdetection/ui/theme/"):
        deletions[p] = "Compose template island; real screens use native Views; no outside caller/XML/reflection"


def write(name, header, rows):
    with (OUT / name).open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream, delimiter="\t")
        writer.writerow(header)
        writer.writerows(rows)


rows = []
for p in tracked:
    path = ROOT / p
    if not path.is_file():
        continue
    status, reason = "유지", "Tracked input/history; no proven safe deletion"
    if p in deletions:
        status, reason = "삭제 가능", deletions[p]
    elif p == "mlkit_diagnostics.txt":
        status, reason = "수정·통합", "Historical ML Kit raw log: relocate byte-for-byte into docs/validation"
    elif p == "arcore_depth.tar":
        status, reason = "확인 필요", "Archive cannot be listed by tar; historical data origin uncertain, preserve"
    elif p.endswith("/dto/DetectionEvent.kt"):
        status, reason = "확인 필요", "No app caller; preserve pending Device App DTO contract decision"
    elif p.endswith("/distance/GroundPlaneDistanceEstimator.kt"):
        status, reason = "유지", "Estimator used by 9 JVM tests; settings/result/JSON depend on shared distance contract types"
    elif p.endswith("/tof/TofMeasurement.kt"):
        status, reason = "유지", "Explicitly protected external ToF source/sample/status extension contract"
    elif p.endswith(("MainActivity.kt", "SettingsActivity.kt", "ImageProxyExt.kt")) or p.endswith(".gradle.kts") or p == "gradle/libs.versions.toml":
        status, reason = "수정·통합", "Separate test-app diagnostics/UI/capability responsibilities; remove proven unused dependencies"
    rows.append((p, status, reason, path.stat().st_size, hashlib.sha256(path.read_bytes()).hexdigest()))
write("code-cleanup-files-before-20261110.tsv", ["path", "classification", "reason", "bytes", "sha256"], rows)

rows = []
declaration = re.compile(r"\b(class|interface|object|fun|val|var)\s+(?:<[^>]+>\s*)?(?:[\w<>?.]+\.)?([A-Za-z_]\w*)")
for p, content in source.items():
    if not p.endswith((".kt", ".java")):
        continue
    for line, text in enumerate(content.splitlines(), 1):
        if text.lstrip().startswith(("//", "*", "import ", "package ")):
            continue
        for match in declaration.finditer(text):
            kind, name = match.groups()
            refs = [(q, counts[name]) for q, counts in tokens.items() if counts[name]]
            status = "삭제 가능" if p in deletions else "유지"
            reason = "Lexical references only; preserve unless manually proven unused"
            if sum(n for _, n in refs) == 1 and p not in deletions:
                status, reason = "확인 필요", "Single lexical occurrence; constructor/override/reflection/contract may be indirect"
            if "/tof/" in p:
                status, reason = "유지", "Explicitly protected future external ToF extension point"
            rows.append((p, line, kind, name, status, sum(n for _, n in refs),
                         "; ".join(f"{q}:{n}" for q, n in refs[:8]) +
                         (f"; ... ({len(refs)} files total)" if len(refs) > 8 else ""), reason))
write("code-cleanup-symbols-before-20261110.tsv",
      ["path", "line", "kind", "name", "classification", "lexical_count", "reference_files", "caveat"], rows)

dependencies = []
for p in ("app/build.gradle.kts", "build.gradle.kts", "gradle/libs.versions.toml", "gradle.properties"):
    for line, value in enumerate((ROOT / p).read_text(encoding="utf-8-sig").splitlines(), 1):
        if value.strip() and not value.lstrip().startswith(("#", "//")):
            dependencies.append((p, line, value.strip()))
write("code-cleanup-config-before-20261110.tsv", ["path", "line", "setting_or_dependency"], dependencies)

reports = list((ROOT / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
totals = Counter()
for report in reports:
    suite = ET.parse(report).getroot()
    for key in ("tests", "failures", "errors", "skipped"):
        totals[key] += int(suite.attrib.get(key, 0))
lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
totals.update({f"lint_{k}": v for k, v in Counter(i.attrib["severity"] for i in lint.findall("issue")).items()})
write("code-cleanup-baseline-summary-20261110.tsv", ["metric", "value"], [("suites", len(reports)), *sorted(totals.items())])
print(f"Captured {len(tracked)} tracked paths, {len(source)} source/resource texts, {len(rows)} declarations. {dict(totals)}")
