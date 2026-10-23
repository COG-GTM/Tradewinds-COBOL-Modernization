#!/usr/bin/env bash
# Legacy (GnuCOBOL 3.1.2 + C/SQLite stub harness) vs migrated (Java 21 / Spring Boot 3 / H2) parity, round 1.
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

echo "== 3. React tests (src/bank-application-frontend, if node_modules present)"
FE="$ROOT/src/bank-application-frontend"
if [ -d "$FE/node_modules/react-scripts" ]; then
  set +e
  (cd "$FE" && CI=true timeout 600 npx react-scripts test --watchAll=false) > "$HERE/build/react-test.log" 2>&1
  rc=$?
  set -e
else
  echo "node_modules missing (run: cd $FE && corepack yarn install)" > "$HERE/build/react-test.log"; rc=127
fi
python3 - "$HERE/build/react-test.log" "$rc" "$RES/tests-react.json" <<'PY'
import json, re, sys
log, rc, out = open(sys.argv[1]).read(), int(sys.argv[2]), sys.argv[3]
g = lambda pat: (re.search(pat, log) or [None, None])[1]
json.dump({'command': 'CI=true npx react-scripts test --watchAll=false', 'exit_code': rc,
           'suites': g(r'Test Suites:\s*(.*)'), 'tests': g(r'Tests:\s*(.*)'),
           'first_error': g(r'(\w*Error: .*)'), 'log_tail': log[-3000:]}, open(out, 'w'), indent=1)
print('react exit', rc, g(r'Tests:\s*(.*)'), g(r'(\w*Error: .*)'))
PY

echo "== 4. side-by-side flow parity (parity/tools/parity.py)"
CP_FILE="$HERE/build/classpath.txt"
(cd "$SVC" && mvn -B -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE" > /dev/null)
CP="$HERE/build/classes:$SVC/target/classes:$(cat "$CP_FILE")"
mkdir -p "$HERE/build/classes"
javac -proc:none -d "$HERE/build/classes" -cp "$CP" "$HERE/tools/ScreenParity.java" "$HERE/tools/ParityServer.java"
python3 "$HERE/tools/parity.py" "$CP" | tee "$HERE/build/parity.log" | grep -v ' MATCH$' || true

echo "== 5. evidence + report"
python3 "$HERE/tools/report.py"
echo "report: $HERE/report/parity-report.html"
