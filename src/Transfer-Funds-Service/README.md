# Transfer Funds Service (modernized XFRFUN)

Java / Spring Boot modernization of the CBSA `XFRFUN` CICS/COBOL program, which
transfers funds between two accounts at the same bank, records the transaction in
`PROCTRAN`, and returns the updated balances.

- Source program: `src/base/cobol_src/XFRFUN.cbl`
- Full migration write-up: [`doc/XFRFUN_Modernization_Guide.md`](../../doc/XFRFUN_Modernization_Guide.md)

## Run

```bash
mvn spring-boot:run
```

Starts on `:8080` backed by an in-memory H2 database seeded with two accounts
(`00000001`, `00000002`, sort code `987654`) from `src/main/resources/data.sql`.

## API

`POST /xfrfun/transfer`

```json
{ "fromAccountNumber": "1", "toAccountNumber": "2", "amount": 100.00 }
```

Response carries the XFRFUN commarea outputs: `success` / `commSuccess`,
`commFailCode`, and the four updated balances. Fail codes mirror the COBOL
`COMM-FAIL-CODE` (`1` FROM not found, `2` TO not found, `3` datastore error,
`4` non-positive amount); a same-account transfer returns HTTP 422 (`SAME`).

## Test

```bash
mvn test
```

## Layout

| Package | Responsibility |
| --- | --- |
| `domain` | JPA entities for `ACCOUNT` and `PROCTRAN` (`Account`, `AccountKey`, `ProcessedTransaction`) |
| `repository` | Spring Data repositories (incl. pessimistic-lock `findByKeyForUpdate`) |
| `dto` | `TransferRequest` / `TransferResponse` (the commarea) |
| `service` | `TransferFundsService` — the XFRFUN business logic; `TransferFailureReason` |
| `controller` | REST endpoint + exception handling |
| `exception` | `SameAccountTransferException`, `TransferProcessingException` |
