#!/usr/bin/env bash
# Build the Carbon React UI and copy it into cbsa-core-service so Spring Boot serves it at /webui-1.0/.
#   ./updateCoreService.sh            -> yarn install + yarn build, then copy build/ into static/
#   SKIP_BUILD=1 ./updateCoreService.sh -> copy an existing build/ only
set -euo pipefail
cd "$(dirname "$0")"

TARGET=../cbsa-core-service/src/main/resources/static

if [[ "${SKIP_BUILD:-}" != "1" ]]; then
  export COREPACK_ENABLE_DOWNLOAD_PROMPT=0
  YARN=(yarn)
  command -v corepack >/dev/null 2>&1 && YARN=(corepack yarn)
  "${YARN[@]}" install
  GENERATE_SOURCEMAP=false "${YARN[@]}" build
fi

[[ -f build/index.html ]] || { echo "build/index.html missing; run yarn build first" >&2; exit 1; }

find "$TARGET" -mindepth 1 ! -name .gitattributes -delete
cp -R build/. "$TARGET/"
echo "Copied $(find "$TARGET" -type f | wc -l) files into $TARGET"
