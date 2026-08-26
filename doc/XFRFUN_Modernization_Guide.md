# XFRFUN (Transfer Funds) COBOL-to-Java Modernization Guide

This document records the analysis and modernization of the **XFRFUN** program
(`src/base/cobol_src/XFRFUN.cbl`, 1,924 lines) into the Spring Boot service at
`src/transfer-funds-service/`. It is intended to be reusable as a worked
example for migrating the remaining CBSA COBOL programs.

## 1. What XFRFUN does

XFRFUN is the CICS COMMAREA-linked program behind the "Transfer Funds"
business capability. It is invoked by the 3270 BMS front-end (`BNK1TFN`) and by
z/OS Connect. Given a FROM account key (sort code + account number), a TO
account key, and an amount, it:

1. Rejects non-positive amounts (`COMM-AMT <= 0` → fail code `4`).
2. Abends (`SAME`) if FROM and TO are the same sort code + account number.
3. Reads and updates both rows on the Db2 `ACCOUNT` table, **always updating
   the lower account number first** to reduce deadlock exposure. Balances are
   changed on both `ACCOUNT_AVAILABLE_BALANCE` and `ACCOUNT_ACTUAL_BALANCE`.
   **No overdraft-limit check is performed** (explicit in the program header).
4. On success, inserts a `TFR` row into the Db2 `PROCTRAN` audit table,
   recorded against the FROM account with description
   `'TRANSFER' + TO sort code + TO account number` (40 bytes).
5. Returns the four updated balances and success flag in the COMMAREA.
6. On any partial failure, issues `EXEC CICS SYNCPOINT ROLLBACK` so the unit
   of work is atomic; unrecoverable paths link to `ABNDPROC` and abend.
7. Retries Db2 deadlocks (`SQLCODE -911`, `SQLERRD(3) = 13172872`) up to 5
   times, sleeping 1 second between attempts (`DB2-DEADLOCK-RETRY < 6`).
8. Detects "storm drain" conditions (`SQLCODE 923` = Db2 connection lost, and
   VSAM RLS abends `AFCR`/`AFCS`/`AFCT`) for CPSM workload management.

### COMMAREA interface (copybook `src/base/cobol_copy/XFRFUN.cpy`)

| COBOL field      | PIC            | Direction | Java equivalent                        |
|------------------|----------------|-----------|----------------------------------------|
| `COMM-FACCNO`    | `9(8)`         | in        | `TransferRequest.fromAccountNumber`    |
| `COMM-FSCODE`    | `9(6)`         | in*       | `TransferRequest.fromSortCode`         |
| `COMM-TACCNO`    | `9(8)`         | in        | `TransferRequest.toAccountNumber`      |
| `COMM-TSCODE`    | `9(6)`         | in*       | `TransferRequest.toSortCode`           |
| `COMM-AMT`       | `S9(10)V99`    | in        | `TransferRequest.amount` (`BigDecimal`)|
| `COMM-FAVBAL`    | `S9(10)V99`    | out       | `TransferResponse.fromAvailableBalance`|
| `COMM-FACTBAL`   | `S9(10)V99`    | out       | `TransferResponse.fromActualBalance`   |
| `COMM-TAVBAL`    | `S9(10)V99`    | out       | `TransferResponse.toAvailableBalance`  |
| `COMM-TACTBAL`   | `S9(10)V99`    | out       | `TransferResponse.toActualBalance`     |
| `COMM-FAIL-CODE` | `X`            | out       | `TransferResponse.failCode`            |
| `COMM-SUCCESS`   | `X` (`Y`/`N`)  | out       | `TransferResponse.success` (boolean)   |

\* Note: XFRFUN overwrites both incoming sort codes with the branch `SORTCODE`
copybook value (`MOVE SORTCODE TO COMM-FSCODE COMM-TSCODE`), so in practice all
transfers are intra-branch. The Java service accepts the sort codes as given;
deployments that need the legacy behavior can pin both to the branch sort code
at the controller layer.

### Fail codes (`COMM-FAIL-CODE`)

| Code | Meaning                                             | COBOL source                          |
|------|-----------------------------------------------------|---------------------------------------|
| `1`  | FROM account not found (`SQLCODE +100`)             | `UPDATE-ACCOUNT-DB2-FROM`             |
| `2`  | TO account not found (`SQLCODE +100`)               | `UPDATE-ACCOUNT-DB2-TO`               |
| `3`  | Datastore error on SELECT/UPDATE                    | both update sections                  |
| `4`  | Non-positive transfer amount                        | `PREMIERE` section                    |

## 2. Target architecture

```
POST /api/v1/transfers                (TransferFundsController)
        │
        ▼
TransferFundsService.transfer()       @Transactional + @Retryable(deadlock)
        │
        ├── AccountRepository.findForUpdate()   SELECT ... FOR UPDATE (x2,
        │                                       ordered by account number)
        ├── Account.debit()/credit()            balance arithmetic
        └── ProcessedTransactionRepository.save()  PROCTRAN 'TFR' audit row
```

| COBOL construct                          | Java construct                                        |
|------------------------------------------|-------------------------------------------------------|
| CICS `LINK` + COMMAREA                    | REST `POST /api/v1/transfers` with JSON body          |
| `EXEC SQL SELECT/UPDATE` on `ACCOUNT`     | Spring Data JPA + `PESSIMISTIC_WRITE` lock            |
| `EXEC CICS SYNCPOINT ROLLBACK`            | `@Transactional` (rollback on exception)              |
| Deadlock retry loop (`GO TO UPDATE-...`)  | `@Retryable(CannotAcquireLockException, maxAttempts=6, backoff=1s)` |
| `EXEC CICS ABEND` + `ABNDPROC` link       | Exception → HTTP 500 with generic message; details logged |
| `EXEC CICS ASKTIME/FORMATTIME`            | `java.time.LocalDateTime`                             |
| Storm-drain SQLCODE detection             | Not applicable (no CPSM); connection failures surface as exceptions handled by the platform |
| `EIBTASKN` as PROCTRAN reference          | Generated 12-digit reference                          |

## 3. Behavior deltas (deliberate)

1. **Same-account transfer**: COBOL abends the whole transaction with code
   `SAME`. The service returns a structured failure (`failCode: "SAME"`,
   HTTP 422) instead — abending a request thread is not meaningful off-host.
2. **Sort code override**: see note above; the service honors caller-supplied
   sort codes rather than forcing the branch `SORTCODE`.
3. **PROCTRAN reference**: `EIBTASKN` (CICS task number) does not exist off
   host; a time-derived 12-digit reference is generated instead.
4. **Storm drain**: CPSM workload signaling has no equivalent; Db2 connection
   loss (SQLCODE 923 class errors) becomes an ordinary exception → HTTP 500,
   suitable for load-balancer health-check based drain instead.
5. **Input validation** is stricter: digits-only whitelist patterns on account
   numbers/sort codes (rejected with HTTP 400 and a generic message), per
   secure-coding requirements. COBOL accepted "data in any format".

Everything else — ordering of updates, absence of overdraft checks, atomic
account+audit unit of work, deadlock retry count and delay, fail-code
semantics, PROCTRAN record layout — is preserved.

## 4. Verification

`src/transfer-funds-service` builds with `./mvnw verify`. Tests
(`TransferFundsServiceTest`) cover: successful transfer with balance and
PROCTRAN assertions, fail codes 1/2/4, same-account rejection, absence of
overdraft checking, and COBOL-style zero-padding of short numeric inputs.

## 5. Reusable migration checklist (for the remaining programs)

1. Read the program header comment — CBSA programs document intent there.
2. Extract the COMMAREA copybook and map every field to a request/response DTO,
   preserving PIC clause semantics (`S9(10)V99` → `BigDecimal`, zero-padding).
3. Trace every `EXEC SQL`/`EXEC CICS` statement; classify as data access,
   transaction control, time, abend handling, or terminal I/O.
4. Preserve business rules exactly (including "surprising" ones like no
   overdraft check) and record any deliberate deltas in a table like §3.
5. Map SYNCPOINT boundaries to `@Transactional` methods; map retry loops to
   `@Retryable`; map abends to exceptions with generic client messages.
6. Reproduce audit (PROCTRAN) writes inside the same transaction.
7. Write tests asserting the COMMAREA-level contract (fail codes, balances),
   not implementation details.
