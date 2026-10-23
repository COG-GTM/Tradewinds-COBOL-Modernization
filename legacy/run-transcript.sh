#!/usr/bin/env bash
# Replay one baseline transcript from a fresh copy of baseline/seed/seed.sql
# and print MATCH or a field-level diff. Exit status 0 = match.
#   legacy/run-transcript.sh baseline/transcripts/XFRFUN/ok.json
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
[ $# -eq 1 ] || { echo "usage: $0 <transcript.json>" >&2; exit 2; }
"$HERE/ensure-build.sh"
exec python3 "$HERE/tools/harness.py" replay "$1"
