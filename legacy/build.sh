#!/usr/bin/env bash
# Build the CBSA legacy harness: translate COBOL, generate BMS copybooks,
# compile every program to a GnuCOBOL module and build the driver.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
B="$HERE/build"
mkdir -p "$B/src" "$B/lib" "$B/maps" "$B/log"
python3 "$HERE/tools/bms2cpy.py" "$B/maps" "$ROOT"/src/base/bms_src/*.bms >/dev/null
python3 "$HERE/tools/cbsa_pp.py" "$B/src" "$ROOT"/src/base/cobol_src/*.cbl >/dev/null || exit 1
cp "$B"/src/*.meta "$B/lib/"
ok=(); bad=()
for f in "$B"/src/*_pp.cob; do
  n="$(basename "$f" _pp.cob)"
  if (cd "$B/src" && cobc -m -std=ibm -fnot-reserved=TIMESTAMP -I "$HERE/copy" -I "$B/maps" \
        -I "$ROOT/src/base/cobol_copy" -o "$B/lib/$n.so" "$f") >"$B/log/$n.log" 2>&1; then
    ok+=("$n")
  else
    bad+=("$n")
  fi
done
gcc -O1 -g -Wall -rdynamic -I/usr/include -o "$B/cbsarun" "$HERE/runtime/cbsastub.c" \
    $(cob-config --cflags) $(cob-config --libs) -lsqlite3 -ldl >"$B/log/cbsarun.log" 2>&1 \
    || { echo "runtime build failed"; cat "$B/log/cbsarun.log"; exit 1; }
printf '%s\n' "${ok[@]}" > "$B/compiled.txt"
echo "compiled: ${#ok[@]} (${ok[*]})"
[ ${#bad[@]} -eq 0 ] || { echo "FAILED: ${bad[*]}"; exit 1; }
