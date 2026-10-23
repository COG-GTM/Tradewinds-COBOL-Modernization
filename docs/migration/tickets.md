# CBSA Java 21 programme — tickets

One agent per ticket, run in parallel within a wave. Owned paths are **disjoint**: a ticket may only add/modify files under its
owned paths. Nobody but the integration agent touches `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/common/**`,
`src/cbsa-core-service/pom.xml`, `schema.sql`, `application*.properties`, `legacy/**`, `baseline/**`, `docs/migration/**`.
If a ticket needs a dependency, property or common change, it says so in its PR description and the integration agent adds it.

Integration order: wave 1 (no code dependencies on other tickets) → wave 2 (DELCUS uses DELACC; screens/frontend/adapters consume wave-1 endpoints).
Branch: `devin/cbsa-<ticket-id-lower>-<slug>` from `devin/java21-cbsa-core`; PR into `devin/java21-cbsa-core`; build: `cd src/cbsa-core-service && mvn -q verify` (JDK 21).

| ID | Wave | Title | COBOL programs | Owned paths |
|---|---|---|---|---|
| T-01 | 1 | CRECUST + CRDTAGY1-5: create customer with async credit check | CRECUST, CRDTAGY1, CRDTAGY2, CRDTAGY3, CRDTAGY4, CRDTAGY5 | `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/crecust/**`<br>`src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/crecust/**` |
| T-02 | 1 | INQCUST + customer name search / list | INQCUST | `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/inqcust/**`<br>`src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/inqcust/**` |
| T-03 | 1 | UPDCUST: update customer name/address | UPDCUST | `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/updcust/**`<br>`src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/updcust/**` |
| T-04 | 2 | DELCUS: delete customer and all their accounts | DELCUS | `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/delcus/**`<br>`src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/delcus/**` |
| T-05 | 1 | CREACC: create account | CREACC | `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/creacc/**`<br>`src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/creacc/**` |
| T-06 | 1 | INQACC + INQACCCU: account enquiry and accounts-by-customer | INQACC, INQACCCU | `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/inqacc/**`<br>`src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/inqacc/**` |
| T-07 | 1 | UPDACC: update account type / interest / overdraft | UPDACC | `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/updacc/**`<br>`src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/updacc/**` |
| T-08 | 1 | DELACC: delete account | DELACC | `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/delacc/**`<br>`src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/delacc/**` |
| T-09 | 1 | DBCRFUN: debit/credit + z/OS Connect payment API | DBCRFUN | `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/dbcrfun/**`<br>`src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/dbcrfun/**` |
| T-10 | 1 | XFRFUN: transfer funds between accounts | XFRFUN | `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/xfrfun/**`<br>`src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/xfrfun/**` |
| T-11 | 1 | BANKDATA: Java data generator reproducing seed.sql | BANKDATA | `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/bankdata/**`<br>`src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/bankdata/**` |
| T-12 | 2 | BNKMENU/BNK1* screen-flow rules + ABNDPROC | BNKMENU, BNK1DCS, BNK1CCS, BNK1CAC, BNK1UAC, BNK1DAC, BNK1CRA, BNK1TFN, BNK1CCA, ABNDPROC | `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/screens/**`<br>`src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/screens/**`<br>`src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/abndproc/**`<br>`src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/abndproc/**` |
| T-13 | 2 | React frontend wired to cbsa-core-service and served by Spring Boot | (Carbon React UI) | `src/bank-application-frontend/**`<br>`src/cbsa-core-service/src/main/resources/static/**` |
| T-14 | 2 | z/OS Connect adapters on Boot 3 / Java 21 calling cbsa-core-service | (Z-OS-Connect-Payment-Interface), (Z-OS-Connect-Customer-Services-Interface) | `src/Z-OS-Connect-Payment-Interface/**`<br>`src/Z-OS-Connect-Customer-Services-Interface/**` |

## Ticket details

### T-01 — CRECUST + CRDTAGY1-5: create customer with async credit check (wave 1)

Programs: CRECUST, CRDTAGY1, CRDTAGY2, CRDTAGY3, CRDTAGY4, CRDTAGY5

Owned paths:
- `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/crecust/**`
- `src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/crecust/**`

Port CRECUST and the five credit agencies. AC: POST /banking/customer per api-contract.md (201 Customer JSON); title rule R-C1 (failCode T, 400); sort code must be 987654; DOB rules R-C2 (O/Y/Z, 400); credit check R-C3: 5 agencies in parallel (CompletableFuture), agency n uses CbsaRandomFactory.creditAgencyStream(n) (draw 1 delay floor(2r)+1 s, only slept when cbsa.credit.simulate-delay=true; draw 2 score floor(998r)+1), score = sum/answered (int), review date = today + (floor(20r)+1) days from stream 0; no answers -> failCode G 500 and no customer row; number from CounterService.nextCustomerNumber() inside @Transactional; PROCTRAN OCC via ProctranWriter.createCustomer; with seed fixed the created customer (number, score, review date) matches baseline/transcripts/CRECUST/*.json. Use only common.* (repositories, ProctranWriter, CounterService, Clock, CbsaRandomFactory, CbsaException/CbsaAbendException, BankIdentity). Do not edit common/, pom.xml, schema.sql or application*.properties: list any needed dependency/property in the PR description for the integration agent. Expose the public service API named in docs/migration/program-map.md exactly. Tests: JUnit 5 + MockMvc/@SpringBootTest against H2 with cbsa.clock.fixed and cbsa.random.seed set; replay every baseline/transcripts/<PROGRAM>/*.json case that exists and assert identical results (balances, fail codes, PROCTRAN rows excluding PROCTRAN_REF). `cd src/cbsa-core-service && mvn -q verify` green on JDK 21. Open a PR into devin/java21-cbsa-core titled "[CBSA-Java21][T-xx] ...", get Devin Review, do not merge.

### T-02 — INQCUST + customer name search / list (wave 1)

Programs: INQCUST

Owned paths:
- `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/inqcust/**`
- `src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/inqcust/**`

Port INQCUST and the legacy CustomerResource read endpoints. AC: GET /banking/customer/{id} (200 Customer JSON, 404 failCode 1 'Customer number N not found.', 400 negative); customer 0 -> random existing customer (CbsaRandom stream), 9999999999 -> last customer (R-C5); GET /banking/customer/name?name=&limit=&offset=&countOnly= returns {numberOfCustomers, customers} with case-insensitive contains match ordered by number, 404 when none; GET /banking/customer?limit&offset&countOnly; JSON exactly as api-contract.md Customer JSON (10-char zero-padded id, ISO dates, customerCreditScore string). Use only common.* (repositories, ProctranWriter, CounterService, Clock, CbsaRandomFactory, CbsaException/CbsaAbendException, BankIdentity). Do not edit common/, pom.xml, schema.sql or application*.properties: list any needed dependency/property in the PR description for the integration agent. Expose the public service API named in docs/migration/program-map.md exactly. Tests: JUnit 5 + MockMvc/@SpringBootTest against H2 with cbsa.clock.fixed and cbsa.random.seed set; replay every baseline/transcripts/<PROGRAM>/*.json case that exists and assert identical results (balances, fail codes, PROCTRAN rows excluding PROCTRAN_REF). `cd src/cbsa-core-service && mvn -q verify` green on JDK 21. Open a PR into devin/java21-cbsa-core titled "[CBSA-Java21][T-xx] ...", get Devin Review, do not merge.

### T-03 — UPDCUST: update customer name/address (wave 1)

Programs: UPDCUST

Owned paths:
- `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/updcust/**`
- `src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/updcust/**`

Port UPDCUST. AC: PUT /banking/customer/{id} per api-contract.md; only name and address updated (DOB/credit score in body ignored); title rule (failCode T, 400); blank name keeps old name, blank address keeps old address, both blank -> failCode 4 400; not found -> 404 failCode 1; sortCode mismatch / null name / null address -> 400 with legacy messages; no PROCTRAN row (UPDCUST writes none). Use only common.* (repositories, ProctranWriter, CounterService, Clock, CbsaRandomFactory, CbsaException/CbsaAbendException, BankIdentity). Do not edit common/, pom.xml, schema.sql or application*.properties: list any needed dependency/property in the PR description for the integration agent. Expose the public service API named in docs/migration/program-map.md exactly. Tests: JUnit 5 + MockMvc/@SpringBootTest against H2 with cbsa.clock.fixed and cbsa.random.seed set; replay every baseline/transcripts/<PROGRAM>/*.json case that exists and assert identical results (balances, fail codes, PROCTRAN rows excluding PROCTRAN_REF). `cd src/cbsa-core-service && mvn -q verify` green on JDK 21. Open a PR into devin/java21-cbsa-core titled "[CBSA-Java21][T-xx] ...", get Devin Review, do not merge.

### T-04 — DELCUS: delete customer and all their accounts (wave 2)

Programs: DELCUS

Owned paths:
- `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/delcus/**`
- `src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/delcus/**`

Port DELCUS (wave 2: depends on T-08 delacc.DeleteAccountService). AC: DELETE /banking/customer/{id} returns 200 with the deleted Customer JSON; customer must exist (404 failCode 1); every account (ordered by number) is deleted through DeleteAccountService (each writes ODA), then the customer row is deleted and PROCTRAN ODC written (ProctranWriter.deleteCustomer); whole operation @Transactional; PROCTRAN failure -> CbsaAbendException HWPT; matches baseline/transcripts/DELCUS/*.json. Until T-08 merges, build against a local stub in your test sources only. Use only common.* (repositories, ProctranWriter, CounterService, Clock, CbsaRandomFactory, CbsaException/CbsaAbendException, BankIdentity). Do not edit common/, pom.xml, schema.sql or application*.properties: list any needed dependency/property in the PR description for the integration agent. Expose the public service API named in docs/migration/program-map.md exactly. Tests: JUnit 5 + MockMvc/@SpringBootTest against H2 with cbsa.clock.fixed and cbsa.random.seed set; replay every baseline/transcripts/<PROGRAM>/*.json case that exists and assert identical results (balances, fail codes, PROCTRAN rows excluding PROCTRAN_REF). `cd src/cbsa-core-service && mvn -q verify` green on JDK 21. Open a PR into devin/java21-cbsa-core titled "[CBSA-Java21][T-xx] ...", get Devin Review, do not merge.

### T-05 — CREACC: create account (wave 1)

Programs: CREACC

Owned paths:
- `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/creacc/**`
- `src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/creacc/**`

Port CREACC. AC: POST /banking/account per api-contract.md (201 Account JSON); account type rule R-A1 (failCode A); customer must exist (404 failCode 1) and have <= 9 accounts (failCode 8 'cannot have more than ten accounts.'); interest/overdraft/sortCode web validations R-A9; number from CounterService.nextAccountNumber() in @Transactional; opened = last statement = today (Clock), next statement per R-A4 (+30 days, Feb +28/+29 by Gregorian leap rule) in StatementDates; PROCTRAN OCA via ProctranWriter.createAccount; matches baseline/transcripts/CREACC/*.json. Use only common.* (repositories, ProctranWriter, CounterService, Clock, CbsaRandomFactory, CbsaException/CbsaAbendException, BankIdentity). Do not edit common/, pom.xml, schema.sql or application*.properties: list any needed dependency/property in the PR description for the integration agent. Expose the public service API named in docs/migration/program-map.md exactly. Tests: JUnit 5 + MockMvc/@SpringBootTest against H2 with cbsa.clock.fixed and cbsa.random.seed set; replay every baseline/transcripts/<PROGRAM>/*.json case that exists and assert identical results (balances, fail codes, PROCTRAN rows excluding PROCTRAN_REF). `cd src/cbsa-core-service && mvn -q verify` green on JDK 21. Open a PR into devin/java21-cbsa-core titled "[CBSA-Java21][T-xx] ...", get Devin Review, do not merge.

### T-06 — INQACC + INQACCCU: account enquiry and accounts-by-customer (wave 1)

Programs: INQACC, INQACCCU

Owned paths:
- `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/inqacc/**`
- `src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/inqacc/**`

Port INQACC and INQACCCU. AC: GET /banking/account/{id} (200 Account JSON, 404 'Account number N not found.'), 99999999 -> last account; GET /banking/account/retrieveByCustomerNumber/{id} returns {customerFound, numberOfAccounts, accounts} ordered by account number, 404 failCode 1 if the customer does not exist, countOnly supported; GET /banking/account?limit&offset&countOnly; Account JSON exactly as api-contract.md (8-char id, 10-char customerNumber, trimmed accountType, ISO dates, 2-dp numbers). Use only common.* (repositories, ProctranWriter, CounterService, Clock, CbsaRandomFactory, CbsaException/CbsaAbendException, BankIdentity). Do not edit common/, pom.xml, schema.sql or application*.properties: list any needed dependency/property in the PR description for the integration agent. Expose the public service API named in docs/migration/program-map.md exactly. Tests: JUnit 5 + MockMvc/@SpringBootTest against H2 with cbsa.clock.fixed and cbsa.random.seed set; replay every baseline/transcripts/<PROGRAM>/*.json case that exists and assert identical results (balances, fail codes, PROCTRAN rows excluding PROCTRAN_REF). `cd src/cbsa-core-service && mvn -q verify` green on JDK 21. Open a PR into devin/java21-cbsa-core titled "[CBSA-Java21][T-xx] ...", get Devin Review, do not merge.

### T-07 — UPDACC: update account type / interest / overdraft (wave 1)

Programs: UPDACC

Owned paths:
- `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/updacc/**`
- `src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/updacc/**`

Port UPDACC. AC: PUT /banking/account/{id} accepting the full Account JSON the React UI sends; only accountType, interestRate, overdraft are applied (balances/dates ignored); blank or invalid type -> 400; interest rate 0..9999.99 and <= 2 dp; sortCode must be 987654; not found -> 404; returns 200 updated Account JSON; no PROCTRAN row. Use only common.* (repositories, ProctranWriter, CounterService, Clock, CbsaRandomFactory, CbsaException/CbsaAbendException, BankIdentity). Do not edit common/, pom.xml, schema.sql or application*.properties: list any needed dependency/property in the PR description for the integration agent. Expose the public service API named in docs/migration/program-map.md exactly. Tests: JUnit 5 + MockMvc/@SpringBootTest against H2 with cbsa.clock.fixed and cbsa.random.seed set; replay every baseline/transcripts/<PROGRAM>/*.json case that exists and assert identical results (balances, fail codes, PROCTRAN rows excluding PROCTRAN_REF). `cd src/cbsa-core-service && mvn -q verify` green on JDK 21. Open a PR into devin/java21-cbsa-core titled "[CBSA-Java21][T-xx] ...", get Devin Review, do not merge.

### T-08 — DELACC: delete account (wave 1)

Programs: DELACC

Owned paths:
- `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/delacc/**`
- `src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/delacc/**`

Port DELACC. AC: DELETE /banking/account/{id} returns 200 with the deleted Account JSON; not found -> 404 failCode 1; PROCTRAN ODA via ProctranWriter.deleteAccount (footer DELETE, amount = actual balance); @Transactional, PROCTRAN failure -> CbsaAbendException HWPT; public DeleteAccountService#deleteAccount(long) : Account is consumed by T-04 (keep signature). Matches baseline/transcripts/DELACC/*.json. Use only common.* (repositories, ProctranWriter, CounterService, Clock, CbsaRandomFactory, CbsaException/CbsaAbendException, BankIdentity). Do not edit common/, pom.xml, schema.sql or application*.properties: list any needed dependency/property in the PR description for the integration agent. Expose the public service API named in docs/migration/program-map.md exactly. Tests: JUnit 5 + MockMvc/@SpringBootTest against H2 with cbsa.clock.fixed and cbsa.random.seed set; replay every baseline/transcripts/<PROGRAM>/*.json case that exists and assert identical results (balances, fail codes, PROCTRAN rows excluding PROCTRAN_REF). `cd src/cbsa-core-service && mvn -q verify` green on JDK 21. Open a PR into devin/java21-cbsa-core titled "[CBSA-Java21][T-xx] ...", get Devin Review, do not merge.

### T-09 — DBCRFUN: debit/credit + z/OS Connect payment API (wave 1)

Programs: DBCRFUN

Owned paths:
- `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/dbcrfun/**`
- `src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/dbcrfun/**`

Port DBCRFUN. AC: PUT /banking/account/debit/{id} and /credit/{id} with {amount} (teller, no funds check, PROCTRAN DEB 'COUNTER WTHDRW' / CRE 'COUNTER RECVED'); PUT /makepayment/dbcr with the PAYDBCR JSON of api-contract.md, always 200 with CommSuccess/CommFailCode: 1 not found, 3 insufficient funds (payment debit leaving available < 0), 4 MORTGAGE/LOAN with facility type 496, PROCTRAN PDR/PCR with desc = origin(1:14); both balances += amount; failures roll back (@Transactional); matches baseline/transcripts/DBCRFUN/*.json. Use only common.* (repositories, ProctranWriter, CounterService, Clock, CbsaRandomFactory, CbsaException/CbsaAbendException, BankIdentity). Do not edit common/, pom.xml, schema.sql or application*.properties: list any needed dependency/property in the PR description for the integration agent. Expose the public service API named in docs/migration/program-map.md exactly. Tests: JUnit 5 + MockMvc/@SpringBootTest against H2 with cbsa.clock.fixed and cbsa.random.seed set; replay every baseline/transcripts/<PROGRAM>/*.json case that exists and assert identical results (balances, fail codes, PROCTRAN rows excluding PROCTRAN_REF). `cd src/cbsa-core-service && mvn -q verify` green on JDK 21. Open a PR into devin/java21-cbsa-core titled "[CBSA-Java21][T-xx] ...", get Devin Review, do not merge.

### T-10 — XFRFUN: transfer funds between accounts (wave 1)

Programs: XFRFUN

Owned paths:
- `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/xfrfun/**`
- `src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/xfrfun/**`

Port XFRFUN. Duplicate note: supersedes XFRFUN-only PRs #1, #2 and #4 — mention this in the PR description. AC: PUT /banking/account/transfer/{id} with {targetAccount, amount} returns 200 {fromAccount, toAccount, amount}; amount <= 0 -> 400 failCode 4; same account -> 400 (legacy abend SAME); from missing -> 404 failCode 1; to missing -> 404 failCode 2 with no balance change; updates applied lowest account number first; single @Transactional; one PROCTRAN TFR row on the from account via ProctranWriter.transfer; no insufficient-funds check (legacy parity); matches baseline/transcripts/XFRFUN/*.json. Use only common.* (repositories, ProctranWriter, CounterService, Clock, CbsaRandomFactory, CbsaException/CbsaAbendException, BankIdentity). Do not edit common/, pom.xml, schema.sql or application*.properties: list any needed dependency/property in the PR description for the integration agent. Expose the public service API named in docs/migration/program-map.md exactly. Tests: JUnit 5 + MockMvc/@SpringBootTest against H2 with cbsa.clock.fixed and cbsa.random.seed set; replay every baseline/transcripts/<PROGRAM>/*.json case that exists and assert identical results (balances, fail codes, PROCTRAN rows excluding PROCTRAN_REF). `cd src/cbsa-core-service && mvn -q verify` green on JDK 21. Open a PR into devin/java21-cbsa-core titled "[CBSA-Java21][T-xx] ...", get Devin Review, do not merge.

### T-11 — BANKDATA: Java data generator reproducing seed.sql (wave 1)

Programs: BANKDATA

Owned paths:
- `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/bankdata/**`
- `src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/bankdata/**`

Port BANKDATA. AC: BankDataGenerator#generate(startKey, endKey, stepKey, seed) reproduces BANKDATA's customer/account generation (title/forename/initial/surname/town/street tables, account types, interest rates, overdraft limits, balances, dates, CONTROL rows) using CbsaRandom instead of FUNCTION RANDOM, with the algorithm and PARM used for baseline/seed/seed.sql documented; BankDataSqlWriter#toSql emits INSERTs in the seed.sql format (column names of schema.sql, zero-padded keys, DATE literals); unit test asserts the generated SQL equals baseline/seed/seed.sql (normalised whitespace) for the documented PARM, or documents and asserts row-level equality if the baseline was produced by GnuCOBOL RANDOM; optional dev-only endpoint behind a property. Use only common.* (repositories, ProctranWriter, CounterService, Clock, CbsaRandomFactory, CbsaException/CbsaAbendException, BankIdentity). Do not edit common/, pom.xml, schema.sql or application*.properties: list any needed dependency/property in the PR description for the integration agent. Expose the public service API named in docs/migration/program-map.md exactly. Tests: JUnit 5 + MockMvc/@SpringBootTest against H2 with cbsa.clock.fixed and cbsa.random.seed set; replay every baseline/transcripts/<PROGRAM>/*.json case that exists and assert identical results (balances, fail codes, PROCTRAN rows excluding PROCTRAN_REF). `cd src/cbsa-core-service && mvn -q verify` green on JDK 21. Open a PR into devin/java21-cbsa-core titled "[CBSA-Java21][T-xx] ...", get Devin Review, do not merge.

### T-12 — BNKMENU/BNK1* screen-flow rules + ABNDPROC (wave 2)

Programs: BNKMENU, BNK1DCS, BNK1CCS, BNK1CAC, BNK1UAC, BNK1DAC, BNK1CRA, BNK1TFN, BNK1CCA, ABNDPROC

Owned paths:
- `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/screens/**`
- `src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/screens/**`
- `src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/abndproc/**`
- `src/cbsa-core-service/src/test/java/com/ibm/cics/cip/bank/core/abndproc/**`

Port the 3270 screen logic as a Java layer (wave 2: calls the T-01..T-10 services). AC: MenuRouter implements R-S1 (options 1-7, A; invalid selection message; PF3/PF12 exit; CLEAR) with tests; one screen handler per BMS mapset reproducing each BNK1* program's field validation and fail-code -> message mapping (BMS field names/lengths from src/base/bms_src) and delegating to the business services; table-driven tests per screen incl. error messages from the COBOL source; ABNDPROC as AbendRecorder listening for common.error.AbendEvent with GET /banking/abend; optional GET /banking/processedTransaction. Use only common.* (repositories, ProctranWriter, CounterService, Clock, CbsaRandomFactory, CbsaException/CbsaAbendException, BankIdentity). Do not edit common/, pom.xml, schema.sql or application*.properties: list any needed dependency/property in the PR description for the integration agent. Expose the public service API named in docs/migration/program-map.md exactly. Tests: JUnit 5 + MockMvc/@SpringBootTest against H2 with cbsa.clock.fixed and cbsa.random.seed set; replay every baseline/transcripts/<PROGRAM>/*.json case that exists and assert identical results (balances, fail codes, PROCTRAN rows excluding PROCTRAN_REF). `cd src/cbsa-core-service && mvn -q verify` green on JDK 21. Open a PR into devin/java21-cbsa-core titled "[CBSA-Java21][T-xx] ...", get Devin Review, do not merge.

### T-13 — React frontend wired to cbsa-core-service and served by Spring Boot (wave 2)

Programs: (Carbon React UI)

Owned paths:
- `src/bank-application-frontend/**`
- `src/cbsa-core-service/src/main/resources/static/**`

Wire src/bank-application-frontend to the new service. AC: keep REACT_APP_CUSTOMER_URL/REACT_APP_ACCOUNT_URL (/webui-1.0/banking/...); add CRA dev proxy to http://localhost:8080 for yarn start; `yarn install && yarn build` succeeds; build output copied into src/cbsa-core-service/src/main/resources/static/ (homepage /webui-1.0/) so GET /webui-1.0/ serves the UI (list any SPA-forwarding controller/config needed in the PR for the integration agent); browser-tested end to end against ./run-local.sh: customer search by number and name, create customer, update customer, create account, update account, delete account, delete customer; screenshots/recording in the PR. Do not touch other paths. Open a PR into devin/java21-cbsa-core titled "[CBSA-Java21][T-13] ...", get Devin Review, do not merge.

### T-14 — z/OS Connect adapters on Boot 3 / Java 21 calling cbsa-core-service (wave 2)

Programs: (Z-OS-Connect-Payment-Interface), (Z-OS-Connect-Customer-Services-Interface)

Owned paths:
- `src/Z-OS-Connect-Payment-Interface/**`
- `src/Z-OS-Connect-Customer-Services-Interface/**`

Upgrade both Spring Boot adapters to Java 21 (java.version 21, Boot 3.3.x/3.5.x, jakarta) and point them at cbsa-core-service. AC: Payment interface PUTs to {base}/webui-1.0/makepayment/dbcr and keeps its form + /submit behaviour and fail-code messages; Customer services interface calls /webui-1.0/banking/customer|account endpoints (mapping existing form/json classes), connection base URL configurable via property (default http://localhost:8080); remove/neutralise the IBM-only provided deps if they block the build; `mvn -q verify` green for each on JDK 21; tests with MockWebServer/WireMock or MockRestServiceServer for each adapter call. Do not touch other paths. Open a PR into devin/java21-cbsa-core titled "[CBSA-Java21][T-14] ...", get Devin Review, do not merge.
