# trade-desk-java

Spring Boot 3 / Java 17 port of `src/base/cobol_src/DBCRFUN.cbl` (debit/credit funds).

`POST /api/dbcrfun` takes the PAYDBCR COMMAREA fields (`src/base/cobol_copy/PAYDBCR.cpy`) as JSON and returns `success`, `failCode`, `avBal`, `actBal`.

| failCode | Meaning |
|---|---|
| `0` | Success (`success` = `Y`) |
| `1` | Account not found |
| `2` | ACCOUNT select or update failed |
| `3` | Payment debit (faciltype 496) with insufficient available balance |
| `4` | Payment (faciltype 496) on a `HOSPITAL` or `TENDER` account |
| `02` | PROCTRAN insert failed; the account update is rolled back |

## Run

```
mvn -B test
# Db2: DB2_URL, DB2_USER, DB2_PASSWORD, DB2_SCHEMA (default IBMUSER), DBCRFUN_SORTCODE (default 987654)
mvn spring-boot:run
# Local H2 with seed accounts 00000001 (CURRENT), 00000002 (HOSPITAL), 00000003 (TENDER)
mvn spring-boot:test-run -Dspring-boot.run.profiles=local
```

```
curl -X POST localhost:8080/api/dbcrfun -H 'Content-Type: application/json' -d \
  '{"accno":"00000001","amt":-10.00,"sortc":"987654","origin":{"applid":"CBSAAPPL","userid":"TELLER01","facilityName":"TERM0001","netwrkId":"NET1","faciltype":0}}'
```
