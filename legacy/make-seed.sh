#!/usr/bin/env bash
# Regenerate baseline/seed/seed.sql by running the legacy BANKDATA batch
# program under the harness with fixed parameters (customers 1..30 step 1,
# random seed 42), then applies legacy/cases/seed-activity.json; make_seed.py dumps the store as canonical INSERTs.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
W="$HERE/build/seed"
rm -rf "$W"; mkdir -p "$W"
[ -x "$HERE/build/cbsarun" ] || "$HERE/build.sh"
sqlite3 "$W/seed.db" < "$ROOT/baseline/seed/schema-legacy-harness.sql"
( cd "$W" && DD_VSAM="$W/customer.dat" CBSA_LIB="$HERE/build/lib" \
    CBSA_FIXED_TIME="${CBSA_FIXED_TIME:-2026-01-15T10:30:00Z}" CBSA_RANDOM_SEED="${CBSA_RANDOM_SEED:-42}" \
    "$HERE/build/cbsarun" "$W/seed.db" batch BANKDATA "1,30,1,42" - "$W/events.txt" > "$W/bankdata.log" 2>&1 )
python3 "$HERE/tools/make_seed.py" load-customers "$W/seed.db" "$W/customer.dat"
# Seed activity (MORTGAGE accounts BANKDATA never generates, PROCTRAN rows)
# is produced by running CREACC/DBCRFUN/XFRFUN under the harness.
python3 "$HERE/tools/harness.py" apply "$W/seed.db" "$HERE/cases/seed-activity.json"
python3 "$HERE/tools/make_seed.py" dump "$W/seed.db" "$ROOT/baseline/seed/seed.sql"
