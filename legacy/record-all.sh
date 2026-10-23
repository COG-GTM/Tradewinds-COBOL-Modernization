#!/usr/bin/env bash
# Regenerate legacy/cases/*.json and re-record every baseline transcript.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
[ -x "$HERE/build/cbsarun" ] || "$HERE/build.sh" >/dev/null
python3 "$HERE/cases/gen_cases.py"
rm -rf "$ROOT/baseline/transcripts"
for c in business screens; do
  python3 "$HERE/tools/harness.py" record "$HERE/cases/$c.json" "$ROOT/baseline/transcripts"
done
