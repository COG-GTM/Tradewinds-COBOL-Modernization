# Movement & PROCTRAN data service (`dataservice.movement`)

REST endpoints that replace the embedded `EXEC SQL` in **DBCRFUN**, **XFRFUN**,
**CRECUST** and **DELCUS** (PROCTRAN audit rows only). Hosted in the Liberty web
tier under `@ApplicationPath("banking")`, so the full URL is
`/banking/data/movement/...` and `/banking/data/proctran/...`.

Classes: `MovementResource`, `ProctranResource` (JAX-RS, thin) ->
`MovementRequestHandler` (validation / JSON mapping) -> `MovementDao`,
`ProctranDao` (plain JDBC on `java.sql.Connection`).

## Common rules

* **Content type**: `application/json` in and out. Bodies are POJOs in
  `MovementJson` bound by the JAX-RS JSON provider (public fields; `null`
  fields are omitted from responses).
* **Numeric keys are JSON strings** (`"sortCode":"987654"`, `"accountNumber":"00000001"`).
  They may be shorter than the column (`"1"`) and are left zero-padded to
  `CHAR(6)` / `CHAR(8)` / `CHAR(10)`, matching the COBOL `PIC 9(n)` keys.
  Non-digits or too many digits -> 400 `V`.
* **Amounts are decimal strings** (`"-12.34"`), at most 2 decimal places and
  within `PIC S9(10)V99` (|amount| <= 9999999999.99). Response amounts and
  balances are strings with exactly 2 decimals.
* **`taskNumber`** (JSON integer, required on every POST) is the caller's
  `EIBTASKN`. It is written to `PROCTRAN_REF` as 12 zero-padded digits
  (`MOVE EIBTASKN TO WS-EIBTASKN12; MOVE WS-EIBTASKN12 TO HV-PROCTRAN-REF`).
* **`PROCTRAN_DATE` / `PROCTRAN_TIME`** are stamped server-side from the JVM
  clock (replaces `EXEC CICS ASKTIME` + `FORMATTIME DDMMYYYY TIME`): `DATE` column
  bound with `setDate`, time as `HHMMSS`. `PROCTRAN_EYECATCHER` is always `PRTR`.
* **Every POST response** is a `MovementResponse`:
  `success` (`Y`/`N`, -> `COMM-SUCCESS`), `failCode` (one char, -> `COMM-FAIL-CODE`),
  optional `abendCode`, optional `errorMessage`, endpoint-specific balances and,
  on success, `proctran` (the row written, see *PROCTRAN row JSON*).
* **`abendCode` present** means the COBOL program would have issued
  `EXEC CICS ABEND ABCODE(<abendCode>)` at that point. The rewritten COBOL must
  issue that abend (the data service has already rolled back its JDBC unit of
  work, and the resource has called `BankRuntime.rollback()`). Check `abendCode`
  before `failCode`; on abend paths `failCode` holds whatever COBOL would have
  left in `COMM-FAIL-CODE` and is not meaningful on its own.
* **Malformed request** (missing/blank field, bad digits, bad decimal, bad DOB,
  unknown PROCTRAN type, description > 40) -> **400**, `success:"N"`,
  `failCode:"V"`. `V` has no COBOL equivalent; a correct COBOL caller never sees it.
  Unparseable JSON is rejected by the JAX-RS provider before reaching the
  handler (400, provider-defined body).
* **Atomicity**: each endpoint is one JDBC unit of work (`setAutoCommit(false)`,
  commit on success, rollback on any failure, original autocommit restored).
  On any status >= 400 the resource also calls `BankRuntime.rollback()` before
  `terminate()`, mirroring `EXEC CICS SYNCPOINT ROLLBACK`.

### PROCTRAN row JSON (`proctran`, and each element of `records`)

```json
{"eyecatcher":"PRTR","sortCode":"987654","accountNumber":"00000001",
 "date":"2026-10-29","time":"140509","reference":"000000004321","type":"DEB",
 "description":"COUNTER WTHDRW                          ","amount":"-40.00"}
```

`description` is returned exactly as stored: `CHAR(40)`, blank padded.

---

## POST `/data/movement/debitCredit` — DBCRFUN

Replaces `UPDATE-ACCOUNT-DB2` (UAD010: SELECT, business checks, UPDATE ACCOUNT)
and `WRITE-TO-PROCTRAN-DB2` (WTPD010: INSERT PROCTRAN) of **DBCRFUN.cbl**.

Request (fields from `PAYDBCR.cpy`):

```jsonc
{"sortCode":"987654",      // SORTCODE (COMM-SORTC)
 "accountNumber":"00000001",// COMM-ACCNO
 "amount":"-40.00",         // COMM-AMT; < 0 debit, >= 0 credit
 "facilityType":496,        // COMM-FACILTYPE, optional, default 0; 496 = payment interface
 "applId":"CICSAPPL",       // COMM-APPLID, optional (used only when facilityType = 496)
 "userId":"PAYUSER1",       // COMM-USERID, optional (used only when facilityType = 496)
 "taskNumber":4321}         // EIBTASKN
```

Response 200:

```json
{"success":"Y","failCode":"0","availableBalance":"60.00","actualBalance":"60.00",
 "proctran":{...}}
```

`availableBalance` -> `COMM-AV-BAL`, `actualBalance` -> `COMM-ACT-BAL`.

Processing (in DBCRFUN order):

1. `SELECT ... FOR UPDATE` the account by sort code + account number.
2. If `amount < 0` and `facilityType = 496`:
   * account type `MORTGAGE` or `LOAN` -> fail `4`;
   * `available + amount < 0` -> fail `3`. (The overdraft limit is **not**
     used; teller debits — any other facility type — are never funds-checked and
     may take the balance negative.)
3. If `facilityType = 496` and account type `MORTGAGE`/`LOAN` -> fail `4`
   (so payment *credits* to mortgage/loan accounts are rejected too; teller
   transactions on them are allowed).
4. `available += amount`, `actual += amount`; UPDATE ACCOUNT.
5. INSERT PROCTRAN:

   | case | `PROCTRAN_TYPE` | `PROCTRAN_DESC` |
   |---|---|---|
   | amount < 0, facilityType != 496 | `DEB` | `COUNTER WTHDRW` |
   | amount >= 0, facilityType != 496 | `CRE` | `COUNTER RECVED` |
   | amount < 0, facilityType = 496 | `PDR` | `COMM-ORIGIN(1:14)` = `applId` (8, blank padded) + first 6 chars of `userId` |
   | amount >= 0, facilityType = 496 | `PCR` | same as `PDR` |

   `PROCTRAN_NUMBER` = account, `PROCTRAN_AMOUNT` = `amount` (signed).

| outcome | HTTP | success | failCode | abendCode | COBOL |
|---|---|---|---|---|---|
| success | 200 | Y | 0 | | WTPD010 `MOVE 'Y'`/`'0'` |
| account not found | 404 | N | 1 | | UAD010 SQLCODE +100 |
| SELECT failed | 500 | N | 2 | | UAD010 SQLCODE other |
| payment on MORTGAGE/LOAN | 400 | N | 4 | | UAD010 |
| payment debit, insufficient available balance | 400 | N | 3 | | UAD010 |
| UPDATE ACCOUNT failed (balances in response are the computed new values, as COBOL moves them to COMM-AV-BAL/ACT-BAL before testing SQLCODE) | 500 | N | 2 | | UAD010 |
| INSERT PROCTRAN failed: account update rolled back | 500 | N | **0** | | WTPD010 `SYNCPOINT ROLLBACK`; `MOVE '02' TO COMM-FAIL-CODE` (PIC X) stores `'0'` |
| invalid request | 400 | N | V | | — |

DBCRFUN's own abend on a *failed* rollback (`HROL`) has no equivalent: a failed
JDBC rollback is logged and reported as 500.

---

## POST `/data/movement/transfer` — XFRFUN

Replaces `UPDATE-ACCOUNT-DB2` (UAD010 ordering/rollback logic),
`UPDATE-ACCOUNT-DB2-FROM` (UADF010), `UPDATE-ACCOUNT-DB2-TO` (UADT010) and
`WRITE-TO-PROCTRAN-DB2` (WTPD010) of **XFRFUN.cbl**.

Request (fields from `XFRFUN.cpy`):

```jsonc
{"sortCode":"987654",          // SORTCODE (COMM-FSCODE = COMM-TSCODE)
 "fromAccountNumber":"00000001",// COMM-FACCNO
 "toAccountNumber":"00000002",  // COMM-TACCNO
 "amount":"30.25",              // COMM-AMT
 "taskNumber":777}              // EIBTASKN
```

Response 200:

```json
{"success":"Y","failCode":"0",
 "fromAvailableBalance":"69.75","fromActualBalance":"69.75",
 "toAvailableBalance":"35.25","toActualBalance":"35.25","proctran":{...}}
```

-> `COMM-FAVBAL`, `COMM-FACTBAL`, `COMM-TAVBAL`, `COMM-TACTBAL`.

Processing:

1. `amount <= 0` -> fail `4` (A010; checked before anything else).
2. from = to -> abend `SAME` (UAD010).
3. Lock and update in **ascending account-number order** (UAD010
   `IF COMM-FACCNO < COMM-TACCNO`): each account is `SELECT ... FOR UPDATE`
   then UPDATEd before the other is touched. FROM: `available -= amount`,
   `actual -= amount`; TO: `+= amount`. **No funds check** (XFRFUN has none).
4. INSERT PROCTRAN: sort code = `sortCode`, number = from account, type `TFR`,
   amount = `amount` (positive), description (`PROC-TRAN-DESC-XFR`):

   | offset (0-based) | length | content |
   |---|---|---|
   | 0 | 26 | `TRANSFER` blank padded |
   | 26 | 6 | target sort code |
   | 32 | 8 | target account number |

5. Any failure rolls back **both** account updates.
6. Deadlock / serialization failure (SQLSTATE `40P01`/`40001`, DB2 `-911`):
   whole unit of work is rolled back and retried up to 5 times with a 1 s
   delay (XFRFUN `DB2-DEADLOCK-RETRY < 6` + `DELAY FOR SECONDS(1)`); when
   exhausted, the abend of the step that failed is returned. (XFRFUN only
   retries in the TO paragraph; with consistent lock ordering deadlocks should
   not occur.)

| outcome | HTTP | success | failCode | abendCode | COBOL |
|---|---|---|---|---|---|
| success | 200 | Y | 0 | | UAD010 `MOVE 'Y'` |
| amount <= 0 | 400 | N | 4 | | A010 |
| same account | 400 | N | 0 | `SAME` | UAD010 `ABEND ABCODE('SAME')` |
| FROM not found (TO update rolled back if TO went first) | 404 | N | 1 | | UADF010 +100, UAD010 `SYNCPOINT ROLLBACK` |
| TO not found (FROM update rolled back if FROM went first) | 404 | N | 2 | | UADT010 +100 + `SYNCPOINT ROLLBACK` |
| FROM SELECT/UPDATE failed | 500 | N | 3 | `FROM` | UADF010 `'3'`, UAD010 `ABEND ABCODE('FROM')` |
| TO SELECT failed | 500 | N | 3 | `RUF2` | UADT010 `'3'`, `ABEND ABCODE('RUF2')` |
| TO UPDATE failed | 500 | N | 0 | `RUF3` | UADT010 `ABEND ABCODE('RUF3')` (fail code not set) |
| INSERT PROCTRAN failed: both updates rolled back | 500 | N | 0 | `WPCD` | WTPD010 `ABEND ABCODE('WPCD')` |
| commit failed | 500 | N | 3 | | — |
| invalid request | 400 | N | V | | — |

---

## POST `/data/proctran/createCustomer` — CRECUST

Replaces `WRITE-PROCTRAN-DB2` (WPD010) of **CRECUST.cbl**. The CUSTOMER VSAM
write stays in COBOL; call this after it.

## POST `/data/proctran/deleteCustomer` — DELCUS

Replaces `WRITE-PROCTRAN-CUST-DB2` (WPCD010) of **DELCUS.cbl**. (The per-account
`ODA` rows are DELACC's and are not written here.)

Request (both):

```jsonc
{"sortCode":"987654",        // STORED-SORTCODE / WS-STOREDC-SORTCODE
 "customerNumber":"1234",    // STORED-CUSTNO / WS-STOREDC-NUMBER
 "customerName":"Mr John Smith-Jones", // CUSTOMER-NAME (only first 14 chars stored)
 "dateOfBirth":"31121975",   // CUSTOMER-DATE-OF-BIRTH, PIC 9(8) DDMMYYYY
 "taskNumber":55}
```

Row written: number `00000000` (`MOVE ZEROS`), amount `0.00`, type `OCC`
(create) / `ODC` (delete), description:

| offset (0-based) | length | content | COBOL |
|---|---|---|---|
| 0 | 6 | sort code | `HV-PROCTRAN-DESC(1:6)` |
| 6 | 10 | customer number | `(7:10)` |
| 16 | 14 | name, truncated / blank padded | `(17:14)` |
| 30 | 10 | DOB `DD/MM/YYYY` | `(31:10)`, built as `DOB(1:2) '/' DOB(3:2) '/' DOB(5:4)` |

e.g. `9876540000001234Mr John Smith-31/12/1975`. The Java decoder
(`web/db2/ProcessedTransaction.processCreateDeleteCustomerRecord`) reads
DD=30-32, MM=33-35, YYYY=36-40, so this round-trips.

| outcome | HTTP | success | failCode | abendCode | COBOL |
|---|---|---|---|---|---|
| success | 200 | Y | 0 | | |
| INSERT failed (rolled back) | 500 | N | 0 | `HWPT` | WPD010 / WPCD010 `ABEND ABCODE('HWPT')` |
| invalid request (incl. DOB not 8 digits) | 400 | N | V | | — |

---

## POST `/data/proctran` — generic validated writer

Request:

```json
{"sortCode":"987654","accountNumber":"1","type":"CHI",
 "description":"CHEQUE PAID IN","amount":"12.30","taskNumber":9}
```

* `type` must be one of the `PROC-TY-*` 88-levels in `PROCTRAN.cpy`:
  `CHA CHF CHI CHO CRE DEB ICA ICC IDA IDC OCA OCC ODA ODC OCS PCR PDR TFR`.
* `description` optional, max 40 chars, stored blank padded.

| outcome | HTTP | success | failCode |
|---|---|---|---|
| success | 200 | Y | 0 |
| INSERT failed | 500 | N | 2 |
| invalid request | 400 | N | V |

## GET `/data/proctran/{sortcode}?limit=&offset=` — reader

`SELECT ... FROM PROCTRAN WHERE PROCTRAN_SORTCODE = ? ORDER BY PROCTRAN_DATE, PROCTRAN_TIME LIMIT ? OFFSET ?`.
`limit` defaults to 250000 (as `ProcessedTransactionResource`), `offset` to 0.

Response 200:

```json
{"success":"Y","failCode":"0","numberOfRecords":2,"records":[{...},{...}]}
```

| outcome | HTTP | success | failCode |
|---|---|---|---|
| success (possibly empty) | 200 | Y | 0 |
| limit < 1, offset < 0, bad sort code | 400 | N | V |
| SELECT failed | 500 | N | 2 |
