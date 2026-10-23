#!/usr/bin/env bash
# Run cbsa-core-service locally on JDK 21.
#   ./run-local.sh                 -> H2 in-memory (default profile), http://localhost:8080/webui-1.0
#   ./run-local.sh postgres        -> Postgres profile (CBSA_DB_URL / CBSA_DB_USER / CBSA_DB_PASSWORD)
# Extra Spring args may follow the profile, e.g. ./run-local.sh default --cbsa.clock.fixed=2026-01-15T10:30:00Z
set -euo pipefail
cd "$(dirname "$0")"

if [[ -z "${JAVA_HOME:-}" && -d /usr/lib/jvm/java-21-openjdk-amd64 ]]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
fi
[[ -n "${JAVA_HOME:-}" ]] && export PATH="$JAVA_HOME/bin:$PATH"

PROFILE="${1:-default}"
shift || true

MVN="mvn"
[[ -x ../../mvnw ]] && ! command -v mvn >/dev/null 2>&1 && MVN="../../mvnw"

ARGS=()
[[ "$PROFILE" != "default" ]] && ARGS+=("--spring.profiles.active=$PROFILE")
[[ -n "${CBSA_FIXED_TIME:-}" ]] && ARGS+=("--cbsa.clock.fixed=$CBSA_FIXED_TIME")
[[ -n "${CBSA_RANDOM_SEED:-}" ]] && ARGS+=("--cbsa.random.seed=$CBSA_RANDOM_SEED")
ARGS+=("$@")

$MVN -q -DskipTests package
exec java -jar target/cbsa-core-service.jar "${ARGS[@]}"
