#!/usr/bin/env bash
# Legacy (GnuCOBOL 3.1.2 + C/SQLite stub harness) vs migrated (Java 21 / Spring Boot 3) parity, round 3 (final).
# The migrated side runs twice: H2 (default profile) and PostgreSQL (profile postgres). Rounds 1 and 2 are kept in
# parity/results/round{1,2}/ for the round-by-round history.
# Usage: parity/run-parity.sh            (writes parity/results/*.json and parity/report/parity-report.html)
#        SKIP_MVN=1 parity/run-parity.sh (reuse target/ + surefire reports from a previous mvn verify)
# Postgres: PARITY_PG_URL (default jdbc:postgresql://localhost:5432/cbsa), PARITY_PG_USER / PARITY_PG_PASSWORD (default
# cbsa / cbsa), e.g. docker run -d -e POSTGRES_DB=cbsa -e POSTGRES_USER=cbsa -e POSTGRES_PASSWORD=cbsa -p 5432:5432 postgres:16.
# The Postgres run is mandatory; SKIP_PG=1 skips it and the report then says so.
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
"$ROOT/legacy/run-all.sh" > "$HERE/build/legacy-run-all.log" 2>&1 || { tail -20 "$HERE/build/legacy-run-all.log"; exit 1; }
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

echo "== 3. React unit tests + production build (src/bank-application-frontend)"
FE="$ROOT/src/bank-application-frontend"
(cd "$FE" && corepack yarn install) > "$HERE/build/react-install.log" 2>&1 || { tail -20 "$HERE/build/react-install.log"; exit 1; }
set +e
(cd "$FE" && CI=true timeout 600 corepack yarn test --watchAll=false) > "$HERE/build/react-test.log" 2>&1
rc=$?
(cd "$FE" && GENERATE_SOURCEMAP=false timeout 900 corepack yarn build) > "$HERE/build/react-build.log" 2>&1
rc_build=$?
set -e
python3 - "$FE" "$ROOT/src/cbsa-core-service/src/main/resources/static" "$HERE/build/react-test.log" "$rc" "$HERE/build/react-build.log" "$rc_build" "$RES/tests-react.json" <<'PY'
import glob, json, os, re, sys
fe, static, log, rc, blog, rcb, out = sys.argv[1], sys.argv[2], open(sys.argv[3]).read(), int(sys.argv[4]), open(sys.argv[5]).read(), int(sys.argv[6]), sys.argv[7]
g = lambda pat, text: (re.search(pat, text).group(1).strip() if re.search(pat, text) else None)
num = lambda pat: int(g(pat, log) or 0)
built = sorted(os.path.basename(p) for p in glob.glob(fe + '/build/static/js/main.*.js'))
served = sorted(os.path.basename(p) for p in glob.glob(static + '/static/js/main.*.js'))
json.dump({'command': 'CI=true corepack yarn test --watchAll=false', 'exit_code': rc,
           'suites': g(r'Test Suites:\s*(.*)', log), 'tests': g(r'Tests:\s*(.*)', log),
           'tests_passed': num(r'Tests:.*?(\d+) passed'), 'tests_failed': num(r'Tests:.*?(\d+) failed'),
           'tests_total': num(r'Tests:.*?(\d+) total'), 'suites_total': num(r'Test Suites:.*?(\d+) total'),
           'test_files': sorted(os.path.relpath(p, fe) for p in glob.glob(fe + '/src/**/*.test.js', recursive=True)),
           'build': {'command': 'GENERATE_SOURCEMAP=false corepack yarn build', 'exit_code': rcb, 'bundle': built,
                     'served_by_core_service': served, 'bundle_matches_served': bool(built) and built == served},
           'log_tail': log[-3000:]}, open(out, 'w'), indent=1)
print('react tests exit', rc, g(r'Tests:\s*(.*)', log), '| build exit', rcb, built, 'served', served)
sys.exit(0 if rc == 0 and rcb == 0 else 1)
PY

echo "== 4. side-by-side flow parity (parity/tools/parity.py)"
CP_FILE="$HERE/build/classpath.txt"
(cd "$SVC" && mvn -B -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE" > /dev/null)
CP="$HERE/build/classes:$SVC/target/classes:$(cat "$CP_FILE")"
mkdir -p "$HERE/build/classes"
javac -proc:none -d "$HERE/build/classes" -cp "$CP" "$HERE/tools/ScreenParity.java" "$HERE/tools/ParityServer.java"
python3 -c "from playwright.sync_api import sync_playwright" 2>/dev/null || pip install -q playwright
python3 -m playwright install chromium-headless-shell > "$HERE/build/playwright-install.log" 2>&1 || python3 -m playwright install chromium >> "$HERE/build/playwright-install.log" 2>&1
DBS="h2"
[ -z "${SKIP_PG:-}" ] && DBS="h2 postgres"
rm -f "$RES/flows-postgres.json" "$RES/react-ui-postgres.json"
for db in $DBS; do
  echo "-- 4.$db flows: legacy vs migrated on $db"
  PARITY_DB=$db python3 "$HERE/tools/parity.py" "$CP" | tee "$HERE/build/parity-$db.log" | sed '/ MATCH$/d'
  echo "-- 4b.$db React UI in Chromium against the migrated service on $db (parity/tools/react_ui_smoke.py)"
  PARITY_DB=$db python3 "$HERE/tools/react_ui_smoke.py" "$CP" | tee "$HERE/build/react-ui-$db.log"
done
cp "$HERE/build/parity-h2.log" "$HERE/build/parity.log"
rm -f "$RES/tests-postgres-it.json"
if [ -z "${SKIP_PG:-}" ]; then
  echo "-- 4c. opt-in PostgreSQL integration test (PostgresProfileMainFlowsTest, CBSA_PG_IT_URL)"
  PGURL="${PARITY_PG_URL:-jdbc:postgresql://localhost:5432/cbsa}"
  PGPASSWORD="${PARITY_PG_PASSWORD:-cbsa}" python3 -c "import sys;sys.path.insert(0,'$HERE/tools');import os;os.environ['PARITY_DB']='postgres';import parity;parity.pg_recreate_schema('cbsa_it')"
  (cd "$SVC" && CBSA_PG_IT_URL="$PGURL" CBSA_PG_IT_USER="${PARITY_PG_USER:-cbsa}" CBSA_PG_IT_PASSWORD="${PARITY_PG_PASSWORD:-cbsa}" \
     CBSA_PG_IT_SCHEMA=cbsa_it mvn -B -q test -Dtest=PostgresProfileMainFlowsTest -Dsurefire.failIfNoSpecifiedTests=false) \
     > "$HERE/build/pg-it.log" 2>&1 || { tail -40 "$HERE/build/pg-it.log"; exit 1; }
  python3 - "$SVC" "$RES/tests-postgres-it.json" <<'PY'
import glob, json, sys, xml.etree.ElementTree as ET
r = ET.parse(glob.glob(sys.argv[1] + '/target/surefire-reports/TEST-*PostgresProfileMainFlowsTest.xml')[0]).getroot()
d = {'class': r.get('name'), **{k: int(r.get(k)) for k in ('tests', 'failures', 'errors', 'skipped')},
     'cases': [c.get('name') for c in r.iter('testcase')]}
json.dump(d, open(sys.argv[2], 'w'), indent=1)
print(d)
PY
fi

echo "== 5. evidence + report"
python3 "$HERE/tools/report.py"
echo "report: $HERE/report/parity-report.html"
