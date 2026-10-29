#!/usr/bin/env bash
# A/B test: DB2 baseline (A) vs PostgreSQL migration (B).
#
# A = BASE_REF (default origin/main) web tier, original DB2 SQL, against DB2 LUW.
#     Only the runtime adapter is applied so it can run outside CICS:
#     BankRuntime/HBankDataAccess from this branch, and Task.getTask()
#     rollback/task-number calls redirected to BankRuntime. SQL is untouched.
# B = this branch's web tier against PostgreSQL, schema from
#     etc/install/base/postgres.
# Both run AbScenario; then DB2's final data is migrated to a fresh
# PostgreSQL database with MigrateDb2ToPostgres and checksummed.
# Output: $WORK/a.json, $WORK/b.json, $WORK/migration.txt, $WORK/ab-report.html
set -euo pipefail

ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
WORK=${WORK:-$HOME/ab-work}
BASE_REF=${BASE_REF:-origin/main}
DB2_IMAGE=${DB2_IMAGE:-icr.io/db2_community/db2:11.5.9.0}
PG_IMAGE=${PG_IMAGE:-postgres:16}
DB2_PASSWORD=${DB2_PASSWORD:-db2pass}
PG_PASSWORD=${PG_PASSWORD:-pgpass}
CBSA_PASSWORD=${CBSA_PASSWORD:-cbsapass}
M2=${M2:-$HOME/.m2/repository}
PG_PORT=${PG_PORT:-55432}
mkdir -p "$WORK"

echo "== Build B (this branch) and resolve classpath"
mvn -q -f "$ROOT/src/webui/pom.xml" test-compile
mvn -q -f "$ROOT/src/webui/pom.xml" dependency:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile="$WORK/cp.txt" >/dev/null
mvn -q dependency:get -Dartifact=com.ibm.db2:jcc:11.5.9.0 >/dev/null
DEPS="$(cat "$WORK/cp.txt"):$M2/com/ibm/db2/jcc/11.5.9.0/jcc-11.5.9.0.jar"
SHIMS="$ROOT/src/webui/target/test-classes"

echo "== Build A ($BASE_REF + runtime adapter only)"
rm -rf "$WORK/baseline" "$WORK/a-classes" "$WORK/b-scenario" "$WORK/a-scenario"
git -C "$ROOT" archive "$BASE_REF" src/webui/src/main/java | tar -x -C "$WORK" --one-top-level=baseline
API="$WORK/baseline/src/webui/src/main/java/com/ibm/cics/cip/bankliberty/api/json"
cp "$ROOT/src/webui/src/main/java/com/ibm/cics/cip/bankliberty/api/json/"{BankRuntime,HBankDataAccess}.java "$API/"
grep -rl "Task.getTask()" "$WORK/baseline" | xargs sed -i \
  -e 's/Task\.getTask()\.rollback()/BankRuntime.rollback()/g' \
  -e 's/Task\.getTask()\.getTaskNumber()/BankRuntime.taskNumber()/g'
sed -i 's/^package com.ibm.cics.cip.bankliberty.web.db2;/&\nimport com.ibm.cics.cip.bankliberty.api.json.BankRuntime;/' \
  "$WORK"/baseline/src/webui/src/main/java/com/ibm/cics/cip/bankliberty/web/db2/*.java
javac -nowarn -d "$WORK/a-classes" -cp "$DEPS" $(find "$WORK/baseline" -name '*.java') 2>&1 | grep -v "^Note:" || true
for side in a b; do
  classes=$([ $side = a ] && echo "$WORK/a-classes" || echo "$ROOT/src/webui/target/classes")
  javac -nowarn -d "$WORK/$side-scenario" -cp "$classes:$SHIMS:$DEPS" \
    "$ROOT/etc/ab-test/src/com/ibm/cics/cip/bankliberty/api/json/AbScenario.java"
done

echo "== Start databases"
if ! docker ps --format '{{.Names}}' | grep -qx db2a; then
  docker rm -f db2a >/dev/null 2>&1 || true
  docker run -d --name db2a --privileged -p 50000:50000 -e LICENSE=accept \
    -e DB2INST1_PASSWORD="$DB2_PASSWORD" -e DBNAME=CBSA -e ARCHIVE_LOGS=false \
    -e AUTOCONFIG=false "$DB2_IMAGE" >/dev/null
fi
docker rm -f pgb >/dev/null 2>&1 || true
docker run -d --name pgb -p "$PG_PORT":5432 -e POSTGRES_PASSWORD="$PG_PASSWORD" "$PG_IMAGE" >/dev/null
until docker logs db2a 2>&1 | grep "Setup has completed" >/dev/null; do sleep 10; echo "  waiting for DB2..."; done
until docker exec pgb pg_isready -U postgres -q; do sleep 1; done; sleep 2

echo "== Create schemas (DB2: INSTDB2 tables; PostgreSQL: etc/install/base/postgres)"
{ echo "CONNECT TO CBSA;"; cat "$ROOT/etc/ab-test/db2_luw_tables.sql"; } > "$WORK/db2_tables.sql"
docker cp "$WORK/db2_tables.sql" db2a:/tmp/tables.sql
docker exec db2a chmod 644 /tmp/tables.sql
docker exec db2a su - db2inst1 -c "db2 -tf /tmp/tables.sql" | grep -E "SQL[0-9]+N" | grep -v SQL0204N || true
docker exec -i pgb psql -q -U postgres -v cbsa_password="$CBSA_PASSWORD" \
  < "$ROOT/etc/install/base/postgres/00_create_database.sql"
docker exec -i pgb psql -q -U postgres -d postgres -c "CREATE DATABASE cbsa_migrated OWNER cbsa"
for db in cbsa cbsa_migrated; do
  docker exec -i pgb psql -q -v ON_ERROR_STOP=1 -U cbsa -d $db \
    < "$ROOT/etc/install/base/postgres/01_create_tables.sql"
done

echo "== Run scenario A (DB2) and B (PostgreSQL)"
java -cp "$WORK/a-scenario:$WORK/a-classes:$SHIMS:$DEPS" \
  -DCBSA_JDBC_URL=jdbc:db2://localhost:50000/CBSA -DCBSA_JDBC_USER=db2inst1 \
  -DCBSA_JDBC_PASSWORD="$DB2_PASSWORD" \
  com.ibm.cics.cip.bankliberty.api.json.AbScenario "$WORK/a.json"
java -cp "$WORK/b-scenario:$ROOT/src/webui/target/classes:$SHIMS:$DEPS" \
  -DCBSA_JDBC_URL=jdbc:postgresql://localhost:$PG_PORT/cbsa -DCBSA_JDBC_USER=cbsa \
  -DCBSA_JDBC_PASSWORD="$CBSA_PASSWORD" \
  com.ibm.cics.cip.bankliberty.api.json.AbScenario "$WORK/b.json"

echo "== Migrate DB2 data -> PostgreSQL (cbsa_migrated) and verify"
set +e
DB2_JDBC_URL=jdbc:db2://localhost:50000/CBSA DB2_USER=db2inst1 DB2_PASSWORD="$DB2_PASSWORD" \
PG_JDBC_URL=jdbc:postgresql://localhost:$PG_PORT/cbsa_migrated PG_USER=cbsa PG_PASSWORD="$CBSA_PASSWORD" \
  java -cp "$DEPS" "$ROOT/etc/install/base/postgres/migrate/MigrateDb2ToPostgres.java" | tee "$WORK/migration.txt"
set -e

python3 "$ROOT/etc/ab-test/report.py" "$WORK/a.json" "$WORK/b.json" "$WORK/migration.txt" "$WORK/ab-report.html"
