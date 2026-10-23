# COBOL program / paragraph → Java class / method

Root package `com.ibm.cics.cip.bank.core` (module `src/cbsa-core-service`). Each ticket owns
`.../core/<pkg>/**` (main + test) only. **Shared code** lives in `.../core/common/**` (owned by the scaffold /
integration agent; tickets must not edit it). The *public service API* column is a contract: wave-2 tickets
(DELCUS, screens, frontend, z/OS Connect) code against these names, so wave-1 tickets must expose them exactly.

## Shared (`common`, delivered by PLAN-B)

| COBOL | Java |
|---|---|
| GETSCODE, `SORTCODE.cpy` | `common.bank.BankIdentity#sortCode()`; `GET /banking/sortCode` (`BankIdentityController`) |
| GETCOMPY | `common.bank.BankIdentity#companyName()`; `GET /banking/companyName` |
| `CUSTOMER.cpy` / VSAM CUSTOMER READ/WRITE/REWRITE/DELETE/STARTBR | `common.domain.Customer`, `common.repository.CustomerRepository` (`findByKey`, `findByNameContaining`, `countByNameContaining`, `findAll`, `count`, `maxCustomerNumber`, `insert`, `update`, `delete`) |
| `ACCOUNT.cpy` / `ACCDB2.cpy` EXEC SQL on ACCOUNT | `common.domain.Account`, `common.repository.AccountRepository` (`findByKey`, `findByCustomer`, `countByCustomer`, `findAll`, `count`, `maxAccountNumber`, `insert`, `updateTypeRateOverdraft`, `updateBalances`, `delete`) |
| `PROCTRAN.cpy` / `PROCDB2.cpy` / `PROCISRT.cpy`, every `WRITE-PROCTRAN-DB2` paragraph | `common.domain.ProcessedTransaction`, `common.domain.ProctranType`, `common.repository.ProctranRepository`, `common.proctran.ProctranWriter` (`debitCredit`, `transfer`, `createCustomer`, `deleteCustomer`, `createAccount`, `deleteAccount`, generic `write`) |
| `CONTDB2.cpy` / `CONTROLI.cpy` / CONTROL table | `common.domain.ControlRecord`, `common.repository.ControlRepository` |
| NCS `CBSACUST`/`CBSAACCT` + ENQ/DEQ, `NEWCUSNO.cpy`/`NEWACCNO.cpy` (G/R/C) | `common.counter.CounterService` (`nextCustomerNumber`, `nextAccountNumber`, `current*`, `rollback*`) |
| `EIBTASKN` (PROCTRAN_REF) | `common.proctran.TaskNumberSupplier` |
| `EXEC CICS ASKTIME/FORMATTIME`, DB2 `CURRENT TIMESTAMP` | `java.time.Clock` bean (`common.config.ClockConfig`, property `cbsa.clock.fixed`) |
| `FUNCTION RANDOM` | `common.config.CbsaRandom` / `CbsaRandomFactory` (property `cbsa.random.seed`) |
| COMMAREA `*-SUCCESS` / `*-FAIL-CODE` | `common.error.CbsaException` (status + `failCode`) |
| `EXEC CICS ABEND ABCODE(x)` + LINK ABNDPROC | `common.error.CbsaAbendException` → HTTP 500 + `common.error.AbendEvent` |
| `EXEC CICS SYNCPOINT ROLLBACK` | `@Transactional` on the ticket's service method (runtime exception ⇒ rollback) |
| Fixed-width PIC formats | `common.util.CbsaFormats` |
| seed (BANKDATA output / `baseline/seed/seed.sql`) | `common.seed.SeedDataLoader` (property `cbsa.seed.location`, `cbsa.seed.mode`) |

## Business programs

| COBOL program → paragraphs | Package (ticket) | Public service API (contract) | REST controller |
|---|---|---|---|
| **CRECUST** `PREMIERE` → `CRECUST`-title `EVALUATE`, `POPULATE-TIME-DATE`, `CREDIT-CHECK`, `DATE-OF-BIRTH-CHECK`, `ENQ-NAMED-COUNTER`, `UPD-NCS`, `WRITE-CUSTOMER-VSAM`, `WRITE-PROCTRAN-DB2`, `DEQ-NAMED-COUNTER` | `crecust` (T-01) | `CreateCustomerService#createCustomer(String name, String address, LocalDate dateOfBirth) : Customer`; `TitleValidator#isValid(String name)` | `CreateCustomerController` `POST /banking/customer` |
| **CRDTAGY1..5** `PREMIERE` (DELAY, GET CONTAINER, RANDOM score, PUT CONTAINER) | `crecust` (T-01) | `CreditAgency` (5 beans `CRDTAGY1..5`), `CreditCheckService#check(Customer) : CreditCheckResult(score, reviewDate, ok)` — parallel via `CompletableFuture`, seeded streams 1..5 | — |
| **INQCUST** `PREMIERE`, `READ-CUSTOMER-NCS`, `READ-CUSTOMER-VSAM`, `GET-LAST-CUSTOMER-VSAM`, `GENERATE-RANDOM-CUSTOMER` | `inqcust` (T-02) | `InquireCustomerService#inquire(long customerNumber) : Optional<Customer>` (0 = random, 9999999999 = last); `#searchByName(String name, int limit, int offset) : List<Customer>`; `#countByName(String)` | `InquireCustomerController` `GET /banking/customer/{id}`, `/name`, `GET /banking/customer` |
| **UPDCUST** `PREMIERE` (title check), `UPDATE-CUSTOMER-VSAM` | `updcust` (T-03) | `UpdateCustomerService#updateCustomer(long customerNumber, String name, String address) : Customer` | `UpdateCustomerController` `PUT /banking/customer/{id}` |
| **DELCUS** `PREMIERE`, `GET-ACCOUNTS`, `DELETE-ACCOUNTS`, `DEL-CUST-VSAM`, `WRITE-PROCTRAN` | `delcus` (T-04, wave 2) | `DeleteCustomerService#deleteCustomer(long customerNumber) : Customer` — uses `delacc.DeleteAccountService` per account | `DeleteCustomerController` `DELETE /banking/customer/{id}` |
| **CREACC** `PREMIERE`, `CUSTOMER-ACCOUNT-COUNT`, `ACCOUNT-TYPE-CHECK`, `ENQ-NAMED-COUNTER`, `FIND-NEXT-ACCOUNT`, `CALCULATE-DATES`, `WRITE-ACCOUNT-DB2`, `WRITE-PROCTRAN-DB2`, `DEQ-NAMED-COUNTER` | `creacc` (T-05) | `CreateAccountService#createAccount(long customerNumber, String accountType, BigDecimal interestRate, int overdraftLimit, BigDecimal availableBalance, BigDecimal actualBalance) : Account`; `StatementDates#nextStatement(LocalDate opened)` | `CreateAccountController` `POST /banking/account` |
| **INQACC** `PREMIERE`, `READ-ACCOUNT-DB2`, `READ-ACCOUNT-LAST` | `inqacc` (T-06) | `InquireAccountService#inquire(long accountNumber) : Optional<Account>` (99999999 = last) | `InquireAccountController` `GET /banking/account/{id}`, `GET /banking/account` |
| **INQACCCU** `PREMIERE`, `CUSTOMER-CHECK`, `READ-ACCOUNT-DB2` (cursor) | `inqacc` (T-06) | `InquireAccountService#accountsForCustomer(long customerNumber) : List<Account>` (throws 404 `failCode 1` if customer missing) | `GET /banking/account/retrieveByCustomerNumber/{id}` |
| **UPDACC** `PREMIERE`, `UPDATE-ACCOUNT-DB2` | `updacc` (T-07) | `UpdateAccountService#updateAccount(long accountNumber, String accountType, BigDecimal interestRate, int overdraftLimit) : Account` | `UpdateAccountController` `PUT /banking/account/{id}` |
| **DELACC** `PREMIERE`, `READ-ACCOUNT-DB2`, `DEL-ACCOUNT-DB2`, `WRITE-PROCTRAN-DB2` | `delacc` (T-08) | `DeleteAccountService#deleteAccount(long accountNumber) : Account` | `DeleteAccountController` `DELETE /banking/account/{id}` |
| **DBCRFUN** `PREMIERE`, `UPDATE-ACCOUNT-DB2`, `WRITE-TO-PROCTRAN`, `CHECK-FOR-STORM-DRAIN-DB2` | `dbcrfun` (T-09) | `DebitCreditService#debitCredit(long accountNumber, BigDecimal amount, boolean payment, String origin14) : Account` | `DebitCreditController` `PUT /banking/account/debit/{id}`, `/credit/{id}`; `PaymentController` `PUT /makepayment/dbcr` |
| **XFRFUN** `PREMIERE`, `UPDATE-ACCOUNT-DB2` (`UAD010` ordering), `UPDATE-ACCOUNT-DB2-FROM`, `-TO`, `WRITE-TO-PROCTRAN`, `CHECK-FOR-STORM-DRAIN-DB2` | `xfrfun` (T-10) | `TransferService#transfer(long fromAccount, long toAccount, BigDecimal amount) : TransferResult(Account from, Account to, BigDecimal amount)` | `TransferController` `PUT /banking/account/transfer/{id}` |
| **BANKDATA** `PREMIERE`, name/town/street table init, `GENERATE-CUSTOMERS`, `GENERATE-ACCOUNTS`, `WRITE-CONTROL` | `bankdata` (T-11) | `BankDataGenerator#generate(long startKey, long endKey, long stepKey, long seed) : GeneratedBank`; `BankDataSqlWriter#toSql(GeneratedBank) : String` (byte-identical to `baseline/seed/seed.sql` for the documented PARM) | optional `POST /banking/admin/bankdata` (dev only) |
| **ABNDPROC** `PREMIERE` (WRITE ABNDFILE) | `abndproc` (T-12) | `AbendRecorder` (`@EventListener(AbendEvent)`), `#recent(int limit)` | `GET /banking/abend` |
| **BNKMENU** `PREMIERE`, `PROCESS-MENU-MAP`, `INVOKE-OTHER-TXNS`, `SEND-MAP` | `screens` (T-12, wave 2) | `MenuRouter#route(String option, AidKey key) : ScreenTransition` | — (Java layer + tests; optional `/banking/screens/*`) |
| **BNK1DCS / BNK1CCS / BNK1CAC / BNK1UAC / BNK1DAC / BNK1CRA / BNK1TFN / BNK1CCA** `PROCESS-MAP`, `EDIT-DATA`, `VALIDATE-DATA`, `GET-*-DATA`, `SEND-MAP` | `screens` (T-12, wave 2) | one `*ScreenHandler` per BMS mapset (`DisplayCustomerScreen`, `CreateCustomerScreen`, `CreateAccountScreen`, `UpdateAccountScreen`, `DisplayAccountScreen`, `CreditDebitScreen`, `TransferScreen`, `ListAccountsScreen`) with `handle(ScreenInput) : ScreenOutput` (field validation + message text), delegating to the services above | — |

## Non-COBOL consumers
| Legacy | Ticket | Notes |
|---|---|---|
| React UI `src/bank-application-frontend` | T-13 | keeps `REACT_APP_*_URL`; dev proxy to `localhost:8080`; build output copied to `src/cbsa-core-service/src/main/resources/static/` and served at `/webui-1.0/` |
| `src/webui` (Liberty JAX-RS) | — | superseded by cbsa-core-service; left in place |
| `src/Z-OS-Connect-Payment-Interface` | T-14 | Boot 3 / Java 21, `ConnectionInfo` → cbsa-core-service `/webui-1.0/makepayment/dbcr` |
| `src/Z-OS-Connect-Customer-Services-Interface` | T-14 | Boot 3 / Java 21, clients re-pointed at `/webui-1.0/banking/*` |
