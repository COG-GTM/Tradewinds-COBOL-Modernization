# DBCRFUN parity report

Java port of `src/base/cobol_src/DBCRFUN.cbl` (counter debit/credit). Line numbers refer to that
file. Java paths are relative to `src/main/java/com/tradewinds/tradedesk/`; test classes are in
`src/test/java/com/tradewinds/tradedesk/`.

`DbcrfunServiceTest` runs against H2 (DB2 mode) with a fixed `Clock`. `DbcrfunServiceMockTest`
uses Mockito repositories for failure paths that are hard to provoke on a real database.

## Interface mapping

| COBOL | Java |
|---|---|
| COMMAREA `PAYDBCR.cpy` input (`COMM-ACCNO`, `COMM-AMT`, `COMM-SORTC`, `COMM-ORIGIN`) | `dbcrfun/DbcrfunRequest`, `dbcrfun/DbcrfunOrigin` |
| COMMAREA output (`COMM-SUCCESS`, `COMM-FAIL-CODE`, `COMM-SORTC`, `COMM-AV-BAL`, `COMM-ACT-BAL`) | `dbcrfun/DbcrfunResponse` |
| `ACCDB2.cpy` / table `ACCOUNT` | `dbcrfun/Account` (`@IdClass(AccountId)` = sortcode + number) |
| `PROCDB2.cpy` / table `PROCTRAN` | `dbcrfun/ProcTran` |
| `SORTCODE.cpy` (`77 SORTCODE PIC 9(6) VALUE 987654`) | `bank.sortcode` in `application.yml` → `config/BankProperties` |
| `EXEC CICS LINK PROGRAM('DBCRFUN')` | `POST /api/dbcrfun` → `dbcrfun/DbcrfunController` |
| `EIBTASKN` (PROCTRAN ref) | `dbcrfun/TaskNumberSource` |
| `EXEC CICS ASKTIME` / `FORMATTIME` | injected `java.time.Clock` |
| `EXEC CICS SYNCPOINT ROLLBACK` | `@Transactional` + `setRollbackOnly()` |

`DbcrfunResponse` also returns `sortc`, because L211 writes the site sortcode back into the
COMMAREA and the caller can read it.

## Branch mapping

| # | COBOL (DBCRFUN.cbl) | Behaviour | Java (`DbcrfunService`) | Test(s) |
|---|---|---|---|---|
| 0 | L198-202 | Initialise `COMM-SUCCESS='N'`, `COMM-FAIL-CODE='0'` | `failure(...)` / final response | all |
| 0a | L211-212 | Input `COMM-SORTC` ignored; site SORTCODE used and returned | `applyTransaction` start | `DbcrfunServiceTest.inputSortcodeIsIgnoredAndSiteSortcodeIsUsed` |
| 0b | L238 | `COMM-ACCNO` moved to `PIC 9(8)` (zero-filled) | `CobolText.moveToPic9` | `DbcrfunServiceTest.shortAccountNumberIsZeroFilledLikePic9` |
| 1 | L245-297, L283-284 | SELECT returns SQLCODE +100 → `N`/`1` | `findById` empty | `DbcrfunServiceTest.accountNotFoundReturnsFailCode1` |
| 2 | L279-297, L286 | Other SELECT error → `N`/`2` | `catch (DataAccessException)` on lookup | `DbcrfunServiceTest.selectErrorReturnsFailCode2`, `DbcrfunServiceMockTest.selectConnectionLossReturnsFailCode2AndIsAStormDrainCondition` |
| 2a | L293, L668-692 (`WHEN 923`) | Storm-drain warning on connection loss | `checkForStormDrain` / `isConnectionLost` (SQLCODE 923 or SQLSTATE class `08`) | `DbcrfunServiceMockTest.stormDrainMatchesSqlcode923AndSqlstate08Only` |
| 2b | L245-286 (no null indicators) | NULL balance → SQLCODE -305 → `N`/`2` | null check after lookup | `DbcrfunServiceMockTest.nullBalanceReturnsFailCode2LikeSqlcode305` |
| 3 | L307, L330-338 | Debit + facilType 496 + type `HOSPITAL`/`TENDER  ` → `N`/`4` | `amt < 0` block, `isRestrictedPayment` | `DbcrfunServiceTest.paymentDebitFromHospitalOrTenderAccountReturnsFailCode4` (HOSPITAL and TENDER) |
| 4 | L340-350 | Debit + facilType 496 + `avail + amt < 0` → `N`/`3` | `amt < 0` block | `DbcrfunServiceTest.paymentDebitWithInsufficientFundsReturnsFailCode3` |
| 5 | L344 (facilType ≠ 496) | Teller debit may overdraw | same block, condition false | `DbcrfunServiceTest.tellerDebitWithInsufficientFundsSucceedsAsOverdraft` |
| 6 | L368-376 | HOSPITAL/TENDER + 496 checked again for every amount → `N`/`4` | second `isRestrictedPayment` call | `DbcrfunServiceTest.paymentCreditToHospitalAccountReturnsFailCode4` |
| 7 | L384-387 | Amount added to available and actual balance | balance arithmetic | `DbcrfunServiceTest.tellerDebitSucceedsUpdatesBalancesAndWritesDebProctran` |
| 8 | L392-435, L416-419, L425-432 | UPDATE error → `N`/`2`; computed balances still returned | `catch` around `accountRepository.saveAndFlush` | `DbcrfunServiceMockTest.accountUpdateFailureReturnsFailCode2WithComputedBalances` |
| 9 | L464-470, L466, L482-487 | PROCTRAN `PRTR`, ref = task number, date `DD.MM.YYYY`, time `HHMMSS` | `writeToProctranDb2` | `DbcrfunServiceTest.tellerDebitSucceedsUpdatesBalancesAndWritesDebProctran` |
| 10 | L491-493 | Teller debit → `DEB` / `COUNTER WTHDRW` | `writeToProctranDb2` | `DbcrfunServiceTest.tellerDebitSucceedsUpdatesBalancesAndWritesDebProctran` |
| 11 | L505-506 | Teller credit → `CRE` / `COUNTER RECVED` | `writeToProctranDb2` | `DbcrfunServiceTest.tellerCreditSucceedsAndWritesCreProctran` |
| 12 | L498-501 | Payment debit → `PDR` / first 14 chars of `COMM-ORIGIN` | `writeToProctranDb2`, `DbcrfunOrigin.commareaPrefix(14)` | `DbcrfunServiceTest.paymentDebitWritesPdrProctranWithOriginPrefix` |
| 13 | L511-514 | Payment credit → `PCR` / first 14 chars of `COMM-ORIGIN` | same | `DbcrfunServiceTest.paymentCreditWritesPcrProctranWithOriginPrefix` |
| 14 | L554-564, L637, L642 | INSERT error → SYNCPOINT ROLLBACK, `N`/`0` (`'02'` truncated by `PIC X`) | `catch (ProctranWriteException)`, `requestRollback()` | `DbcrfunServiceTest.proctranInsertFailureRollsBackAccountAndReturnsTruncatedFailCode0`, `DbcrfunServiceMockTest.proctranInsertFailureReturnsNWithSingleCharFailCode0` |
| 15 | L649-650 | Success → `Y`/`0` with new balances | final `return` | tests 7, 8, 9 above |

## Edge cases

| Edge case | COBOL | Java | Test |
|---|---|---|---|
| `amt = 0` is a credit | L307 / L491 `IF COMM-AMT < 0` else-branch | `amt.signum() < 0` | `DbcrfunServiceTest.zeroAmountIsTreatedAsCredit` (496 → `PCR`), `DbcrfunServiceTest.zeroAmountTellerRequestWritesCreProctran` (teller → `CRE`) |
| Account type compared padded to 8 chars | `HV-ACCOUNT-ACC-TYPE PIC X(8)` vs `'HOSPITAL'`, `'TENDER  '` | `CobolText.moveToPicX(type, 8)` before compare | `DbcrfunServiceMockTest.accountTypeComparisonIsSpacePaddedToEightChars` (`TENDER`, `TENDER  `, `TENDERX` ...) |
| `failCode` single character | `MOVE '02' TO COMM-FAIL-CODE` (PIC X) → `'0'` | constant `FAIL_PROCTRAN = "0"`, commented at the L637 mapping | `CobolTextTest.moveOfTwoCharLiteralToPicXKeepsOnlyLeftmostChar`, branch 14 tests |
| Amount scale | `COMM-AMT PIC S9(10)V99` | `@Digits(integer=10, fraction=2)`; extra decimals truncated, not rounded | `DbcrfunServiceMockTest.amountWithMoreThanTwoDecimalsIsTruncatedNotRounded`, `DbcrfunControllerTest.requestThatCannotFitTheCommareaIsRejected` |
| HTTP shape | Business failures are COMMAREA values, not abends | `200 OK` with `success="N"` | `DbcrfunControllerTest.businessFailureIsHttp200WithSuccessN` |

## Not ported / deliberate differences

- **Abend handling (L700-830, `AD2Z`, `AFCR`/`AFCS`/`AFCT`, `HROL`, `EXEC CICS LINK ABNDPROC`)**: CICS-specific. Unexpected exceptions propagate and Spring rolls the transaction back.
- **Storm drain**: logged as a `WARN`. There is no CICS storm-drain state to set.
- **`PROCTRAN_DATE` column type**: the DB2 DDL (`etc/install/base/db2jcl/INSTDB2.jcl`) declares `DATE`, but DBCRFUN binds a `PIC X(10)` host variable holding `DD.MM.YYYY`. `ProcTran.date` is a `String` with that format, and the H2 test schema uses `CHAR(10)`.
- **Row lock**: `AccountRepository.findById` uses `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`). DBCRFUN does a plain SELECT followed by an absolute UPDATE, so two concurrent postings to one account can overwrite each other's balance change. The outputs stay the same.
- **Logs**: error logs do not include the account number. The COBOL DISPLAYs do not include it either.
- **HTTP**: responses carry `Cache-Control: no-store` because they contain balances.
- **PROCTRAN key**: the legacy table has no primary key. `ProcTranId` (sortcode, number, date, time, ref) is only a JPA identity.

## `mvn test` results

Run in `src/trade-desk-java` with `mvn -B test` (Maven 3.9.9):

```
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.784 s -- in com.tradewinds.tradedesk.dbcrfun.DbcrfunServiceTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.275 s -- in com.tradewinds.tradedesk.dbcrfun.DbcrfunServiceMockTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.050 s -- in com.tradewinds.tradedesk.dbcrfun.DbcrfunControllerTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s -- in com.tradewinds.tradedesk.config.CobolTextTest
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  5.580 s
```

The ERROR log lines in the full output are expected: some tests rename `ACCOUNT` or `PROCTRAN` on
purpose to make DB2 errors happen.

A mutation check removed the `setRollbackOnly()` call. The rollback test then failed, which shows
the test checks the rollback.
