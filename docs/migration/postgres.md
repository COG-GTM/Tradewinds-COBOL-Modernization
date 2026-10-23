# Running cbsa-core-service on PostgreSQL

`cbsa-core-service` runs on H2 in-memory by default. Profile `postgres`
(`src/cbsa-core-service/src/main/resources/application-postgres.properties`) switches to PostgreSQL. It uses
the same canonical `schema.sql` and `baseline/seed/seed.sql`. Both profiles use the same SQL with no dialect switches:
`schema.sql` is portable (`CREATE TABLE IF NOT EXISTS`, legacy DB2 column names unquoted, so PostgreSQL folds them to
lower case and every query still matches).

Verified against **PostgreSQL 16.15** (`postgres:16` Docker image) with JDK 21 and Spring Boot 3.3.4.

## Configuration

| Env var | Default | Notes |
|---|---|---|
| `CBSA_DB_URL` | `jdbc:postgresql://localhost:5432/cbsa` | append `?currentSchema=<schema>` to use a non-`public` schema |
| `CBSA_DB_USER` | `cbsa` | |
| `CBSA_DB_PASSWORD` | none (required) | no default is shipped. Startup fails if it is unset |
| `PORT` | `8080` | context path `/webui-1.0` |

All other `cbsa.*` properties (`cbsa.seed.*`, `cbsa.clock.fixed`, `cbsa.random.seed`, …) come from `application.properties`
and behave the same as on H2. On startup Spring runs `schema.sql` (`spring.sql.init.mode=always`, idempotent), then
`SeedDataLoader` loads the canonical seed when `CUSTOMER` and `ACCOUNT` are empty (`cbsa.seed.mode=if-empty`). Data is
kept across restarts, so the seed is not reapplied. `cbsa.seed.mode=always` only clears `CONTROL` before inserting. To
start over, drop the schema: `DROP SCHEMA public CASCADE; CREATE SCHEMA public;`.

## 1. Start PostgreSQL 16

```bash
docker run -d --name cbsa-pg16 -e POSTGRES_DB=cbsa -e POSTGRES_USER=cbsa -e POSTGRES_PASSWORD=cbsa \
  -p 5432:5432 postgres:16
# or apt: sudo apt-get install postgresql-16 && sudo -u postgres createuser -P cbsa && sudo -u postgres createdb -O cbsa cbsa
```

## 2. Run the service on the `postgres` profile

```bash
cd src/cbsa-core-service
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH
export CBSA_DB_URL=jdbc:postgresql://localhost:5432/cbsa CBSA_DB_USER=cbsa CBSA_DB_PASSWORD=cbsa
./run-local.sh postgres
# equivalent: mvn -q -DskipTests package && java -jar target/cbsa-core-service.jar --spring.profiles.active=postgres
```

Run the jar from `src/cbsa-core-service` so `file:../../baseline/seed/seed.sql` resolves. Otherwise the classpath copy
`seed/seed.sql` is used. The log should show `The following 1 profile is active: "postgres"` and
`CBSA seed loaded from URL [file:../../baseline/seed/seed.sql]`. Smoke check:

```bash
B=http://localhost:8080/webui-1.0/banking
curl -s $B/customer/1                              # Drs Adam N Renton, credit score 397
curl -s "$B/customer/name?name=Renton&limit=10"    # 2 customers (1, 7)
curl -s $B/account/retrieveByCustomerNumber/1      # 4 accounts: ISA, SAVING, CURRENT, LOAN
curl -s "$B/customer?countOnly=true"               # {"numberOfCustomers":30}
```

The React UI is served at `http://localhost:8080/webui-1.0/` and works unchanged on this profile.

## 3. Opt-in integration test

`src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/postgres/PostgresProfileMainFlowsTest.java` starts the
whole application on a random port with `@ActiveProfiles("postgres")` and drives the main flows over HTTP against a real
PostgreSQL. It is **skipped** unless `CBSA_PG_IT_URL` is set, so `mvn verify` stays H2-only and needs no database or Docker.

| Env var | Default | |
|---|---|---|
| `CBSA_PG_IT_URL` | unset → test skipped | e.g. `jdbc:postgresql://localhost:5432/cbsa` |
| `CBSA_PG_IT_USER` | `cbsa` | |
| `CBSA_PG_IT_PASSWORD` | empty | |
| `CBSA_PG_IT_SCHEMA` | `cbsa_it` | **dropped and recreated** at the start of every run. Point it at a throwaway schema |

```bash
cd src/cbsa-core-service
CBSA_PG_IT_URL=jdbc:postgresql://localhost:5432/cbsa CBSA_PG_IT_PASSWORD=cbsa \
  mvn -B test -Dtest=PostgresProfileMainFlowsTest
# Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

Coverage (fixed clock `2026-01-15T10:30:00Z`, `cbsa.random.seed=42`):

1. **Schema + seed**: the connection is PostgreSQL. `schema.sql` creates `CUSTOMER`, `ACCOUNT`, `PROCTRAN`, `CONTROL` with
   native types (`date`, `integer`, …). The seed loads 30 customers and 76 accounts, and the `CONTROL` counters are set to 30 / 76.
2. **Reads** (INQCUST, INQACC, INQACCCU, GETSCODE, GETCOMPY): customer by number and by name, list counts, accounts of a
   customer in account-number order, an account with its `NUMERIC` balances, 404 `failCode 1`, and the INQACC
   `99999999` = last-account sentinel.
3. **Lifecycle** (CRECUST + CRDTAGY1-5, UPDCUST, CREACC, UPDACC, DBCRFUN, XFRFUN, DELACC, DELCUS): create a customer
   (credit check), update it, open an account, update it, credit, debit, transfer to seeded account 1, and check the balances
   and the PROCTRAN rows (`OCC/OCA/CRE/DEB/TFR`). Then create-account validation (`failCode A`), delete the account, and delete
   the customer, which also deletes its remaining accounts.
4. **Concurrency**: 8 parallel customer creates and 8 parallel account creates get distinct numbers. The `CONTROL` counters
   advance by exactly 8, so the atomic `UPDATE CONTROL … + ?` increment holds under PostgreSQL row locking.

## Findings

No PostgreSQL-only defects were found. `application-postgres.properties`, `schema.sql`, the seed script, the `JdbcClient`
repositories and `CounterService` all ran unchanged on PostgreSQL 16, so no Java or schema changes were needed. Points to note:

- The test isolates itself with `?currentSchema=`. The service accepts the same parameter in `CBSA_DB_URL` if several
  environments share one database.
- `information_schema` and JDBC metadata return lower-case identifiers on PostgreSQL. Application SQL is unaffected because
  identifiers are unquoted. Only ad-hoc `queryForMap` callers see lower-case keys.
