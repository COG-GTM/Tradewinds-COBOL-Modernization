# XFRFUN (Transfer Funds) — COBOL-to-Java Modernization Guide

This guide documents the modernization of the **Transfer Funds** business
capability of the CICS Banking Sample Application (CBSA) from its original
CICS/COBOL/Db2 implementation (`XFRFUN`) to a Java / Spring Boot service. It is
written to be used by a migration team: it inventories the source artifacts,
explains the business logic, gives a field-by-field mapping, records the
construct-by-construct translation decisions, and lists the deliberate
deviations and follow-up items.

---

## 1. Capability summary

`XFRFUN` transfers a monetary amount between two accounts held at the same bank.
Given a FROM account, a TO account and an amount, it:

1. debits the FROM account,
2. credits the TO account,
3. records the transaction in the `PROCTRAN` (processed transaction) audit log,
   and
4. returns the updated available and actual balances for both accounts.

All updates happen inside a single unit of work: if any step fails, every change
is backed out so the two accounts can never be left inconsistent.

---

## 2. Source artifact inventory

| Artifact | Path | Role |
| --- | --- | --- |
| Program | `src/base/cobol_src/XFRFUN.cbl` | The transfer-funds program (1,924 lines). |
| Commarea copybook | `src/base/cobol_copy/XFRFUN.cpy` | Input/output parameter layout (`DFHCOMMAREA`). |
| Account copybook | `src/base/cobol_copy/ACCOUNT.cpy` | `ACCOUNT` record layout. |
| Proctran copybook | `src/base/cobol_copy/PROCTRAN.cpy` | `PROCTRAN` record layout, incl. transaction-type and description redefines. |
| Sort code copybook | `src/base/cobol_copy/SORTCODE.cpy` | Bank sort code constant (`987654`). |
| Abend info copybook | `src/base/cobol_copy/ABNDINFO.cpy` | Standard abend diagnostic record. |
| Build / link JCL | `etc/install/base/buildjcl/XFRFUN.jcl`, `etc/install/base/linkeditjcl/XFRFUN.lked` | Mainframe build artifacts (not migrated). |

The modernized code lives under `src/Transfer-Funds-Service/` (Maven artifact
`com.ibm.cics.cip.bank:transferfunds`).

---

## 3. Business logic flow

COBOL section references are shown in `monospace`.

1. **Entry** (`PREMIERE`, label `A010`)
   - Both sort codes are forced to the bank's own `SORTCODE` constant
     (`MOVE SORTCODE TO COMM-FSCODE COMM-TSCODE`). Transfers are therefore always
     intra-bank.
   - If `COMM-AMT <= 0`, set `COMM-SUCCESS = 'N'`, `COMM-FAIL-CODE = '4'` and
     return.
2. **Orchestration** (`UPDATE-ACCOUNT-DB2`, label `UAD010`)
   - If FROM and TO are the same account, link to the abend handler and
     `EXEC CICS ABEND ABCODE('SAME')`.
   - Otherwise update the two accounts in **ascending account-number order**
     (`IF COMM-FACCNO < COMM-TACCNO` updates FROM first, else TO first). This
     ordering exists purely to avoid Db2 deadlocks between concurrent,
     opposite-direction transfers; the business result is identical either way.
3. **Debit FROM** (`UPDATE-ACCOUNT-DB2-FROM`, label `UADF010`)
   - `SELECT` the FROM row. `SQLCODE +100` → `COMM-FAIL-CODE = '1'`; other
     non-zero `SQLCODE` → `'3'`.
   - Subtract `COMM-AMT` from both available and actual balance, then `UPDATE`.
   - On success store `COMM-FAVBAL` / `COMM-FACTBAL`.
4. **Credit TO** (`UPDATE-ACCOUNT-DB2-TO`, label `UADT010`)
   - `SELECT` the TO row. `SQLCODE +100` → `COMM-FAIL-CODE = '2'` and
     `SYNCPOINT ROLLBACK`; other non-zero `SQLCODE` → `'3'`.
   - Add `COMM-AMT` to both balances, then `UPDATE`.
   - On success store `COMM-TAVBAL` / `COMM-TACTBAL`.
5. **Audit** (`WRITE-TO-PROCTRAN` → `WRITE-TO-PROCTRAN-DB2`, label `WTPD010`)
   - Insert one `PROCTRAN` row keyed on the **FROM** account, with type `TFR`,
     description `'TRANSFER'` + TO sort code + TO account, and the transfer
     amount. If the insert fails, abend `WPCD` (the accounts were already
     updated, so the unit of work is rolled back to preserve integrity).
6. **Exit** (`GET-ME-OUT-OF-HERE`) — `EXEC CICS RETURN`.

### Failure / fail-code outcomes

| `COMM-FAIL-CODE` | Condition | COBOL behaviour |
| --- | --- | --- |
| `'1'` | FROM account not found | Roll back, return `success = N`. |
| `'2'` | TO account not found | Roll back, return `success = N`. |
| `'3'` | Any other Db2 error on SELECT/UPDATE | Abend (with `-911` deadlock retry, up to 6 attempts). |
| `'4'` | Amount ≤ 0 | Return `success = N` (no datastore access). |
| same-account | FROM key == TO key | Abend `SAME`. |

---

## 4. Commarea field mapping (`XFRFUN.cpy`)

| COBOL field | PIC | Direction | Java mapping |
| --- | --- | --- | --- |
| `COMM-FACCNO` | `9(8)` | in | `TransferRequest.fromAccountNumber` |
| `COMM-FSCODE` | `9(6)` | in (overwritten) | supplied by `cbsa.bank.sort-code` config |
| `COMM-TACCNO` | `9(8)` | in | `TransferRequest.toAccountNumber` |
| `COMM-TSCODE` | `9(6)` | in (overwritten) | supplied by `cbsa.bank.sort-code` config |
| `COMM-AMT` | `S9(10)V99` | in | `TransferRequest.amount` (`BigDecimal`) |
| `COMM-FAVBAL` | `S9(10)V99` | out | `TransferResponse.fromAvailableBalance` |
| `COMM-FACTBAL` | `S9(10)V99` | out | `TransferResponse.fromActualBalance` |
| `COMM-TAVBAL` | `S9(10)V99` | out | `TransferResponse.toAvailableBalance` |
| `COMM-TACTBAL` | `S9(10)V99` | out | `TransferResponse.toActualBalance` |
| `COMM-FAIL-CODE` | `X` | out | `TransferResponse.commFailCode` (+ `failureReason` enum) |
| `COMM-SUCCESS` | `X` | out | `TransferResponse.commSuccess` (+ `success` boolean) |

`PIC S9(10)V99` (packed decimal, scale 2) maps to `BigDecimal`. **Do not use
`float`/`double` for money** — see §7.

---

## 5. Datastore mapping

### ACCOUNT (`ACCOUNT.cpy`) → `Account` entity

Composite key (`ACCOUNT_SORTCODE`, `ACCOUNT_NUMBER`) → `AccountKey` `@EmbeddedId`.
Monetary columns (`ACCOUNT_AVAILABLE_BALANCE`, `ACCOUNT_ACTUAL_BALANCE`,
`ACCOUNT_INTEREST_RATE`) → `BigDecimal`. The embedded `SELECT ... FOR UPDATE`
behaviour is reproduced by `AccountRepository.findByKeyForUpdate`, which applies
`LockModeType.PESSIMISTIC_WRITE`.

### PROCTRAN (`PROCTRAN.cpy`) → `ProcessedTransaction` entity

`PROCTRAN` is an append-only log. The original COBOL key (sort code + number) is
not unique per row, so the entity adds a generated surrogate key
(`PROCTRAN_ID`). The transfer record is built exactly as the COBOL does:

- eyecatcher `PRTR` (`PROC-TRAN-VALID`)
- type `TFR` (`PROC-TY-TRANSFER`)
- description = `'TRANSFER'` left-justified in 26 chars + TO sort code (6) + TO
  account (8) (the `PROC-TRAN-DESC-XFR` redefinition)
- keyed on the FROM account, amount = transfer amount.

---

## 6. COBOL construct → Java translation

| COBOL / CICS construct | Java / Spring equivalent |
| --- | --- |
| `EXEC CICS LINK PROGRAM('XFRFUN') COMMAREA(...)` | `POST /xfrfun/transfer` with `TransferRequest` → `TransferResponse` |
| `DFHCOMMAREA` (`XFRFUN.cpy`) | `TransferRequest` (in) + `TransferResponse` (out) DTOs |
| `EXEC SQL SELECT ... ` / `UPDATE ... ` / `INSERT ... ` | Spring Data JPA repositories |
| CICS logical unit of work + `SYNCPOINT` / `SYNCPOINT ROLLBACK` | `@Transactional`; `setRollbackOnly()` for the return-with-failure paths |
| Implicit Db2 row locking under the UOW | `@Lock(PESSIMISTIC_WRITE)` on `findByKeyForUpdate` |
| `EXEC CICS ABEND ABCODE('SAME')` | `SameAccountTransferException` → HTTP 422 |
| `EXEC CICS ABEND ABCODE('WPCD')` / fail code `'3'` | `TransferProcessingException` → HTTP 500 (transaction rolls back) |
| `SQLCODE` checks (`0`, `+100`, other) | `Optional` presence from the repository + `DataAccessException` |
| `EXEC CICS ASKTIME` / `FORMATTIME DDMMYYYY DATESEP('.')` | `java.time.Clock` + `DateTimeFormatter("dd.MM.yyyy")` / `("HHmmss")` |
| `EIBTASKN` used as `PROCTRAN_REF` | injectable reference sequence (see §7) |
| `SORTCODE` constant (`987654`) | `cbsa.bank.sort-code` property |
| `ABNDINFO` linkage to `ABNDPROC` abend handler | not migrated (see §8) |

---

## 7. Behaviour-preserving design decisions

- **`BigDecimal` for money.** The COBOL fields are fixed-point `S9(10)V99`. Using
  `BigDecimal` (scale 2) preserves exact decimal arithmetic; binary
  floating-point would not.
- **Read-before-write instead of update-then-rollback.** The COBOL updates the
  first account and then rolls back if the second account is missing. The Java
  service reads (and locks) both accounts before mutating either, so the same net
  outcome (no committed partial update, correct fail code) is achieved with less
  churn. The transaction is still marked rollback-only on the not-found paths to
  remain faithful to the original `SYNCPOINT ROLLBACK`.
- **Deadlock avoidance preserved.** The ascending-account-number lock order from
  the `COMM-FACCNO < COMM-TACCNO` branch is implemented with ordered pessimistic
  locks, so the original concurrency safety property is retained.
- **PROCTRAN reference number.** The COBOL uses the CICS task number
  (`EIBTASKN`) as `PROCTRAN_REF`. There is no CICS task number off-platform, so
  the service injects a reference sequence; replace this with the platform's
  durable transaction identifier during integration.

---

## 8. Intentionally **not** migrated (and why)

These belong to the mainframe runtime, not the business capability. They should
be handled by the target platform rather than reimplemented:

- **Abend handler linkage (`ABNDINFO` → `ABNDPROC`).** The bulk of `XFRFUN.cbl`
  is boilerplate that captures applid/task/date/time diagnostics and links to a
  shared abend program before abending. In Java this is replaced by exceptions,
  structured logging and the platform's observability stack.
- **Db2 `-911` deadlock retry loop (up to 6 attempts + `EXEC CICS DELAY`).**
  Deadlock/serialization retries should be a cross-cutting concern (e.g. a
  Spring retry interceptor) rather than hand-coded per program.
- **CPSM/WLM "storm drain" handling (`CHECK-FOR-STORM-DRAIN-DB2`, VSAM RLS abends
  `AFCR`/`AFCS`/`AFCT`).** These are CICS workload-management behaviours with no
  direct off-platform equivalent.

If any of these must be retained, raise them as separate, explicit work items so
they are not silently dropped.

---

## 9. Running and testing

```bash
cd src/Transfer-Funds-Service
mvn test          # run the unit / integration tests
mvn spring-boot:run   # start the service on :8080 (H2 in-memory, seeded data)
```

Example request:

```bash
curl -s -X POST http://localhost:8080/xfrfun/transfer \
  -H 'Content-Type: application/json' \
  -d '{"fromAccountNumber":"1","toAccountNumber":"2","amount":100.00}'
```

The H2 database is seeded from `src/main/resources/data.sql` with two accounts
(`00000001`, `00000002`) under sort code `987654`. For a real migration, point
the datasource at the modernized ACCOUNT/PROCTRAN store instead.

### Test coverage (`mvn test`)

| Test | Rule verified | COBOL origin |
| --- | --- | --- |
| `successfulTransferDebitsFromAndCreditsToBothBalances` | both balances debited/credited; success | `UADF010` / `UADT010` |
| `successfulTransferFromHigherToLowerAccountNumber` | lock-ordering branch | `UAD010` else-branch |
| `successfulTransferWritesTransferRecordToProctran` | TFR row, description, date/time | `WTPD010` |
| `nonPositiveAmountReturnsFailCodeFourAndChangesNothing` | fail code `'4'` | `A010` |
| `sameAccountTransferThrowsSameAccountException` | abend `SAME` | `UAD010` |
| `fromAccountNotFoundReturnsFailCodeOne` | fail code `'1'` | `UADF010` |
| `toAccountNotFoundReturnsFailCodeTwoAndRollsBack` | fail code `'2'` + rollback | `UADT010` |
| controller: happy path / `SAME` 422 / validation 400 | HTTP surface | `EXEC CICS LINK` |

---

## 10. Migration checklist for the team

- [ ] Confirm the intra-bank assumption (both sort codes forced to `987654`).
      If cross-bank transfers are needed, this is a **functional change**, not a
      like-for-like migration.
- [ ] Replace the H2 datasource with the target ACCOUNT/PROCTRAN database and
      verify column types/precision (especially decimal scale).
- [ ] Replace the PROCTRAN reference sequence with the platform's durable
      transaction id.
- [ ] Decide where deadlock-retry and observability/abend concerns live on the
      target platform.
- [ ] Validate balance arithmetic against production data with parallel runs
      (COBOL vs. Java) before cutover.
- [ ] Wire authentication/authorization and input validation per the target
      platform's security standards before exposing the endpoint.
