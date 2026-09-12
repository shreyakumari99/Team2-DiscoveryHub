#!/usr/bin/env bash
#
# Builds ONE page summarising the backend test suite: every module, every test
# class, every test name, pass/fail, and line coverage — with links out to the
# per-module JaCoCo reports.
#
#   ./scripts/test-report.sh            # build the page and open it
#   ./scripts/test-report.sh --no-open  # build only
#
# Reads what `./scripts/test-all.sh` already produced:
#   services/<module>/target/surefire-reports/TEST-*.xml   (test results)
#   services/<module>/target/site/jacoco/jacoco.csv        (coverage)
#
# So run the tests first. If a module has no results yet it is listed as
# "not run" rather than being silently skipped, because a missing module is
# something you want to notice.
#
# Output: reports/backend-tests.html  (gitignored, regenerate any time)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

OPEN_REPORT=true
for arg in "$@"; do
  case "$arg" in
    --no-open) OPEN_REPORT=false ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

OUT_DIR="reports"
OUT="$OUT_DIR/backend-tests.html"
mkdir -p "$OUT_DIR"

python3 - "$OUT" <<'PY'
import csv, glob, html, os, sys
import xml.etree.ElementTree as ET

out_path = sys.argv[1]

MODULES = [
    ("services/service-commons",          "service-commons"),
    ("services/ingestion-service",        "ingestion-service"),
    ("services/archive-service",          "archive-service"),
    ("services/search-service",           "search-service"),
    ("services/case-service",             "case-service"),
    ("services/hold-retention-service",   "hold-retention-service"),
    ("services/export-service",           "export-service"),
    ("services/audit-service",            "audit-service"),
    ("data-generator",                    "data-generator"),
]


def read_tests(module_dir):
    """Every test class in a module, with its individual test names."""
    classes = []
    for xml in sorted(glob.glob(f"{module_dir}/target/surefire-reports/TEST-*.xml")):
        try:
            suite = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        cases = []
        for case in suite.findall("testcase"):
            failed = (case.find("failure") is not None
                      or case.find("error") is not None)
            skipped = case.find("skipped") is not None
            cases.append((case.get("name", "?"), failed, skipped))
        classes.append({
            "name": suite.get("name", "?").split(".")[-1],
            "fqcn": suite.get("name", "?"),
            "tests": int(suite.get("tests", 0)),
            "failures": int(suite.get("failures", 0)) + int(suite.get("errors", 0)),
            "skipped": int(suite.get("skipped", 0)),
            "time": float(suite.get("time", 0) or 0),
            "cases": cases,
        })
    return classes


def read_coverage(module_dir):
    """Line coverage for a module, or None if JaCoCo produced nothing."""
    csv_path = f"{module_dir}/target/site/jacoco/jacoco.csv"
    if not os.path.exists(csv_path):
        return None
    missed = covered = 0
    with open(csv_path) as fh:
        for row in csv.DictReader(fh):
            missed += int(row["LINE_MISSED"])
            covered += int(row["LINE_COVERED"])
    total = missed + covered
    return (covered, total) if total else None


rows, total_tests, total_failed, total_time = [], 0, 0, 0.0
for module_dir, label in MODULES:
    classes = read_tests(module_dir)
    coverage = read_coverage(module_dir)
    tests = sum(c["tests"] for c in classes)
    failed = sum(c["failures"] for c in classes)
    skipped = sum(c["skipped"] for c in classes)
    secs = sum(c["time"] for c in classes)
    total_tests += tests
    total_failed += failed
    total_time += secs
    rows.append({
        "dir": module_dir, "label": label, "classes": classes,
        "tests": tests, "failed": failed, "skipped": skipped,
        "time": secs, "coverage": coverage,
        "jacoco": f"{module_dir}/target/site/jacoco/index.html",
    })


def bar(pct):
    colour = "#2e7d32" if pct >= 70 else ("#f9a825" if pct >= 50 else "#c62828")
    return (f'<div class="bar"><span style="width:{pct:.1f}%;background:{colour}"></span></div>'
            f'<span class="pct">{pct:.1f}%</span>')


parts = ["""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>DiscoveryHub — backend test report</title>
<style>
 body{font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;
      margin:0;padding:2rem;background:#f6f7f9;color:#1b1f24}
 h1{margin:0 0 .25rem;font-size:1.5rem}
 .sub{color:#667;margin-bottom:1.5rem}
 .totals{display:flex;gap:1rem;margin-bottom:1.5rem;flex-wrap:wrap}
 .card{background:#fff;border:1px solid #e3e6ea;border-radius:8px;padding:1rem 1.25rem;min-width:120px}
 .card .n{font-size:1.6rem;font-weight:600}
 .card .l{color:#667;font-size:.8rem;text-transform:uppercase;letter-spacing:.04em}
 table{width:100%;border-collapse:collapse;background:#fff;
        border:1px solid #e3e6ea;border-radius:8px;overflow:hidden}
 th,td{padding:.55rem .8rem;text-align:left;border-bottom:1px solid #eef0f3}
 th{background:#fafbfc;font-size:.78rem;text-transform:uppercase;letter-spacing:.04em;color:#667}
 tr:last-child td{border-bottom:none}
 .num{text-align:right;font-variant-numeric:tabular-nums}
 .ok{color:#2e7d32;font-weight:600}
 .bad{color:#c62828;font-weight:600}
 .muted{color:#889}
 .bar{display:inline-block;width:110px;height:8px;background:#e9ecef;
      border-radius:4px;overflow:hidden;vertical-align:middle;margin-right:.5rem}
 .bar span{display:block;height:100%}
 .pct{font-variant-numeric:tabular-nums;font-size:.85rem}
 details{margin:.9rem 0;background:#fff;border:1px solid #e3e6ea;border-radius:8px}
 summary{padding:.7rem 1rem;cursor:pointer;font-weight:600}
 details table{border:none;border-radius:0}
 code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.85rem}
 a{color:#1565c0}
</style></head><body>
<h1>DiscoveryHub — backend test report</h1>
"""]

status = ('<span class="ok">all passing</span>' if total_failed == 0
          else f'<span class="bad">{total_failed} failing</span>')
parts.append(f'<div class="sub">Generated from the last <code>./scripts/test-all.sh</code> run — {status}</div>')

covered_all = sum(r["coverage"][0] for r in rows if r["coverage"])
total_all = sum(r["coverage"][1] for r in rows if r["coverage"])
overall = covered_all / total_all * 100 if total_all else 0

parts.append('<div class="totals">')
for n, l in [(total_tests, "tests"), (len([r for r in rows if r["tests"]]), "modules"),
             (f"{overall:.1f}%", "line coverage"), (f"{total_time:.1f}s", "run time")]:
    parts.append(f'<div class="card"><div class="n">{n}</div><div class="l">{l}</div></div>')
parts.append('</div>')

parts.append("""<table><thead><tr>
<th>Module</th><th class="num">Tests</th><th class="num">Failed</th>
<th class="num">Skipped</th><th>Line coverage</th><th>Detail</th>
</tr></thead><tbody>""")

for r in rows:
    if not r["classes"]:
        parts.append(f'<tr><td><code>{html.escape(r["label"])}</code></td>'
                     f'<td colspan="5" class="muted">not run — no surefire results</td></tr>')
        continue
    cov = bar(r["coverage"][0] / r["coverage"][1] * 100) if r["coverage"] else '<span class="muted">no report</span>'
    failed = (f'<span class="bad">{r["failed"]}</span>' if r["failed"]
              else '<span class="ok">0</span>')
    link = (f'<a href="../{r["jacoco"]}">JaCoCo</a>'
            if os.path.exists(r["jacoco"]) else '<span class="muted">—</span>')
    parts.append(f'<tr><td><code>{html.escape(r["label"])}</code></td>'
                 f'<td class="num">{r["tests"]}</td><td class="num">{failed}</td>'
                 f'<td class="num">{r["skipped"] or ""}</td><td>{cov}</td><td>{link}</td></tr>')
parts.append('</tbody></table>')

parts.append('<h2 style="font-size:1.1rem;margin:2rem 0 .5rem">Every test, by module</h2>')
for r in rows:
    if not r["classes"]:
        continue
    parts.append(f'<details><summary>{html.escape(r["label"])} — '
                 f'{r["tests"]} tests in {len(r["classes"])} classes</summary><table><tbody>')
    for c in r["classes"]:
        parts.append(f'<tr><td colspan="2"><strong>{html.escape(c["name"])}</strong> '
                     f'<span class="muted">({c["tests"]} tests, {c["time"]:.2f}s)</span></td></tr>')
        for name, failed_case, skipped_case in c["cases"]:
            mark = ('<span class="bad">FAIL</span>' if failed_case
                    else ('<span class="muted">skip</span>' if skipped_case
                          else '<span class="ok">&#10003;</span>'))
            pretty = name.replace("()", "")
            parts.append(f'<tr><td style="width:3rem">{mark}</td>'
                         f'<td><code>{html.escape(pretty)}</code></td></tr>')
    parts.append('</tbody></table></details>')

parts.append('</body></html>')

with open(out_path, "w") as fh:
    fh.write("\n".join(parts))
print(f"  {total_tests} tests across {len([r for r in rows if r['tests']])} modules, "
      f"{total_failed} failing, {overall:.1f}% line coverage")
PY

echo "  written to $OUT"

if [ "$OPEN_REPORT" = true ]; then
  if command -v open >/dev/null 2>&1; then
    open "$OUT"
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$OUT" >/dev/null 2>&1
  fi
fi
