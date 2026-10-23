#!/usr/bin/env bash
# Rebuild legacy/build when cbsarun is missing or older than any input of build.sh
# (COBOL sources, copybooks, BMS maps, stub runtime, translator scripts).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
BIN="$HERE/build/cbsarun"
if [ ! -x "$BIN" ] || [ -n "$(find "$ROOT/src/base/cobol_src" "$ROOT/src/base/cobol_copy" "$ROOT/src/base/bms_src" \
      "$HERE/runtime" "$HERE/tools" "$HERE/copy" "$HERE/build.sh" -type f ! -name '*.pyc' -newer "$BIN" -print -quit)" ]; then
  "$HERE/build.sh" >/dev/null
fi
