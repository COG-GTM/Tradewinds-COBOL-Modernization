# Transfer Funds Service

Java/Spring Boot modernization of the CBSA **XFRFUN** COBOL/CICS program
(`src/base/cobol_src/XFRFUN.cbl`). See
[doc/XFRFUN_Modernization_Guide.md](../../doc/XFRFUN_Modernization_Guide.md)
for the full analysis, COMMAREA mapping, and behavior deltas.

## Run locally

```bash
./mvnw spring-boot:run
```

Uses an in-memory H2 database seeded with two accounts (sort code `987654`,
accounts `00000001` and `00000002`). Point `spring.datasource.*` at Db2 for
z/OS to run against the real CBSA schema.

## API

`POST /api/v1/transfers`

```json
{
  "fromSortCode": "987654",
  "fromAccountNumber": "00000001",
  "toSortCode": "987654",
  "toAccountNumber": "00000002",
  "amount": 100.00
}
```

Success (HTTP 200):

```json
{
  "success": true,
  "failCode": null,
  "fromAvailableBalance": 900.00,
  "fromActualBalance": 900.00,
  "toAvailableBalance": 600.00,
  "toActualBalance": 600.00
}
```

Failure (HTTP 422) carries the legacy `COMM-FAIL-CODE` value:
`"1"` FROM account not found, `"2"` TO account not found, `"3"` datastore
error, `"4"` non-positive amount, `"SAME"` same-account transfer.

## Test

```bash
./mvnw test
```
