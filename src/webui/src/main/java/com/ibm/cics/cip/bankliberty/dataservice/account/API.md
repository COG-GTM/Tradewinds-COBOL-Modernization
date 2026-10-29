# Account data service (`dataservice.account`)

REST replacement for the `EXEC SQL` against ACCOUNT / CONTROL / PROCTRAN in
INQACC, INQACCCU, CREACC, UPDACC, DELACC and BANKDATA. Hosted in the Liberty
web tier: all paths are relative to `/banking` (`@ApplicationPath("banking")`).

Classes: `AccountDataResource` (`/data/account`), `AccountAdminResource`
(`/data/admin/account`), `AccountDataService` (validation and JSON/status
mapping), `AccountDao` (JDBC; one atomic transaction per COBOL unit of work).

## Conventions

* **Content type**: `application/json` both ways (UTF-8).
* **Keys** (`sortcode`, `accountNumber`, `customerNumber`, `taskRef`) are
  digits, sent as JSON strings or path/query values. Shorter values are
  zero-padded to the PIC 9 width (6 / 8 / 10 / 12), exactly like a COBOL MOVE,
  so `"1"` and `"00000001"` are the same account. Non-digits or too many
  digits: `400`, `failCode "V"`.
* **Decimals** (`interestRate`, balances) may be JSON numbers or strings; they
  are truncated (not rounded) to 2 places, matching a MOVE into `V99`.
  Responses always return them as strings with exactly 2 places, e.g.
  `"-1234.56"`, `"0.00"`.
* **Dates in requests** (reset only) must be real calendar dates: `31022021`
  or `2021-02-30` are rejected with `400 V`, never adjusted.
* **Dates** in responses are `DDMMYYYY` strings — the layout of the COMMAREA
  date groups (`*-OPENED`, `*-LAST-STMT-DT`, `*-NEXT-STMT-DT`). Null dates
  come back as `"00000000"`.
* **accountType** is returned blank-padded to 8 characters (`"ISA     "`).
* **taskRef** = the caller's `EIBTASKN`, written to `PROCTRAN_REF`
  zero-padded to 12. **The COBOL caller must always send it**; if omitted the
  Liberty task number (`BankRuntime.taskNumber()`) is used, which is not the
  COBOL task.

### Account object

```json
{
  "eyeCatcher": "ACCT",
  "customerNumber": "0000000042",
  "sortcode": "987654",
  "accountNumber": "00000042",
  "accountType": "ISA     ",
  "interestRate": "2.10",
  "opened": "29102026",
  "overdraftLimit": 250,
  "lastStatementDate": "29102026",
  "nextStatementDate": "28112026",
  "availableBalance": "10.00",
  "actualBalance": "10.00"
}
```

Maps 1:1 onto the COMMAREA fields: `COMM-EYE`/`INQACC-EYE`, `*-CUSTNO`,
`*-SCODE`, `*-ACCNO`, `*-ACC-TYPE`, `*-INT-RATE`, `*-OPENED`,
`*-OVERDRAFT`, `*-LAST-STMT-DT`, `*-NEXT-STMT-DT`, `*-AVAIL-BAL`,
`*-ACTUAL-BAL`.

### Envelope

Success:

```json
{ "success": "Y", "failCode": " ", "account": { ... } }
```

Failure:

```json
{ "success": "N", "failCode": "1", "abendCode": "HWPT", "message": "..." }
```

* `success` / `failCode` go straight into the COMMAREA success flag and fail
  code (`failCode` is `" "` when the legacy program had no code for the path).
* `abendCode` is present **only** where the legacy program issued
  `EXEC CICS ABEND`. To stay behaviour-identical the COBOL caller should
  `EXEC CICS ABEND ABCODE(abendCode)` when it is present. The data service has
  already rolled back its database work and backed out the CICS UOW
  (`BankRuntime.rollback()`) before responding.
* `message` is diagnostic text only; do not parse it. For 5xx it is a fixed
  generic string (details go to the server log only).

### Transactions

Every write endpoint is one database transaction: commit on success, rollback
on **any** failure. In particular, if the PROCTRAN insert fails, the ACCOUNT
insert/delete and the CONTROL updates are rolled back too (legacy:
abend `HWPT` → CICS backout). On any write failure the resource also calls
`BankRuntime.rollback()` so other recoverable resources in the CICS UOW are
backed out, matching `SYNCPOINT ROLLBACK`.

Create and reset first take a transaction-scoped advisory lock
`pg_advisory_xact_lock(0x41434354, <sortcode>)`, so account-number
allocation and a bulk reset for the same sortcode never interleave (the
CONTROL rows cannot be the lock target because reset deletes them).

### Security

These endpoints sit behind the same Liberty application as the existing
`/banking` resources and, like them, perform no authorization of their own;
CREACC/INQACCCU customer checks still run in COBOL. They are intended for
the in-region COBOL callers and BANKDATA only. Deployment must restrict
`/banking/data/*` (and especially `/banking/data/admin/*`) with a Liberty
`<security-constraint>` / role mapping to the CICS service identity, and must
not expose them through the public UI route.

---

## 1. Inquire account — INQACC

`GET /data/account/{sortcode}/{number}`

Replaces INQACC `READ-ACCOUNT-DB2` (`ACC-CURSOR` OPEN / `FETCH-DATA` / CLOSE).
If `{number}` is `99999999` this behaves exactly like `/last` below
(INQACC `A010`: `IF INQACC-ACCNO = 99999999 PERFORM READ-ACCOUNT-LAST`).

`GET /data/account/{sortcode}/last`

Replaces INQACC `READ-ACCOUNT-LAST` / `GET-LAST-ACCOUNT-DB2`: highest
`ACCOUNT_NUMBER` for the sortcode (`ORDER BY ACCOUNT_NUMBER DESC LIMIT 1`).

Request body: none.

| HTTP | success | failCode | abendCode | Meaning / legacy behaviour |
|---|---|---|---|---|
| 200 | Y | `" "` | – | Found; `account` populated → `INQACC-SUCCESS='Y'` |
| 404 | N | `1` | – | `/{number}`: no row (SQLCODE +100) → `INQACC-SUCCESS='N'` (INQACC has no fail-code field) |
| 404 | N | `1` | `HNCS` | `/last`: no accounts for sortcode. Legacy `GLAD010` abended HNCS on SQLCODE≠0 |
| 400 | N | `V` | – | Bad sortcode / number |
| 500 | N | `" "` | `HRAC` | DB failure on `/{number}` (legacy `RAD010`/`FD010` abend HRAC) |
| 500 | N | `" "` | `HNCS` | DB failure on `/last` (legacy `GLAD010` abend HNCS) |

## 2. Accounts for a customer — INQACCCU

`GET /data/account/{sortcode}?customerNumber={customerNumber}`

Replaces INQACCCU `READ-ACCOUNT-DB2` / `FETCH-DATA`: at most **20** rows
(`ACCOUNT-DETAILS OCCURS 1 TO 20`), `WHERE ACCOUNT_CUSTOMER_NUMBER = ? AND
ACCOUNT_SORTCODE = ?`, ordered by `ACCOUNT_NUMBER` ascending (the legacy
cursor had no ORDER BY; ascending account number makes the order
deterministic).

Response (200):

```json
{
  "success": "Y", "failCode": " ",
  "customerNumber": "0000000042",
  "numberOfAccounts": 2,
  "accounts": [ { account }, { account } ]
}
```

`numberOfAccounts` → `NUMBER-OF-ACCOUNTS`; `accounts[i]` → `ACCOUNT-DETAILS(i)`.

**`CUSTOMER-CHECK` (the `INQCUST` LINK against VSAM) stays in COBOL** and must
run before this call; this endpoint only short-circuits the two values
`CUSTOMER-CHECK` rejects without a lookup.

| HTTP | success | failCode | extra | Meaning / legacy behaviour |
|---|---|---|---|---|
| 200 | Y | `" "` | | Zero or more accounts → `COMM-SUCCESS='Y'` |
| 404 | N | `1` | `customerFound:"N"`, `numberOfAccounts:0` | Customer number 0 or 9999999999 (`CC010`) |
| 400 | N | `V` | | Bad / missing sortcode or customerNumber |
| 500 | N | `3` | `customerFound:"N"`, `numberOfAccounts:0` | DB failure (legacy OPEN=2 / FETCH=3 / CLOSE=4, each followed by SYNCPOINT ROLLBACK; collapsed to `3` because JDBC has no separate open/close) |

## 3. Create account — CREACC

`POST /data/account`

Replaces CREACC `FIND-NEXT-ACCOUNT` (FNA010) + `WRITE-ACCOUNT-DB2` (WAD010) +
`WRITE-PROCTRAN-DB2` (WPD010), all in **one** transaction.

Request:

```json
{
  "sortcode": "987654",
  "customerNumber": "42",
  "accountType": "ISA",
  "interestRate": "2.10",
  "overdraftLimit": 250,
  "availableBalance": "10.00",
  "actualBalance": "10.00",
  "taskRef": "123",
  "accountNumber": "00000043"
}
```

| Field | Required | Notes |
|---|---|---|
| sortcode | yes | `SORTCODE` |
| customerNumber | yes | `COMM-CUSTNO` |
| accountType | yes | `COMM-ACC-TYPE`; must **start with** `ISA`, `MORTGAGE`, `SAVING`, `CURRENT` or `LOAN` (ATC010 prefix test on the padded 8 bytes, so `ISAPLUS` is valid) |
| interestRate | no (0) | `COMM-INT-RT`, 0–9999.99 |
| overdraftLimit | no (0) | `COMM-OVERDR-LIM`, 0–99999999 |
| availableBalance / actualBalance | no (0) | `COMM-AVAIL-BAL` / `COMM-ACT-BAL` |
| taskRef | should | `EIBTASKN` |
| accountNumber | no | see allocation below |

What the transaction does:

1. `SELECT … FOR UPDATE` CONTROL row `<sortcode>-ACCOUNT-LAST`
   (e.g. `987654-ACCOUNT-LAST`).
   * `accountNumber` **omitted** (recommended): allocate `ACCOUNT-LAST + 1`
     and `UPDATE` the row to it — exactly FNA010.
   * `accountNumber` **supplied**: use it as-is; `ACCOUNT-LAST` is advanced to
     it only if it is higher.
2. `SELECT … FOR UPDATE` `<sortcode>-ACCOUNT-COUNT`, `UPDATE` to `+1`.
3. `INSERT ACCOUNT`: eyecatcher `ACCT`, opened = last statement = today,
   next statement = today + 30 days (WAD010 `INTEGER-OF-DATE + 30`).
4. `INSERT PROCTRAN`: eyecatcher `PRTR`, `PROCTRAN_TYPE='OCA'`,
   `PROCTRAN_DATE`=today, `PROCTRAN_TIME`=HHMMSS now, `PROCTRAN_REF`=taskRef
   (12 digits), `PROCTRAN_AMOUNT=0`, `PROCTRAN_DESC` (40 bytes):

   | Offset (1-based) | Len | Content |
   |---|---|---|
   | 1 | 10 | customer number, zero-padded |
   | 11 | 8 | account type, blank-padded |
   | 19 | 8 | last statement `DDMMYYYY` |
   | 27 | 8 | next statement `DDMMYYYY` |
   | 35 | 6 | spaces (WPD010 `MOVE SPACES TO HV-PROCTRAN-DESC(35:6)`) |

   e.g. `0000000042ISA     2910202628112026      `.

Response: `201` with the created account (`COMM-NUMBER` = `account.accountNumber`,
`COMM-OPENED`, `COMM-LAST-STMT-DT`, `COMM-NEXT-STMT-DT` from the account).

| HTTP | success | failCode | abendCode | Meaning / legacy behaviour |
|---|---|---|---|---|
| 201 | Y | `" "` | – | Created → `COMM-SUCCESS='Y'`, `COMM-FAIL-CODE=' '` |
| 400 | N | `A` | – | Account type not allowed (ATC010) |
| 400 | N | `V` | – | Missing / malformed field |
| 409 | N | `7` | – | `ACCOUNT` insert hit duplicate key (WAD010 SQLCODE≠0 → `7`) |
| 500 | N | `7` | – | Other `ACCOUNT` insert failure, or account number > 99999999 |
| 500 | N | `" "` | `HNCS` | CONTROL row missing / SELECT or UPDATE failed (FNA010 abend HNCS) |
| 500 | N | `" "` | `HWPT` | PROCTRAN insert failed (WPD010 abend HWPT); everything rolled back |

**What stays in COBOL (CREACC):** the `INQCUST` LINK (fail code `1`), the
`INQACCCU` count check (`> 9` accounts → fail code `8`; INQACCCU now calls
endpoint 2), and `ENQ-NAMED-COUNTER` / `DEQ-NAMED-COUNTER` (`EXEC CICS ENQ/DEQ
RESOURCE('CBSAACCT'||sortcode)`, fail codes `3` / `5`).

Note on the named counter: the task brief mentions `HBNKACCT`, but this
CREACC never calls `GET COUNTER`; `NCS-ACC-NO-NAME` (`CBSAACCT` + sortcode)
is only used as the ENQ resource, and the account number comes from the
CONTROL row. So **the COBOL should omit `accountNumber`** and let the endpoint
allocate from CONTROL (keeping its ENQ/DEQ around the call is harmless; the
`FOR UPDATE` row lock already serialises allocation). `accountNumber` exists
only for callers that allocate numbers themselves.

Deliberate difference: legacy CREACC returned fail code `7` via `EXEC CICS
RETURN`, which *committed* the CONTROL increments even though the ACCOUNT
insert failed (leaving a gap). Here the whole unit of work is rolled back.

## 4. Update account — UPDACC

`PUT /data/account/{sortcode}/{number}`

Replaces UPDACC `UPDATE-ACCOUNT-DB2` (UAD010): SELECT (here `FOR UPDATE`),
validate, `UPDATE ACCOUNT SET ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE,
ACCOUNT_OVERDRAFT_LIMIT`. No PROCTRAN row (UPDACC writes none).

Request:

```json
{ "accountType": "LOAN", "interestRate": "3.50", "overdraftLimit": 999 }
```

All three required (they are always present in the UPDACC COMMAREA).
`accountType` is **not** checked against the CREACC list — only that it is not
blank and does not start with a space (`COMM-ACC-TYPE = SPACES OR
COMM-ACC-TYPE(1:1) = ' '`), max 8 chars, stored blank-padded.

Response: `200` with the account **after** the update (UPDACC moves the HV
fields back to the COMMAREA).

UPDACC's COMMAREA has no fail-code field; the COBOL only sets
`COMM-SUCCESS`. `failCode` below is informational.

| HTTP | success | failCode | Meaning / legacy behaviour |
|---|---|---|---|
| 200 | Y | `" "` | Updated → `COMM-SUCCESS='Y'` |
| 404 | N | `1` | Account not found (SELECT SQLCODE≠0). Checked **before** type validation, as in UAD010 |
| 400 | N | `A` | Blank / leading-space account type (row unchanged) |
| 400 | N | `V` | Missing interestRate / overdraftLimit, bad keys, type > 8 chars |
| 500 | N | `2` | SELECT failed |
| 500 | N | `3` | UPDATE failed (rolled back) |

## 5. Delete account — DELACC

`DELETE /data/account/{sortcode}/{number}?taskRef={EIBTASKN}`

Replaces DELACC `READ-ACCOUNT-DB2` (RAD010) + `DEL-ACCOUNT-DB2` (DADB010) +
`WRITE-PROCTRAN-DB2` (WPD010), all in one transaction.

Request body: none. `taskRef` query parameter should be supplied.

PROCTRAN row: eyecatcher `PRTR`, `PROCTRAN_TYPE='ODA'`, `PROCTRAN_AMOUNT` =
the deleted account's **actual balance**, date/time/ref as for CREACC,
`PROCTRAN_DESC`:

| Offset (1-based) | Len | Content |
|---|---|---|
| 1 | 10 | customer number |
| 11 | 8 | account type |
| 19 | 8 | last statement `DDMMYYYY` |
| 27 | 8 | next statement `DDMMYYYY` |
| 35 | 6 | `DELETE` |

e.g. `0000000042MORTGAGE0107202101082021DELETE`.

Response: `200` with the **deleted** account (→ `DELACC-EYE … DELACC-ACTUAL-BAL`).

| HTTP | success | failCode | abendCode | Meaning / legacy behaviour |
|---|---|---|---|---|
| 200 | Y | `" "` | – | Deleted → `DELACC-DEL-SUCCESS='Y'`, `DELACC-DEL-FAIL-CD=' '` |
| 404 | N | `1` | – | Not found (SQLCODE +100) → `DELACC-DEL-SUCCESS='N'`, `DELACC-DEL-FAIL-CD='1'` |
| 400 | N | `V` | – | Bad keys / taskRef |
| 500 | N | `3` | – | DELETE failed → `DELACC-DEL-FAIL-CD='3'` (rolled back) |
| 500 | N | `" "` | `HRAC` | SELECT failed (RAD010 abend HRAC) |
| 500 | N | `" "` | `HWPT` | PROCTRAN insert failed (WPD010 abend HWPT); delete rolled back |

The `failCode` maps to `DELACC-DEL-FAIL-CD`; DELACC leaves `DELACC-SUCCESS`
as `' '`.

## 6. Bulk reset — BANKDATA

`POST /data/admin/account/reset?sortcode={sortcode}[&lastAccountNumber=n][&numberOfAccounts=n]`

Replaces BANKDATA `DELETE-DB2-ROWS` (ACCOUNT + account CONTROL rows),
`POPULATE-ACC` (`INSERT ACCOUNT`) and the two `INSERT INTO CONTROL` at the end
of `PREMIERE`, in one transaction, using JDBC batch inserts (1,000 rows per
batch). Customer/VSAM generation stays in BANKDATA. BANKDATA is a batch job,
so it calls this over HTTP (not via CICS LINK).

Request body: JSON **array** of account objects (same field names as the
account object above). Per element:

| Field | Required | Notes |
|---|---|---|
| customerNumber | yes | |
| accountNumber | yes | |
| accountType | yes | 1–8 chars, stored as given (blank-padded) |
| interestRate, overdraftLimit, availableBalance, actualBalance | no (0) | |
| opened, lastStatementDate, nextStatementDate | yes | `DDMMYYYY` or `YYYY-MM-DD` |
| sortcode | no | if present must equal the query `sortcode` |
| eyeCatcher | no | default `ACCT` |

Steps:

1. `DELETE FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ?`
2. `DELETE FROM CONTROL` rows `<sortcode>-ACCOUNT-LAST` and
   `<sortcode>-ACCOUNT-COUNT`
3. Batch `INSERT ACCOUNT`
4. `INSERT CONTROL <sortcode>-ACCOUNT-LAST` = `lastAccountNumber` (default:
   highest account number in the body, 0 if empty) and
   `<sortcode>-ACCOUNT-COUNT` = `numberOfAccounts` (default: array size);
   `CONTROL_VALUE_STR` blank.

Response (200):

```json
{ "success": "Y", "failCode": " ", "sortcode": "987654", "accountsInserted": 2500 }
```

| HTTP | success | failCode | Meaning |
|---|---|---|---|
| 200 | Y | `" "` | Reset done |
| 400 | N | `V` | Body not an array / invalid element / sortcode mismatch (nothing changed) |
| 409 | N | `I` | Duplicate account number in the batch (whole reset rolled back) |
| 500 | N | `I` or `" "` | Insert or other DB failure (whole reset rolled back); legacy BANKDATA set RC=12 |

Deliberate difference: BANKDATA committed every ~1,000 customers; this endpoint
is all-or-nothing.
