# CBSA COBOL core — migration inventory

Source of truth: `src/base/cobol_src` (29 programs, ~27k lines), `src/base/cobol_copy` (37 copybooks),
`src/base/bms_src` (9 mapsets), DB2 DDL `etc/install/base/db2jcl/CRETB0*.jcl` / `CREI*.jcl`.
Target: `src/cbsa-core-service` (Java 21 / Spring Boot 3.3, H2 default, Postgres profile `postgres`).
See [program-map.md](program-map.md) for COBOL → Java mapping, [api-contract.md](api-contract.md) for REST,
[tickets.md](tickets.md) for the work breakdown.

## 1. Data stores

| Legacy store | Kind | Copybook(s) | Key | Target table |
|---|---|---|---|---|
| `CUSTOMER` file | VSAM KSDS | `CUSTOMER.cpy` (record), `CUSTCTRL.cpy` (control record, key 000000/9999999999) | sortcode 9(6) + number 9(10) | `CUSTOMER` (new, columns per shared contract) |
| `ACCOUNT` | DB2 table (CRETB01) | `ACCOUNT.cpy`, `ACCDB2.cpy` | `ACCTINDX` unique (sortcode, number); `ACCTCUST` (sortcode, customer) | `ACCOUNT` (verbatim) |
| `PROCTRAN` | DB2 table (CRETB02) | `PROCTRAN.cpy`, `PROCDB2.cpy`, `PROCISRT.cpy` | none (audit log) | `PROCTRAN` (verbatim) |
| `CONTROL` | DB2 table (CRETB03) | `CONTDB2.cpy`, `CONTROLI.cpy`, `ACCTCTRL.cpy` | `CONTINDX` unique (name) | `CONTROL` (verbatim) |
| `ABNDFILE` | VSAM KSDS | `ABNDINFO.cpy` | utime COMP-3 + task no | not migrated as a table; `AbendEvent` (see T-12) |
| Named counters `CBSACUST<sc>` / `CBSAACCT<sc>` | CICS NCS | `NEWCUSNO.cpy`, `NEWACCNO.cpy` | — | `CONTROL` rows via `CounterService` |

Canonical DDL: `src/cbsa-core-service/src/main/resources/schema.sql`. Canonical seed: `baseline/seed/seed.sql`.

### Field types (copybook → SQL → Java)
| Copybook | SQL | Java (`common.domain`) |
|---|---|---|
| `PIC 9(10)` customer number | `CHAR(10)` zero-padded | `long` (formatted by `CbsaFormats.customerNumber`) |
| `PIC 9(8)` account number | `CHAR(8)` zero-padded | `long` (`CbsaFormats.accountNumber`) |
| `PIC 9(6)` sort code | `CHAR(6)` | `String` |
| `PIC 9(8)` DDMMYYYY dates (REDEFINES DD/MM/YYYY) | `DATE` | `LocalDate` |
| `PIC 9(4)V99` interest rate | `DECIMAL(6,2)` | `BigDecimal` scale 2 |
| `PIC S9(10)V99` balances / amounts | `DECIMAL(12,2)` | `BigDecimal` scale 2 |
| `PIC 9(8)` overdraft | `INTEGER` | `int` |
| `PIC 999` credit score | `INTEGER` | `int` |
| `PIC X(n)` | `CHAR(n)` / `VARCHAR(n)` | `String` (trimmed on read; PROCTRAN_DESC kept positional) |

## 2. Programs

| Program | Lines | Kind | Purpose | Calls / links | Data |
|---|---|---|---|---|---|
| CRECUST | 1497 | business (LINK) | Create customer: title check, async credit check (5 agencies, `RUN TRANSID('OCR1'..'OCR5')` + `FETCH ANY`), DOB check, allocate number (ENQ + NCS), write VSAM, write PROCTRAN `OCC` | CRDTAGY1-5 via channel `CIPCREDCHANN`, ABNDPROC | CUSTOMER, PROCTRAN, NCS |
| CRDTAGY1..5 | ~273 each | async child | Credit agency stub: random DELAY 1-3s, then random score 1..998 into container | — | channel/container |
| INQCUST | 711 | business | Read customer by key; custno `0000000000` → random existing customer, `9999999999` → last customer | ABNDPROC | CUSTOMER |
| UPDCUST | 364 | business | Update name and/or address only (title validated); other fields ignored | — | CUSTOMER |
| DELCUS | 761 | business | Inquire customer, get accounts (INQACCCU), LINK DELACC for each, delete VSAM record, PROCTRAN `ODC` | INQCUST, INQACCCU, DELACC, ABNDPROC | CUSTOMER, ACCOUNT, PROCTRAN |
| CREACC | 1247 | business | Create account: customer must exist (INQCUST), max 10 accounts (INQACCCU), type check, allocate number (ENQ + CONTROL `<sc>-ACCOUNT-LAST/-COUNT`), insert ACCOUNT, PROCTRAN `OCA` | INQCUST, INQACCCU, ABNDPROC | ACCOUNT, CONTROL, PROCTRAN |
| INQACC | 1002 | business | Read account by number; `99999999` → last account | ABNDPROC | ACCOUNT |
| INQACCCU | 882 | business | Customer check (INQCUST) + cursor of all accounts for customer (max 20 in COMMAREA) | INQCUST | ACCOUNT, CUSTOMER |
| UPDACC | 406 | business | Update account type, interest rate, overdraft (type must be non-blank) | — | ACCOUNT |
| DELACC | 649 | business | Read + delete account, PROCTRAN `ODA` (desc footer 'DELETE', amount = actual balance) | ABNDPROC | ACCOUNT, PROCTRAN |
| DBCRFUN | 861 | business | Debit/credit an account (teller or payment API); payment rules for MORTGAGE/LOAN and insufficient funds; PROCTRAN DEB/CRE/PDR/PCR | ABNDPROC | ACCOUNT, PROCTRAN |
| XFRFUN | 1924 | business | Transfer between two accounts at this bank, deadlock-avoiding update order (lower account first), SYNCPOINT ROLLBACK on failure, PROCTRAN `TFR` | ABNDPROC | ACCOUNT, PROCTRAN |
| GETCOMPY | 43 | utility | Returns `'CICS Bank Sample Application'` | — | — |
| GETSCODE | 46 | utility | Returns sort code (SORTCODE.cpy `987654`) | — | — |
| ABNDPROC | 176 | utility | Writes ABNDINFO record to VSAM `ABNDFILE` | — | ABNDFILE |
| BANKDATA | 1463 | batch | Generates customers + accounts from PARM `start,end,step,seed` using name/town/street tables and FUNCTION RANDOM; writes control records | — | CUSTOMER, ACCOUNT, CONTROL |
| BNKMENU | 1314 | 3270 screen (`OMEN`, map BNK1MAI) | Main menu: options 1-A route via `RETURN TRANSID` to the BNK1* transactions; PF3/PF12 exit, CLEAR | — | — |
| BNK1DCS | 2056 | screen `ODCS` (BNK1DCM) | Display / delete / update customer | INQCUST, DELCUS, UPDCUST | — |
| BNK1CCS | 1660 | screen `OCCS` (BNK1CCM) | Create customer | CRECUST | — |
| BNK1CAC | 1301 | screen `OCAC` (BNK1CAM) | Create account | CREACC | — |
| BNK1UAC | 1408 | screen `OUAC` (BNK1UAM) | Update account | INQACC, UPDACC | — |
| BNK1DAC | 1161 | screen `ODAC` (BNK1DAM) | Display / delete account | INQACC, DELACC | — |
| BNK1CRA | 1169 | screen `OCRA` (BNK1CDM) | Credit / debit funds | DBCRFUN | — |
| BNK1TFN | 1227 | screen `OTFN` (BNK1TFM) | Transfer funds | XFRFUN | — |
| BNK1CCA | 955 | screen `OCCA` (BNK1ACC) | List accounts for customer | INQACCCU | — |

## 3. COMMAREA layouts (LINK interfaces)

| Copybook | Program | Key fields in | Key fields out |
|---|---|---|---|
| `CRECUST.cpy` | CRECUST | COMM-NAME X(60), COMM-ADDRESS X(160), COMM-DATE-OF-BIRTH 9(8) DDMMYYYY | COMM-KEY (sortcode+number), COMM-CREDIT-SCORE 999, COMM-CS-REVIEW-DATE, COMM-SUCCESS, COMM-FAIL-CODE |
| `CREACC.cpy` | CREACC | COMM-CUSTNO 9(10), COMM-ACC-TYPE X(8), COMM-INT-RT 9(4)V99, COMM-OVERDR-LIM 9(8), COMM-AVAIL-BAL / COMM-ACT-BAL | COMM-KEY (sortcode+number), COMM-OPENED, COMM-LAST/NEXT-STMT-DT, COMM-SUCCESS, COMM-FAIL-CODE |
| `INQCUST.cpy` (`INQCUSTZ` for z/OS Connect) | INQCUST | INQCUST-CUSTNO 9(10) | full customer, INQCUST-INQ-SUCCESS, INQCUST-INQ-FAIL-CD |
| `INQACC.cpy` (`INQACCZ`) | INQACC | INQACC-ACCNO 9(8) | full account, INQACC-SUCCESS |
| `INQACCCU.cpy` (`INQACCCZ`, `DELACCZ`) | INQACCCU | CUSTOMER-NUMBER 9(10) | NUMBER-OF-ACCOUNTS, CUSTOMER-FOUND, ACCOUNT-DETAILS OCCURS 1..20, COMM-SUCCESS, COMM-FAIL-CODE |
| `UPDCUST.cpy` | UPDCUST | COMM-CUSTNO, COMM-NAME, COMM-ADDR | full customer, COMM-UPD-SUCCESS, COMM-UPD-FAIL-CD |
| `UPDACC.cpy` | UPDACC | COMM-ACCNO, COMM-ACC-TYPE, COMM-INT-RATE, COMM-OVERDRAFT | full account, COMM-SUCCESS |
| `DELCUS.cpy` | DELCUS | COMM-CUSTNO | deleted customer, COMM-DEL-SUCCESS, COMM-DEL-FAIL-CD |
| `DELACC.cpy` | DELACC | DELACC-ACCNO | deleted account, DELACC-DEL-SUCCESS, DELACC-DEL-FAIL-CD |
| `PAYDBCR.cpy` | DBCRFUN | COMM-ACCNO X(8), COMM-AMT S9(10)V99 (negative = debit), COMM-ORIGIN (applid, userid, facility name, netid, COMM-FACILTYPE; 496 = payment API) | COMM-AV-BAL, COMM-ACT-BAL, COMM-SUCCESS, COMM-FAIL-CODE |
| `XFRFUN.cpy` | XFRFUN | COMM-FACCNO/FSCODE, COMM-TACCNO/TSCODE, COMM-AMT | COMM-FAVBAL/FACTBAL/TAVBAL/TACTBAL, COMM-SUCCESS, COMM-FAIL-CODE |
| `GETCOMPY.cpy` / `GETSCODE.cpy` | GETCOMPY / GETSCODE | — | company-name X(40) / SORTCODE X(6) |
| `NEWCUSNO.cpy` / `NEWACCNO.cpy` | (counter helpers) | function G/R/C | number, success, fail code |
| `PROCISRT.cpy` | (PROCTRAN insert helper) | function 1-7 + per-type struct | — |
| `ABNDINFO.cpy` | ABNDPROC | applid, tranid, date, time, code, program, resp/resp2/sqlcode, freeform X(600) | — |
| `BNK1DDM.cpy`, `BANKMAP.cpy`, `CUSTMAP.cpy` | BMS symbolic maps | screen fields | — |

## 4. Business rules (testable assertions)

### Customer (CRECUST / UPDCUST / INQCUST / DELCUS)
- R-C1 Title = first space-delimited word of name ∈ {Professor, Mr, Mrs, Miss, Ms, Dr, Drs, Lord, Sir, Lady} or empty; else fail `T` (CRECUST, UPDCUST).
- R-C2 DOB: year < 1601 → fail `O`; invalid date (CEEDAYS) → fail `Z`; age > 150 → fail `O`; DOB in the future → fail `Y`.
- R-C3 Credit check: 5 agencies run in parallel; each returns score 1..998 after a 1-3 s delay; customer score = SUM / number of agencies that answered (integer division). Review date = today + random(1..20) days. If no agency answers → score 0, review date = today, fail `G` (customer NOT created).
- R-C4 Customer number = NCS `CBSACUST` + 1 (under ENQ); on write failure the counter is rolled back. PROCTRAN `OCC`, number `00000000`, desc = sortcode(6)+custno(10)+name(1:14)+DOB `DD/MM/YYYY`, amount 0.
- R-C5 INQCUST: custno 0 → random existing customer (retry until found); custno 9999999999 → highest customer; not found → fail `1`.
- R-C6 UPDCUST: only name and address change; a blank (or leading-space) name keeps the old name, blank address keeps the old address; both blank → fail `4`; not found → fail `1`; rewrite error → fail `3`.
- R-C7 DELCUS: customer must exist; every account of the customer is deleted first via DELACC (each writes `ODA`), then the customer record is deleted and PROCTRAN `ODC` written (same desc layout as `OCC`). PROCTRAN write failure → abend `HWPT`.

### Account (CREACC / INQACC / INQACCCU / UPDACC / DELACC)
- R-A1 Account type must start with ISA, MORTGAGE, SAVING, CURRENT or LOAN; else fail `A`.
- R-A2 Customer must exist (INQCUST) else fail `1`; customer may hold at most 10 accounts (`NUMBER-OF-ACCOUNTS > 9` → fail `8`); counting error → fail `9`.
- R-A3 Account number = CONTROL `<sc>-ACCOUNT-LAST` + 1 (CONTROL `<sc>-ACCOUNT-COUNT` + 1) under ENQ `CBSAACCT<sc>`; ENQ failure → fail `3`, CONTROL update failure → fail `5`, insert failure → fail `7`.
- R-A4 New account: opened = today, last statement = today, next statement = today + 30 days (Feb: +28, +1 in leap years per the Gregorian rule), balances as supplied (UI sends 0). PROCTRAN `OCA`, desc = custno(10)+type(8)+last-stmt DDMMYYYY+next-stmt DDMMYYYY+6 spaces.
- R-A5 INQACC: accno 99999999 → last account; not found → `INQACC-SUCCESS = 'N'`.
- R-A6 INQACCCU: customer not found → fail `1` (CUSTOMER-FOUND = 'N'); cursor/DB errors → `2`/`3`/`4`; returns accounts ordered by account number.
- R-A7 UPDACC: blank account type → `COMM-SUCCESS = 'N'`; only type, interest rate and overdraft are updated; not found → 'N'.
- R-A8 DELACC: not found → fail `1`; delete error → fail `3`; PROCTRAN `ODA` desc footer `DELETE`, amount = actual balance; PROCTRAN failure → abend `HWPT`.
- R-A9 (web layer, legacy AccountsResource) interest rate 0.00..9999.99 with ≤ 2 decimals; sort code must equal 987654; overdraft ≥ 0.

### Money movement (DBCRFUN / XFRFUN)
- R-M1 DBCRFUN: amount < 0 = debit, ≥ 0 = credit. Account not found → fail `1`; other read error → `2`.
- R-M2 Payment API (COMM-FACILTYPE = 496): MORTGAGE or LOAN accounts are rejected → fail `4`; debit making available balance < 0 → fail `3` (insufficient funds). Teller debits (other facility types) have no funds check.
- R-M3 Both available and actual balance += amount. PROCTRAN teller: `DEB` 'COUNTER WTHDRW' / `CRE` 'COUNTER RECVED'; payment: `PDR` / `PCR` with desc = COMM-ORIGIN(1:14). Update/PROCTRAN failure → SYNCPOINT ROLLBACK, fail `2`.
- R-M4 XFRFUN: amount ≤ 0 → fail `4`; from account not found → fail `1`; to account not found → fail `2` (with rollback of the from update); other DB error → `3`; from == to → abend `SAME`. Accounts are updated lowest-number-first to avoid deadlock. No insufficient-funds check. One PROCTRAN `TFR` row on the FROM account: desc = 'TRANSFER' padded to 26 + to-sortcode(6) + to-account(8), amount = transfer amount.

### Screen flows (BNKMENU / BNK1*)
- R-S1 Menu options: 1 display customer (ODCS), 2 display account (ODAC), 3 create customer (OCCS), 4 create account (OCAC), 5 update account (OUAC), 6 credit/debit (OCRA), 7 transfer (OTFN), A list accounts for customer (OCCA); anything else → "Invalid selection" message. PF3/PF12 → end session, CLEAR → clear screen, PA keys ignored.
- R-S2 Each BNK1* program validates its input fields (numeric checks, required fields, Y/N confirmations, amount format) before LINKing to the business program and maps COMMAREA fail codes to screen messages.

## 5. PROCTRAN transaction types (PROCTRAN.cpy level-88)

| Code | Meaning | Written by |
|---|---|---|
| CHA / CHF / CHI / CHO | cheque acknowledged / failure / paid in / paid out | (not written by base programs) |
| CRE / DEB | counter credit / debit | DBCRFUN |
| PCR / PDR | payment credit / debit (facility type 496) | DBCRFUN |
| TFR | transfer | XFRFUN |
| OCC / ODC | branch create / delete customer | CRECUST / DELCUS |
| OCA / ODA | branch create / delete account | CREACC / DELACC |
| ICC / IDC / ICA / IDA | internet (web) create/delete customer/account | legacy Liberty webui (Java) |
| OCS | create standing order / direct debit | (not written by base programs) |

## 6. Fail codes and abend codes

| Program | COMMAREA fail codes |
|---|---|
| CRECUST | `T` bad title, `G` credit check failed/none answered, `C`-`H` credit-check container/channel errors, `O` DOB < 1601 or age > 150, `Y` DOB in future, `Z` invalid DOB, `1` VSAM write, `3` ENQ, `4` NCS update, `5` DEQ, `A`/`B` channel errors |
| CREACC | `1` customer not found, `3` ENQ, `5` CONTROL update, `7` insert, `8` > 9 accounts, `9` count error, `A` bad account type |
| INQCUST | `0` ok, `1` not found, `2` read error, `9` NCS/browse error |
| INQACCCU | `1` customer not found, `2`/`3`/`4` cursor open/fetch/close errors |
| UPDCUST | `1` not found, `2` read error, `3` rewrite error, `4` name and address both blank, `T` bad title |
| DELACC | `1` not found, `3` delete error |
| DBCRFUN | `1` account not found, `2` DB error, `3` insufficient funds (payment), `4` MORTGAGE/LOAN payment rejected |
| XFRFUN | `1` from not found, `2` to not found, `3` DB error, `4` amount ≤ 0 |

| Abend | Meaning | Programs |
|---|---|---|
| HWPT | PROCTRAN insert failed | CRECUST, CREACC, DELCUS, DELACC (and DBCRFUN/XFRFUN via rollback) |
| HNCS | named counter / ENQ failure | CREACC, INQACC |
| HRAC | account read/delete failure | INQACC, DELACC |
| HROL | SYNCPOINT ROLLBACK failure | INQCUST, INQACCCU, DBCRFUN, XFRFUN |
| CVR1 | VSAM read failure | INQCUST |
| WPV6 / WPV7 | VSAM delete failure | DELCUS |
| SAME | transfer from == to | XFRFUN |
| FROM / RUF2 / WPCD | update-from / update-to / PROCTRAN failures | XFRFUN |

Java mapping: business fail codes → `CbsaException` (4xx with `failCode`); abends → `CbsaAbendException`
(HTTP 500 with `abendCode`, transaction rolled back, `AbendEvent` published).

## 7. Determinism (shared contract)
- Time: `cbsa.clock.fixed` (Java) / `CBSA_FIXED_TIME` (legacy) — `java.time.Clock` bean in `common.config.ClockConfig`.
- Randomness: `cbsa.random.seed` / `CBSA_RANDOM_SEED` — CBSA-LCG in `common.config.CbsaRandom`
  (`state = (state*1103515245 + 12345) mod 2^31`, `r = state/2^31`); stream k seeded with seed + k;
  k = 0 CRECUST review offset, k = 1..5 CRDTAGY1..5 (draw 1 delay, draw 2 score).
- PROCTRAN_REF is the CICS task number (Java: per-JVM sequence) and is excluded from parity comparisons.
