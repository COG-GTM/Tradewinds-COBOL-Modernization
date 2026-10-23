#!/usr/bin/env bash
# Replay every transcript under baseline/transcripts (each from a fresh seed)
# and write baseline/results/legacy-results.json. Exit status 0 = all match.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
"$HERE/ensure-build.sh"
mkdir -p "$ROOT/baseline/results"
exec python3 "$HERE/tools/harness.py" replay-all "$ROOT/baseline/transcripts" "$ROOT/baseline/results/legacy-results.json"
