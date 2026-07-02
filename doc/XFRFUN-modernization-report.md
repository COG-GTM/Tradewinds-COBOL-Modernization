# XFRFUN Transfer Funds — COBOL-to-Java modernization report

## 1. Program inventory

### COBOL programs (`src/base/cobol_src/`)

| Category | Program | Description |
|---|---|---|
| **BMS / UI** | `BNKMENU` | Main 3270 menu |
| | `BNK1CAC` | Create Account screen |
| | `BNK1CCA` | Customer Account List screen |
| | `BNK1CCS` | Create Customer screen |
| | `BNK1CRA` | Credit/Debit screen |
| | `BNK1DAC` | Display/Delete Account screen |
| | `BNK1DCS` | Display/Update/Delete Customer screen |
| | `BNK1TFN` | **Transfer Funds screen** (invokes `XFRFUN`) |
| | `BNK1UAC` | Update Account screen |
| **Business logic** | `XFRFUN` | **Transfer Funds** (this modernization) |
| | `DBCRFUN` | Debit/Credit |
| | `CREACC` | Create Account |
| | `CRECUST` | Create Customer |
| | `DELACC` | Delete Account |
| | `DELCUS` | Delete Customer (and accounts) |
| | `INQACC` | Inquire Account |
| | `INQACCCU` | Inquire Accounts by Customer |
| | `INQCUST` | Inquire Customer |
| | `UPDACC` | Update Account |
| | `UPDCUST` | Update Customer |
| **Credit scoring** | `CRDTAGY1–5` | Dummy credit-agency simulators |
| **Utilities** | `ABNDPROC` | Abend Processor |
| | `BANKDATA` | Batch data initializer |
| | `GETCOMPY` | Returns company name |
| | `GETSCODE` | Returns bank sort code |

### Copybooks (`src/base/cobol_copy/`)

37 copybooks total. Those relevant to XFRFUN:

| Copybook | Purpose |
|---|---|
| `XFRFUN.cpy` | COMMAREA layout for Transfer Funds (input/output fields) |
| `ACCDB2.cpy` | `DECLARE ACCOUNT TABLE` — Db2 DDL for the ACCOUNT table |
| `PROCDB2.cpy` | `DECLARE PROCTRAN TABLE` — Db2 DDL for the PROCTRAN table |
| `PROCTRAN.cpy` | Working-storage layout for PROCTRAN record, including `PROC-TRAN-DESC-XFR` overlay |
| `SORTCODE.cpy` | Default sort code: `77 SORTCODE PIC 9(6) VALUE 987654` |
| `ACCOUNT.cpy` | Working-storage layout for ACCOUNT record |
| `ABNDINFO.cpy` | Abend info record for `ABNDPROC` |

### BMS maps (`src/base/bms_src/`)

| Map | Screen |
|---|---|
| `BNK1TFM.bms` | Transfer Funds map (invokes XFRFUN via `BNK1TFN`) |
| `BNK1MAI.bms` | Main menu |
| `BNK1ACC.bms`, `BNK1CAM.bms`, etc. | Other operation maps |

---

## 2. Transfer Funds dependency graph

```
  ┌─────────────────────────────────┐
  │  3270 Terminal (BNK1TFM.bms)    │
  └───────────┬─────────────────────┘
              │ CICS SEND MAP / RECEIVE MAP
              ▼
  ┌─────────────────────────────────┐
  │  BNK1TFN.cbl (BMS handler)     │
  │  Transaction: TFN              │
  └───────────┬─────────────────────┘
              │ EXEC CICS LINK PROGRAM('XFRFUN')
              │ COMMAREA: XFRFUN.cpy
              ▼
  ┌─────────────────────────────────┐
  │  XFRFUN.cbl (business logic)   │  ◄── THIS PROGRAM WAS MODERNIZED
  │  Includes:                      │
  │    COPY SORTCODE                │  (default sort code 987654)
  │    EXEC SQL INCLUDE ACCDB2      │  (ACCOUNT table DDL)
  │    EXEC SQL INCLUDE PROCDB2     │  (PROCTRAN table DDL)
  │    EXEC SQL INCLUDE SQLCA       │
  │    COPY ACCOUNT (x2)            │  (WS-ACC-DATA, WS-ACC-DATA2)
  │    COPY PROCTRAN                │  (PROCTRAN-AREA)
  │    COPY ABNDINFO                │  (abend handler data)
  └──────┬──────────┬───────────────┘
         │          │
         ▼          ▼
  ┌────────────┐ ┌──────────────┐
  │ ACCOUNT    │ │ PROCTRAN     │
  │ (Db2)      │ │ (Db2)        │
  │ SELECT,    │ │ INSERT       │
  │ UPDATE     │ │              │
  └────────────┘ └──────────────┘
         │
         ▼ (on failure)
  ┌────────────────┐
  │ ABNDPROC.cbl   │
  │ (abend handler)│
  └────────────────┘
```

---

## 3. Data dictionary

### ACCOUNT table (ACCDB2.cpy / HOST-ACCOUNT-ROW)

| COBOL field | PIC / Db2 type | Java type | Java field |
|---|---|---|---|
| `ACCOUNT_EYECATCHER` | `CHAR(4)` | `String` | `eyecatcher` |
| `ACCOUNT_CUSTOMER_NUMBER` | `CHAR(10)` | `String` | `customerNumber` |
| `ACCOUNT_SORTCODE` | `CHAR(6) NOT NULL` | `String` | `sortcode` (PK) |
| `ACCOUNT_NUMBER` | `CHAR(8) NOT NULL` | `String` | `accountNumber` (PK) |
| `ACCOUNT_TYPE` | `CHAR(8)` | `String` | `accountType` |
| `ACCOUNT_INTEREST_RATE` | `DECIMAL(4,2)` / `PIC S9(4)V99 COMP-3` | `BigDecimal` | `interestRate` |
| `ACCOUNT_OPENED` | `DATE` | `LocalDate` | `opened` |
| `ACCOUNT_OVERDRAFT_LIMIT` | `INTEGER` / `PIC S9(9) COMP` | `int` | `overdraftLimit` |
| `ACCOUNT_LAST_STATEMENT` | `DATE` | `LocalDate` | `lastStatement` |
| `ACCOUNT_NEXT_STATEMENT` | `DATE` | `LocalDate` | `nextStatement` |
| `ACCOUNT_AVAILABLE_BALANCE` | `DECIMAL(10,2)` / `PIC S9(10)V99 COMP-3` | `BigDecimal` | `availableBalance` |
| `ACCOUNT_ACTUAL_BALANCE` | `DECIMAL(10,2)` / `PIC S9(10)V99 COMP-3` | `BigDecimal` | `actualBalance` |

### PROCTRAN table (PROCDB2.cpy / HOST-PROCTRAN-ROW)

| COBOL field | PIC / Db2 type | Java type | Java field |
|---|---|---|---|
| `PROCTRAN_EYECATCHER` | `CHAR(4)` | `String` | `eyecatcher` |
| `PROCTRAN_SORTCODE` | `CHAR(6) NOT NULL` | `String` | `sortcode` |
| `PROCTRAN_NUMBER` | `CHAR(8) NOT NULL` | `String` | `accountNumber` |
| `PROCTRAN_DATE` | `CHAR(8)` | `String` | `transactionDate` |
| `PROCTRAN_TIME` | `CHAR(6)` | `String` | `transactionTime` |
| `PROCTRAN_REF` | `CHAR(12)` | `String` | `reference` |
| `PROCTRAN_TYPE` | `CHAR(3)` — `'TFR'` for transfer | `String` | `type` |
| `PROCTRAN_DESC` | `CHAR(40)` | `String` | `description` |
| `PROCTRAN_AMOUNT` | `DECIMAL(12,2)` / `PIC S9(10)V99 COMP-3` | `BigDecimal` | `amount` |

### XFRFUN COMMAREA (XFRFUN.cpy / DFHCOMMAREA)

| COBOL field | PIC | Direction | Java mapping |
|---|---|---|---|
| `COMM-FACCNO` | `9(8)` | In | `TransferFundsForm.fromAccountNumber` |
| `COMM-FSCODE` | `9(6)` | In | `TransferFundsForm.fromSortCode` |
| `COMM-TACCNO` | `9(8)` | In | `TransferFundsForm.toAccountNumber` |
| `COMM-TSCODE` | `9(6)` | In | `TransferFundsForm.toSortCode` |
| `COMM-AMT` | `S9(10)V99` | In | `TransferFundsForm.amount` (`BigDecimal`) |
| `COMM-FAVBAL` | `S9(10)V99` | Out | `TransferFundsResponse.fromAvailableBalance` |
| `COMM-FACTBAL` | `S9(10)V99` | Out | `TransferFundsResponse.fromActualBalance` |
| `COMM-TAVBAL` | `S9(10)V99` | Out | `TransferFundsResponse.toAvailableBalance` |
| `COMM-TACTBAL` | `S9(10)V99` | Out | `TransferFundsResponse.toActualBalance` |
| `COMM-FAIL-CODE` | `X` | Out | `TransferFundsResponse.failCode` |
| `COMM-SUCCESS` | `X` | Out | `TransferFundsResponse.success` |

---

## 4. Business rules (extracted from XFRFUN.cbl)

### 4.1 Input validation (PREMIERE / A010)

1. **Negative/zero amount rejected:** If `COMM-AMT <= ZERO`, set `COMM-SUCCESS = 'N'`, `COMM-FAIL-CODE = '4'`, and return immediately.
2. **Same-account transfer rejected:** If `COMM-FACCNO = COMM-TACCNO AND COMM-FSCODE = COMM-TSCODE`, abend with code `'SAME'` — this is a hard error, not a soft failure.

### 4.2 Deadlock-prevention ordering (UPDATE-ACCOUNT-DB2 / UAD010)

The COBOL compares account numbers to determine update order:
- If `COMM-FACCNO < COMM-TACCNO`: update FROM first, then TO.
- If `COMM-FACCNO >= COMM-TACCNO`: update TO first, then FROM.

This prevents Db2 deadlocks when two concurrent transfers between the same pair of accounts are running in opposite directions.

### 4.3 FROM account update (UPDATE-ACCOUNT-DB2-FROM / UADF010)

1. `SELECT ... FROM ACCOUNT WHERE (ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?)`
2. If `SQLCODE = +100` (not found): `COMM-FAIL-CODE = '1'`, return.
3. If `SQLCODE` is any other non-zero: `COMM-FAIL-CODE = '3'`, check storm drain, return.
4. Debit: `AVAIL_BAL = AVAIL_BAL - COMM-AMT`, `ACTUAL_BAL = ACTUAL_BAL - COMM-AMT`
5. `UPDATE ACCOUNT SET ... WHERE ...`
6. If UPDATE fails (`SQLCODE != 0`): `COMM-FAIL-CODE = '3'`, return.
7. Store updated balances in `COMM-FAVBAL`, `COMM-FACTBAL`; set `COMM-SUCCESS = 'Y'`.

### 4.4 TO account update (UPDATE-ACCOUNT-DB2-TO / UADT010)

1. `INITIALIZE HOST-ACCOUNT-ROW` (clear host variables).
2. `SELECT ... FROM ACCOUNT WHERE (ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?)`
3. If `SQLCODE = +100` (not found):
   - `COMM-FAIL-CODE = '2'`
   - `EXEC CICS SYNCPOINT ROLLBACK` (undo the first account's changes)
   - Return.
4. If `SQLCODE = -911` (deadlock): retry up to 5 times with 1-second delay, rolling back between attempts. After 5 retries, abend with `'RUF2'`.
5. If `SQLCODE` is any other non-zero: abend with `'RUF2'`.
6. Credit: `AVAIL_BAL = AVAIL_BAL + COMM-AMT`, `ACTUAL_BAL = ACTUAL_BAL + COMM-AMT`
7. `UPDATE ACCOUNT SET ... WHERE ...`
8. If UPDATE fails: same deadlock-retry / abend logic as SELECT.
9. Store updated balances in `COMM-TAVBAL`, `COMM-TACTBAL`; set `COMM-SUCCESS = 'Y'`.

### 4.5 No overdraft enforcement

Per the program header comment (line 21): *"No checking is made on overdraft limits."* The FROM account balance can go negative.

### 4.6 PROCTRAN logging (WRITE-TO-PROCTRAN-DB2 / WTPD010)

On success:
1. Eyecatcher = `'PRTR'`
2. Sort code and account number = FROM account
3. Reference = EIBTASKN (task number) zero-padded to 12 digits
4. Type = `'TFR'` (transfer)
5. Description = `PROC-TRAN-DESC-XFR` layout: 26-char header (`'TRANSFER'` padded) + 6-char TO sort code + 8-char TO account number
6. Amount = `COMM-AMT`
7. `INSERT INTO PROCTRAN`
8. If INSERT fails: abend with `'WPCD'` — the COBOL comment notes this creates a data inconsistency since ACCOUNT was already updated.

### 4.7 Rollback / abend behavior

| Condition | ABEND code | Effect |
|---|---|---|
| Same-account transfer | `SAME` | Immediate abend |
| Second-leg account update fails (recoverable) | (rollback) | `SYNCPOINT ROLLBACK`, return with fail code |
| Second-leg update fails (non-recoverable) | `TO  ` / `FROM` | Abend after logging |
| Rollback itself fails | `HROL` | Abend |
| Db2 deadlock after 5 retries | `RUF2` | Abend |
| Db2 timeout | `RUF2` | Abend |
| PROCTRAN write fails | `WPCD` | Abend (data inconsistency) |
| VSAM RLS abend (storm drain) | `AFCR`/`AFCS`/`AFCT` | Rollback, set fail code `'2'`, return |
| Unhandled abend | (original code) | Re-abend |

### 4.8 Input-format tolerance

Per the program header (line 20–21): *"This program needs to be able to cope with the data in any format."* The COBOL uses `MOVE` operations that handle varying-length numeric input; the Java equivalent uses `padAccountNumber()` and `normaliseSortCode()`.

---

## 5. COBOL-to-Java mapping table

| COBOL section / paragraph | Lines | Java class | Java method |
|---|---|---|---|
| `PREMIERE` / `A010` | 269–305 | `TransferFundsService` | `transferFunds()` |
| `UPDATE-ACCOUNT-DB2` / `UAD010` | 308–916 | `TransferFundsService` | `transferFunds()` (ordering + orchestration) |
| `UPDATE-ACCOUNT-DB2-FROM` / `UADF010` | 919–1038 | `TransferFundsService` | `debitFromAccount()` |
| `UPDATE-ACCOUNT-DB2-TO` / `UADT010` | 1041–1560 | `TransferFundsService` | `creditToAccount()` |
| `WRITE-TO-PROCTRAN` / `WTP010` | 1563–1568 | `TransferFundsService` | `writeProcessedTransaction()` |
| `WRITE-TO-PROCTRAN-DB2` / `WTPD010` | 1571–1723 | `TransferFundsService` | `writeProcessedTransaction()` |
| `GET-ME-OUT-OF-HERE` / `GMOOH010` | 1726–1734 | Controller | HTTP response return |
| `CHECK-FOR-STORM-DRAIN-DB2` | 1737–1766 | — | Not applicable (CICS workload mgmt) |
| `ABEND-HANDLING` / `AH010` | 1769–1906 | Spring `@ExceptionHandler` | `handleSameAccount()`, `handleRollback()` |
| `POPULATE-TIME-DATE` / `PTD010` | 1909–1924 | `TransferFundsService` | `LocalDateTime.now()` |
| `HOST-ACCOUNT-ROW` | 65–78 | `AccountEntity` | JPA entity |
| `HOST-PROCTRAN-ROW` | 86–95 | `ProcessedTransactionEntity` | JPA entity |
| `DFHCOMMAREA` / `XFRFUN.cpy` | 262–266 | `TransferFundsForm` / `TransferFundsResponse` | Request/response DTOs |
| `SORTCODE` copybook | — | `TransferFundsService` | `DEFAULT_SORT_CODE = "987654"` |
| `EXEC CICS SYNCPOINT ROLLBACK` | various | Spring `@Transactional` | RuntimeException triggers rollback |
| `EXEC CICS ABEND` | various | Custom exceptions | `SameAccountTransferException`, `TransferRollbackException` |

---

## 6. Test coverage

13 characterization tests covering:

| Test | COBOL rule |
|---|---|
| `happyPathTransfer` | Full success path + PROCTRAN write |
| `packedDecimalPrecision` | `PIC S9(10)V99 COMP-3` → `BigDecimal` (cent accuracy) |
| `largeAmountTransfer` | Max value `9,999,999,999.99` |
| `zeroAmountRejected` | `COMM-AMT <= ZERO` → fail code `'4'` |
| `negativeAmountRejected` | Negative amount → fail code `'4'` |
| `sameAccountThrows` | `ABCODE('SAME')` |
| `fromAccountNotFound` | `SQLCODE = +100` → fail code `'1'` |
| `toAccountNotFoundRollsBack` | `SQLCODE = +100` → `SYNCPOINT ROLLBACK` |
| `atomicRollbackOnSecondLegFailure` | Both accounts restored after rollback |
| `defaultSortCode` | `SORTCODE.cpy VALUE 987654` |
| `accountNumberPadding` | `PIC 9(8)` → leading-zero padding |
| `reverseOrderWhenFromGreaterThanTo` | Deadlock-prevention ordering |
| `overdraftAllowed` | No overdraft-limit enforcement |

---

## 7. Next candidate programs for modernization

| Priority | Program | Rationale |
|---|---|---|
| 1 | `DBCRFUN` | Same Db2 pattern (single-account debit/credit), simpler than XFRFUN — shares ACCOUNT + PROCTRAN tables |
| 2 | `CREACC` | Create Account — uses Named Counters + ACCOUNT insert + PROCTRAN logging |
| 3 | `CRECUST` | Create Customer — most complex: Named Counters + async credit scoring + VSAM + PROCTRAN |
| 4 | `DELACC` / `DELCUS` | Delete operations — cascade delete pattern, PROCTRAN logging |
| 5 | `INQACC` / `INQCUST` | Read-only queries — simplest to port, good for building out the inquiry APIs |
