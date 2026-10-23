#!/usr/bin/env bash
# Legacy (GnuCOBOL 3.1.2 + C/SQLite stub harness) vs migrated (Java 21 / Spring Boot 3 / H2) parity, round 2 (round-1 results kept in parity/results/round1/ for the delta table).
# Usage: parity/run-parity.sh            (writes parity/results/*.json and parity/report/parity-report.html)
#        SKIP_MVN=1 parity/run-parity.sh (reuse target/ + surefire reports from a previous mvn verify)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
SVC="$ROOT/src/cbsa-core-service"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
RES="$HERE/results"
mkdir -p "$RES" "$HERE/report" "$HERE/build"

echo "== 1. legacy build + baseline replay (legacy/run-all.sh)"
"$ROOT/legacy/build.sh" > "$HERE/build/legacy-build.log" 2>&1
python3 - "$ROOT" "$RES/legacy-replay-all.json" <<'PY'
import glob, json, os, sys
root, out = sys.argv[1], sys.argv[2]
sys.path.insert(0, os.path.join(root, 'legacy', 'tools'))
import harness
rows = []
for p in sorted(glob.glob(os.path.join(root, 'baseline', 'transcripts', '*', '*.json'))):
    st, d = harness.replay(p, quiet=True)
    rows.append({'transcript': os.path.relpath(p, root), 'status': st, 'diffs': d})
tot = {s: sum(1 for r in rows if r['status'] == s) for s in ('match', 'diff', 'fixture')}
json.dump({'command': 'legacy/run-transcript.sh <each baseline transcript> (harness.replay)', 'total': len(rows),
           'totals': tot, 'transcripts': rows}, open(out, 'w'), indent=1)
print(len(rows), tot)
PY

echo "== 2. migrated build + tests (mvn -B verify)"
if [ -z "${SKIP_MVN:-}" ]; then
  (cd "$SVC" && mvn -B -q verify > "$HERE/build/mvn-verify.log" 2>&1) || { tail -50 "$HERE/build/mvn-verify.log"; exit 1; }
fi
python3 - "$SVC" "$RES/tests-migrated.json" <<'PY'
import glob, json, sys, xml.etree.ElementTree as ET
svc, out = sys.argv[1], sys.argv[2]
rows = []
for f in sorted(glob.glob(svc + '/target/surefire-reports/TEST-*.xml')):
    r = ET.parse(f).getroot()
    rows.append({'class': r.get('name'), **{k: int(r.get(k)) for k in ('tests', 'failures', 'errors', 'skipped')},
                 'time_s': float(r.get('time'))})
tot = {k: sum(x[k] for x in rows) for k in ('tests', 'failures', 'errors', 'skipped')}
json.dump({'tool': 'mvn -B verify (surefire)', 'classes': len(rows), 'totals': tot, 'per_class': rows}, open(out, 'w'), indent=1)
print(len(rows), 'classes', tot)
PY

echo "== 3. React tests (src/bank-application-frontend; installs deps with corepack yarn if missing)"
FE="$ROOT/src/bank-application-frontend"
if [ ! -d "$FE/node_modules/react-scripts" ]; then
  (cd "$FE" && corepack yarn install) > "$HERE/build/react-install.log" 2>&1 || true
fi
if [ -d "$FE/node_modules/react-scripts" ]; then
  set +e
  # attempt A: the suite exactly as committed
  (cd "$FE" && CI=true timeout 600 npx react-scripts test --watchAll=false) > "$HERE/build/react-test.log" 2>&1
  rc=$?
  # attempt B: same suite plus a parity-only Jest setup file providing TextEncoder/TextDecoder/streams
  (cd "$FE" && CI=true timeout 600 npx react-scripts test --watchAll=false --setupFiles "$HERE/tools/react-jest-polyfill.js") \
    > "$HERE/build/react-test-polyfill.log" 2>&1
  rc_b=$?
  set -e
else
  echo "node_modules missing and corepack yarn install failed (see build/react-install.log)" > "$HERE/build/react-test.log"; rc=127
  cp "$HERE/build/react-test.log" "$HERE/build/react-test-polyfill.log"; rc_b=127
fi
python3 - "$FE" "$HERE/build/react-test.log" "$rc" "$HERE/build/react-test-polyfill.log" "$rc_b" "$RES/tests-react.json" <<'PY'
import glob, json, os, re, sys
fe, log, rc, logb, rcb, out = sys.argv[1], open(sys.argv[2]).read(), int(sys.argv[3]), open(sys.argv[4]).read(), int(sys.argv[5]), sys.argv[6]
def g(pat, text):
    m = re.search(pat, text)
    return m.group(1).strip() if m else None
# static check of what the committed test files import (relative imports must resolve, packages must be installed)
imports = []
for t in sorted(glob.glob(fe + '/src/**/*.test.js', recursive=True)):
    for mod in re.findall(r"from '([^']+)'", open(t).read()):
        if mod.startswith('.'):
            base = os.path.normpath(os.path.join(os.path.dirname(t), mod))
            ok = any(os.path.exists(base + e) for e in ('', '.js', '.jsx', '/index.js'))
        else:
            parts = mod.split('/')
            ok = os.path.isdir(os.path.join(fe, 'node_modules', *parts[:2 if mod.startswith('@') else 1]))
        imports.append({'test': os.path.relpath(t, fe), 'import': mod, 'resolves': ok})
json.dump({'command': 'CI=true npx react-scripts test --watchAll=false', 'exit_code': rc,
           'suites': g(r'Test Suites:\s*(.*)', log), 'tests': g(r'Tests:\s*(.*)', log),
           'first_error': g(r'(\w*Error: .*)', log) or g(r'(Cannot find module .*)', log),
           'polyfill_attempt': {'command': 'same + --setupFiles parity/tools/react-jest-polyfill.js', 'exit_code': rcb,
                                'suites': g(r'Test Suites:\s*(.*)', logb), 'tests': g(r'Tests:\s*(.*)', logb),
                                'first_error': g(r'(Cannot find module .*)', logb) or g(r'(\w*Error: .*)', logb)},
           'test_file_imports': imports, 'log_tail': log[-3000:]}, open(out, 'w'), indent=1)
print('react exit', rc, g(r'Tests:\s*(.*)', log), '| polyfill exit', rcb, g(r'(Cannot find module .*)', logb))
PY

echo "== 4. side-by-side flow parity (parity/tools/parity.py)"
CP_FILE="$HERE/build/classpath.txt"
(cd "$SVC" && mvn -B -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE" > /dev/null)
CP="$HERE/build/classes:$SVC/target/classes:$(cat "$CP_FILE")"
mkdir -p "$HERE/build/classes"
javac -proc:none -d "$HERE/build/classes" -cp "$CP" "$HERE/tools/ScreenParity.java" "$HERE/tools/ParityServer.java"
python3 "$HERE/tools/parity.py" "$CP" | tee "$HERE/build/parity.log" | sed '/ MATCH$/d'

echo "== 4b. React UI in Chromium against the migrated service (parity/tools/react_ui_smoke.py)"
python3 -c "from playwright.sync_api import sync_playwright" 2>/dev/null || pip install -q playwright
python3 -m playwright install chromium-headless-shell > "$HERE/build/playwright-install.log" 2>&1 || python3 -m playwright install chromium >> "$HERE/build/playwright-install.log" 2>&1
python3 "$HERE/tools/react_ui_smoke.py" "$CP" | tee "$HERE/build/react-ui.log"

echo "== 5. evidence + report"
python3 "$HERE/tools/report.py"
echo "report: $HERE/report/parity-report.html"
