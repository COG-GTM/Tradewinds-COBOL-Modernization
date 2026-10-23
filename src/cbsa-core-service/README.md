# cbsa-core-service

Java 21 / Spring Boot 3.3 replacement for the CBSA COBOL/CICS business core (`src/base`).
Standalone Maven project (deliberately not a module of the root `pom.xml`).

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -q verify                 # build + tests
./run-local.sh                # H2 in-memory, http://localhost:8080/webui-1.0
./run-local.sh postgres       # CBSA_DB_URL / CBSA_DB_USER / CBSA_DB_PASSWORD
CBSA_FIXED_TIME=2026-01-15T10:30:00Z CBSA_RANDOM_SEED=42 ./run-local.sh   # deterministic
```

| Property | Default | Purpose |
|---|---|---|
| `cbsa.seed.location` | `file:../../baseline/seed/seed.sql,classpath:seed/seed.sql` | first existing seed script is loaded after `schema.sql` (missing file tolerated) |
| `cbsa.seed.mode` | `if-empty` | `if-empty` / `always` / `never` |
| `cbsa.clock.fixed` | blank | ISO-8601 instant or local date-time; fixes the `java.time.Clock` bean |
| `cbsa.clock.zone` | `UTC` | clock zone |
| `cbsa.random.seed` | blank | seeds `CbsaRandom` (CBSA-LCG) streams |
| `cbsa.sortcode` / `cbsa.company-name` | `987654` / `CICS Bank Sample Application` | GETSCODE / GETCOMPY |
| `cbsa.credit.simulate-delay` | `false` | honour CRDTAGY random delays |

Layout: `com.ibm.cics.cip.bank.core.common` (shared: domain, repositories, ProctranWriter, CounterService, Clock,
CbsaRandom, error handling, seed loader, bank identity) and one package per ticket — see
[`docs/migration/program-map.md`](../../docs/migration/program-map.md), [`api-contract.md`](../../docs/migration/api-contract.md)
and [`tickets.md`](../../docs/migration/tickets.md).
