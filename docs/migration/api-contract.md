# CBSA core REST API contract

Service: `src/cbsa-core-service`, context path **`/webui-1.0`** (all paths below are relative to it).
Derived from every axios call in `src/bank-application-frontend/src` (env `REACT_APP_CUSTOMER_URL=/webui-1.0/banking/customer`,
`REACT_APP_ACCOUNT_URL=/webui-1.0/banking/account`), the legacy Liberty JAX-RS resources it used to call
(`src/webui/src/main/java/com/ibm/cics/cip/bankliberty/api/json/*Resource.java`), and the z/OS Connect payment API
(`src/zosconnect_artefacts/apis/makepayment`, `src/Z-OS-Connect-Payment-Interface`).

## Conventions
- JSON, UTF-8. Dates are ISO `yyyy-MM-dd` (the UI slices `substring(0,4)/(5,7)/(8,10)`).
- Customer numbers are serialised as **10-char zero-padded strings** (`"0000000001"`), account numbers as
  **8-char zero-padded strings** (`"00000001"`); path parameters accept unpadded numbers (`/banking/customer/1`).
- Money: JSON numbers with 2 decimals (`BigDecimal`, scale 2). Interest rate: number, 2 decimals.
- Error body (every non-2xx), produced by `common.error.GlobalExceptionHandler`:
  ```json
  {"errorMessage": "Customer number 42 not found.", "failCode": "1", "abendCode": "HWPT"}
  ```
  `errorMessage` always present (the UI `alert`s it on customer update); `failCode` = legacy COMMAREA fail code when one
  applies; `abendCode` only for abends (HTTP 500).
- Status codes: `200` read/update/delete OK, `201` created, `400` validation / business-rule rejection,
  `404` customer or account not found, `500` abend / data-store failure. Unknown path → 404, wrong verb → 405.

## Customer JSON
```json
{
  "id": "0000000001",
  "sortCode": "987654",
  "customerName": "Mr Ab Smith",
  "customerAddress": "1 High St, Leeds",
  "dateOfBirth": "1980-02-03",
  "customerCreditScore": "500",
  "customerCreditScoreReviewDate": "2026-02-01"
}
```
UI reads: `id`, `sortCode`, `customerName`, `customerAddress`, `dateOfBirth`, `customerCreditScore`, `customerCreditScoreReviewDate`.
`customerCreditScore` is a string (legacy `CustomerJSON` type).

## Account JSON
```json
{
  "id": "00000001",
  "sortCode": "987654",
  "customerNumber": "0000000001",
  "accountType": "ISA",
  "interestRate": 2.10,
  "overdraft": 0,
  "availableBalance": 100.00,
  "actualBalance": 100.00,
  "dateOpened": "2020-01-01",
  "lastStatementDate": "2025-12-01",
  "nextStatementDate": "2026-01-01"
}
```
UI reads all of the above (`accountType` trimmed, no trailing blanks).

## Customer endpoints (`/banking/customer`)

| Method & path | Request | 2xx response | Errors | Ticket |
|---|---|---|---|---|
| `POST /banking/customer` | `{"customerName","customerAddress","dateOfBirth":"yyyy-MM-dd","sortCode":"987654"}` | `201` Customer JSON (UI reads `id`) | `400` name/address/sortCode/DOB missing; `400 failCode T` "Customer title X is not valid"; `400` "Sortcode X not valid for this bank (987654)"; `400 failCode O/Y/Z` DOB rules; `500 failCode G` credit check failed; `500` counter/write failures | T-01 |
| `GET /banking/customer/{id}` | — | `200` Customer JSON; `0` = random customer (INQCUST GENERATE-RANDOM-CUSTOMER: `FUNCTION RANDOM(EIBTASKN)` seeded with `cbsa.random.seed` + task number, COMPUTE evaluated as the legacy GnuCOBOL runtime does per `cbsa.inqcust.random-arithmetic`), `9999999999` = last customer | `400` negative id; `404 failCode 1` "Customer number {id} not found." | T-02 |
| `GET /banking/customer/name?name=&limit=&offset=&countOnly=` | UI sends `limit=10` | `200 {"numberOfCustomers": n, "customers": [Customer…]}` (`countOnly=true` → only `numberOfCustomers`) | `404` no customer matches (UI shows "no results"); `400` missing name | T-02 |
| `GET /banking/customer?limit=&offset=&countOnly=` | — | `200 {"numberOfCustomers", "customers"}` | — | T-02 |
| `PUT /banking/customer/{id}` | `{"customerName","customerAddress","sortCode", "dateOfBirth"?, "creditScore"?}` — only name and address are applied (UPDCUST) | `200` updated Customer JSON | `400` name null / `failCode T` title / sortCode invalid / address null / `failCode 4` both blank; `404 failCode 1` | T-03 |
| `DELETE /banking/customer/{id}` | — | `200` deleted Customer JSON (all its accounts are deleted first) | `404 failCode 1`; `500 abendCode HWPT/WPV6/WPV7` | T-04 |

## Account endpoints (`/banking/account`)

| Method & path | Request | 2xx response | Errors | Ticket |
|---|---|---|---|---|
| `POST /banking/account` | `{"customerNumber","accountType","interestRate","overdraft","sortCode","dateOpened"?,"availableBalance"?,"actualBalance"?}` (UI sends all but the balances; `dateOpened` ignored — CREACC uses today; optional `availableBalance`/`actualBalance` = COMMAREA `COMM-AVAIL-BAL`/`COMM-ACT-BAL`, written to the new account as passed, default `0`) | `201` Account JSON (UI reads `id`) | `400 failCode A` "Account type X is not valid"; `400` interest rate < 0, > 9999.99 or > 2 dp; `400` sortCode invalid; `400` overdraft < 0; `404 failCode 1` customer not found; `400 failCode 8` "Customer number N cannot have more than ten accounts."; `500 failCode 3/5/7/9` | T-05 |
| `GET /banking/account/{id}` | — | `200` Account JSON | `404` "Account number {id} not found." | T-06 |
| `GET /banking/account/retrieveByCustomerNumber/{customerNumber}?countOnly=` | — | `200 {"customerFound": true, "numberOfAccounts": n, "accounts": [Account…]}` (ordered by account number; `numberOfAccounts` is what the UI checks before deleting a customer) | `404 failCode 1` customer not found | T-06 |
| `GET /banking/account?limit=&offset=&countOnly=` | — | `200 {"numberOfAccounts", "accounts"}` | — | T-06 |
| `PUT /banking/account/{id}` | Full Account JSON as the UI sends (`id, customerNumber, sortCode, accountType, interestRate, overdraft, dateOpened, lastStatementDate, nextStatementDate, availableBalance, actualBalance`); only `accountType`, `interestRate`, `overdraft` are applied (UPDACC); `accountType` follows UPDACC: any value not blank and not starting with a space is written as sent (first 8 characters, case kept) | `200` updated Account JSON | `400` blank type (or leading space), rate rules, sortCode; `404` not found | T-07 |
| `DELETE /banking/account/{id}` | — | `200` deleted Account JSON | `404 failCode 1`; `500 failCode 3` / `abendCode HWPT` | T-08 |
| `PUT /banking/account/debit/{id}` | `{"amount": 10.00}` (positive; debits) | `200` Account JSON with new balances | `400` amount missing/≤ 0; `404 failCode 1`; `500 failCode 2` | T-09 |
| `PUT /banking/account/credit/{id}` | `{"amount": 10.00}` | `200` Account JSON | as debit | T-09 |
| `PUT /banking/account/transfer/{id}` | `{"targetAccount": 2, "amount": 10.00}` | `200 {"fromAccount": Account, "toAccount": Account, "amount": 10.00}` | `400` "Cannot transfer to the same account" / `failCode 4` amount ≤ 0; `404 failCode 1` from not found; `404 failCode 2` target not found; `500 failCode 3` | T-10 |

Teller debit/credit (`/debit`, `/credit`) behave like DBCRFUN with a non-payment facility type: no funds check,
PROCTRAN `DEB`/`CRE`.

## Bank identity (`common`, already implemented)
| `GET /banking/sortCode` | `200 {"sortCode": "987654"}` |
|---|---|
| `GET /banking/companyName` | `200 {"companyName": "CICS Bank Sample Application"}` |

## z/OS Connect payment API mirror (`/makepayment/dbcr`) — T-09
Mirrors `zosconnect_artefacts/apis/makepayment` (`PUT {basePath}/makepayment/dbcr`), called by
`Z-OS-Connect-Payment-Interface` (`ConnectionInfo.getAddressAndPort() + "/makepayment/dbcr"`). COMMAREA semantics:
**always HTTP 200** (unless the body is malformed → 400) with success/fail in the body.

Request:
```json
{"PAYDBCR": {
  "CommAccno": "00000001", "CommAmt": -25.00, "mSortC": 987654, "CommAvBal": 0, "CommActBal": 0,
  "CommOrigin": {"CommApplid": "PAYAPP", "CommUserid": "ACME", "CommFacilityName": "        ",
                 "CommNetwrkId": "        ", "CommFaciltype": 496, "Fill0": "    "},
  "CommSuccess": " ", "CommFailCode": " "}}
```
Response: same structure with `CommAvBal`, `CommActBal` set to the new balances, `CommSuccess` `"Y"`/`"N"` and
`CommFailCode`: `"0"` ok, `"1"` account not found, `"2"` data-store error, `"3"` insufficient funds, `"4"` MORTGAGE/LOAN
account cannot take payments. `CommFaciltype` 496 = payment (PDR/PCR); anything else = teller (DEB/CRE).
Negative `CommAmt` = debit. PROCTRAN desc for payments = `CommApplid`(8) + `CommUserid`(1:6).

## z/OS Connect customer-services APIs
The legacy `/inqcustz`, `/inqaccz`, `/inqacccz`, `/crecust`, `/creacc`, `/updcust`, `/updacc`, `/delcus`, `/delacc`
z/OS Connect APIs are **not** mirrored; `Z-OS-Connect-Customer-Services-Interface` (T-14) is re-pointed at the
`/banking/*` endpoints above and maps its existing form/JSON classes onto them.

## Optional read APIs (T-12)
| `GET /banking/processedTransaction?limit=&offset=` | `200 {"numberOfProcessedTransactions", "processedTransactions": [{"sortCode","accountNumber","date","time","reference","type","description","amount"}]}` |
|---|---|
| `GET /banking/abend?limit=` | `200 [AbendEvent…]` (abends raised by the service) |
| `POST /banking/admin/abend` | ABNDPROC (only when `cbsa.abndproc.endpoint.enabled=true`, header `X-CBSA-Admin-Token` = `cbsa.abndproc.endpoint.token`, else `403`; absent → `404`). Body = ABNDINFO COMMAREA `{"utimeKey","taskNoKey","applid","tranid","date","time","code","program","respCode","resp2Code","sqlCode","freeform"}`; `201` the record as written to ABNDFILE (PIC widths applied), `409` duplicate key (DUPREC) |
| `GET /banking/admin/abend` | ABNDFILE records in key order (same gating) |

`GET /banking/processedTransaction` is served by `proctran.ProcessedTransactionController` (port of Liberty
`ProcessedTransactionResource`): rows of this bank's sort code ordered by `PROCTRAN_DATE, PROCTRAN_TIME`
(then `PROCTRAN_REF, PROCTRAN_NUMBER`); `limit` defaults to 250000 and `offset` to 0, negative values are `400`.
Each row also carries `timestamp`, and the description-decoded Liberty fields where the type has them: `customer`,
`customerName`, `dateOfBirth` (OCC/ICC/ODC/IDC), `accountType`, `lastStatement`, `nextStatement` (OCA/ICA/ODA/IDA),
`targetSortcode`, `targetAccount` (TFR). `date`/`time` are ISO (`2026-01-10`, `09:00:00`); the Liberty-only date
fields keep Liberty's `DateFormat` MEDIUM rendering under `Locale.US` (`timestamp` e.g. `Jan 10, 2026, 9:00:00 AM`,
decoded dates e.g. `Feb 1, 1980`). The body also keeps the Liberty keys `numberOfProcessedTransactionRecords` and
`success: "Y"`. An unreadable PROCTRAN table returns `500 {"errorMessage":"Proctran DB2 table not accessible. Please contact your system administrator."}`.

### Task number (EIBTASKN) and `PROCTRAN_REF`
Every HTTP request runs as one CICS task (`TaskNumberSupplier.inTask`, `CicsTaskFilter`): the first use of the task
number in a request allocates the next number of a region-wide counter, and every PROCTRAN row written by that request
carries it as `PROCTRAN_REF` (12 digits). CRECUST's credit agencies run as child tasks EIBTASKN + 1..5 and later
tasks are numbered after them.
