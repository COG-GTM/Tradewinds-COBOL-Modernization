# CBSA Modernization — Multi-Currency-Per-Account Ticket Backlog

## Context

This backlog describes the incremental modernization of the CICS Banking Sample
Application (CBSA) COBOL banking core to Java, using a **strangler-fig** approach,
while introducing **multi-currency-per-account** support.

### Agreed constraints

1. **Normalized balances.** An account may hold balances in multiple currencies via
   a new normalized child table `ACCOUNT_BALANCE` keyed by
   `(ACCOUNT_SORTCODE, ACCOUNT_NUMBER, ACCOUNT_CURRENCY)`. We do **not** add extra
   currency columns to `ACCOUNT`.
2. **Fixed 2-decimal money.** `DECIMAL(x,2)` / `PIC ...V99` is acceptable throughout.
   No minor-unit or arbitrary-precision refactor.
3. **No FX.** There is **no** cross-currency conversion. Transfers operate on a single
   specified currency and reject currency mismatches.
4. **COBOL stays runnable.** The COBOL programs continue to build and run unchanged.
   The Java path is **opt-in per capability** via a feature toggle (Ticket 4).
5. **Backward compatible.** Existing accounts/transactions default to currency `GBP`.

### Currency conventions

* `ACCOUNT_CURRENCY` / `PROCTRAN_CURRENCY` are ISO-4217 alpha-3 codes stored as
  `CHAR(3)` (COBOL `PIC X(3)`).
* The default currency for all pre-existing data and for callers that omit currency is
  `GBP`.

### Terminology

* **COBOL path** — the existing CICS/COBOL programs (`INQACC`, `DBCRFUN`, `XFRFUN`, …)
  invoked via z/OS Connect / `LINK`.
* **Java path** — the Liberty JAX-RS resources under
  `src/webui/src/main/java/com/ibm/cics/cip/bankliberty/` operating directly against Db2.

---

## Ticket dependency graph

```
1 (schema) ─┬─> 2 (migration)
            └─> 5 (domain/DTOs) ─┬─> 6 (inquiry)
                                 ├─> 7 (debit/credit) ──> 8 (transfer)
                                 └─> 9 (create/update/delete)
3 (characterization tests) ..... gates 6,7,8,9 (equivalence)
4 (feature toggle) ............. gates 6,7,8,9 (routing)
10 (Spring Boot + z/OS Connect) . depends on 7 (and 8 for transfer payloads)
```

Recommended execution order: **1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10**.
Tickets 1–2 are prerequisites for all Java work and are implemented first.

---

## Ticket 1 — Relational target schema (`ACCOUNT_BALANCE` + `PROCTRAN_CURRENCY`)

**Scope**

* Add a new `ACCOUNT_BALANCE` table to the Db2 install DDL, keyed by
  `(ACCOUNT_SORTCODE CHAR(6), ACCOUNT_NUMBER CHAR(8), ACCOUNT_CURRENCY CHAR(3))`
  with `AVAILABLE_BALANCE DECIMAL(12,2)` and `ACTUAL_BALANCE DECIMAL(12,2)`.
* Add a unique index on the three-part key and a **foreign key** from
  `ACCOUNT_BALANCE (ACCOUNT_SORTCODE, ACCOUNT_NUMBER)` to `ACCOUNT`.
  This requires `ACCOUNT` to expose those columns as a `PRIMARY KEY` (the existing
  unique index `ACCTINDX` becomes the enforcing/primary index — no row-layout change).
* Add `PROCTRAN_CURRENCY CHAR(3) NOT NULL WITH DEFAULT 'GBP'` to `PROCTRAN` so existing
  COBOL inserts (which list columns explicitly and omit currency) keep working and
  default to `GBP`.
* Update the COBOL `DECLARE TABLE` copybooks to match the new schema.
* Keep the drop job in sync (drop `ACCOUNT_BALANCE` before its parent `ACCOUNT`).

**Affected files**

* `etc/install/base/db2jcl/INSTDB2.jcl` — new STOGROUP/TABLESPACE/TABLE/INDEX for
  `ACCOUNT_BALANCE`; `PRIMARY KEY` on `ACCOUNT`; `PROCTRAN_CURRENCY` column on `PROCTRAN`.
* `etc/install/base/db2jcl/DROPDB2.jcl` — drop `ACCOUNT_BALANCE` (table/tablespace/stogroup) first.
* `src/base/cobol_copy/ACCDB2.cpy` — add an `ACCOUNT_BALANCE` `DECLARE TABLE`.
* `src/base/cobol_copy/PROCDB2.cpy` — add `PROCTRAN_CURRENCY CHAR(3)` to the `PROCTRAN`
  `DECLARE TABLE`.
* (Note) The segmented install members `CRESG0x/CRETS0x/CRETB0x/CREI x01` are an
  alternative to `INSTDB2.jcl`; parallel `ACCOUNT_BALANCE` members are a follow-on and
  out of scope for this ticket.

**Acceptance criteria**

* `INSTDB2.jcl` creates `ACCOUNT_BALANCE` with the specified key, columns, unique index,
  and enforced FK to `ACCOUNT`; the job installs cleanly on a fresh Db2 subsystem.
* `PROCTRAN` has `PROCTRAN_CURRENCY CHAR(3)` defaulting to `GBP`.
* The DECLARE-TABLE copybooks compile in the existing COBOL programs with no source
  changes to the programs themselves (COBOL still builds and runs).
* `DROPDB2.jcl` cleanly tears down the schema including `ACCOUNT_BALANCE`.

**Dependencies:** none (foundation ticket).

---

## Ticket 2 — Data migration / backfill

**Scope**

* Provide a migration script (SQL + JCL) for **existing** deployments that:
  * adds the `ACCOUNT` primary key, `ACCOUNT_BALANCE` table/index/FK, and the
    `PROCTRAN_CURRENCY` column via `ALTER`/`CREATE` (idempotent-friendly);
  * inserts exactly **one** `ACCOUNT_BALANCE` row per existing `ACCOUNT`, currency `GBP`,
    copying `ACCOUNT_AVAILABLE_BALANCE` → `AVAILABLE_BALANCE` and
    `ACCOUNT_ACTUAL_BALANCE` → `ACTUAL_BALANCE`;
  * backfills `PROCTRAN_CURRENCY = 'GBP'` for existing rows.

**Affected files**

* `etc/install/base/db2jcl/MIGMCUR.jcl` — new DSNTEP2 migration job with inline SQL.
* `etc/install/base/db2jcl/README.md` — document the migration member.

**Acceptance criteria**

* Running the migration against a pre-existing CBSA schema results in one GBP
  `ACCOUNT_BALANCE` row per account whose balances equal the source `ACCOUNT` balances.
* All existing `PROCTRAN` rows have `PROCTRAN_CURRENCY = 'GBP'`.
* Re-running the balance backfill does not create duplicate rows (guarded by
  `NOT EXISTS`).
* COBOL programs continue to run against the migrated schema.

**Dependencies:** Ticket 1 (schema shape).

---

## Ticket 3 — Characterization / golden test harness

**Scope**

* Capture the **current** behavior (COBOL path) of the four core capabilities —
  account inquiry, debit, credit, and same-currency transfer — through the existing
  Liberty JAX-RS endpoints (`AccountsResource`), producing golden fixtures/assertions.
* These golden tests are the equivalence oracle: each Java capability (Tickets 6–9)
  must reproduce the captured behavior before its toggle is flipped to the Java path.

**Affected files**

* `src/webui/src/test/java/com/ibm/cics/cip/bankliberty/**` — new characterization tests
  (JUnit) exercising `AccountsResource` inquiry/debit/credit/transfer.
* Test fixtures / recorded golden responses (JSON) checked into the test resources.

**Acceptance criteria**

* Tests capture request/response and resulting balance state for inquiry, debit, credit,
  and transfer against the COBOL path.
* Tests are re-runnable and produce a stable, diffable golden baseline.
* A single switch (the Ticket-4 toggle) lets the same suite run against the Java path to
  assert equivalence.

**Dependencies:** none to author; **gates** Tickets 6–9 (used to validate each).

---

## Ticket 4 — Per-capability feature toggle

**Scope**

* Add a configuration-driven toggle that lets **each** capability (inquiry, debit/credit,
  transfer, create/update/delete) independently route to the **COBOL path** or the new
  **Java path**.
* Toggle source: environment variable / JVM property / config file, read once and cached.
* Default: **all capabilities route to the COBOL path** (opt-in to Java).

**Affected files**

* New config class under
  `src/webui/src/main/java/com/ibm/cics/cip/bankliberty/` (e.g. `FeatureToggles`),
  and/or extension of `api/json/HBankDataAccess.java`.
* `AccountsResource` / `ProcessedTransactionResource` consult the toggle to select the path.

**Acceptance criteria**

* Each capability has an independent flag (e.g.
  `cbsa.java.inquiry`, `cbsa.java.debitcredit`, `cbsa.java.transfer`, `cbsa.java.account`).
* With all flags off (default), behavior is byte-for-byte the COBOL path.
* Flipping a single flag routes only that capability to Java; others remain on COBOL.
* Toggle values are logged at startup for operability.

**Dependencies:** none to author; **gates** Tickets 6–9 (routing).

---

## Ticket 5 — Currency-aware Account domain + DTOs

**Scope**

* Model multiple per-currency balances in the Java domain, backed by `ACCOUNT_BALANCE`
  (read and write balance rows rather than the single `ACCOUNT_AVAILABLE_BALANCE` /
  `ACCOUNT_ACTUAL_BALANCE` columns).
* Add a `currency` field to the account and debit/credit DTOs.

**Affected files**

* `src/webui/src/main/java/com/ibm/cics/cip/bankliberty/web/db2/Account.java` — introduce
  a per-currency balance collection (e.g. `List<AccountBalance>` or
  `Map<String,Balance>`) sourced from `ACCOUNT_BALANCE`; add a new
  `AccountBalance`/`web/db2/AccountBalance.java` helper for row access.
* `src/webui/src/main/java/com/ibm/cics/cip/bankliberty/api/json/AccountJSON.java` — add a
  `currency` field (and per-currency balance representation) with getters/setters and
  `toString`.
* `src/webui/src/main/java/com/ibm/cics/cip/bankliberty/api/json/DebitCreditAccountJSON.java`
  — add a `currency` field (default `GBP` when absent).

**Acceptance criteria**

* `Account` can load and persist balances per currency from/to `ACCOUNT_BALANCE`.
* `AccountJSON` serializes per-currency balances and a `currency` field; legacy single-balance
  fields remain populated (GBP) for backward compatibility of existing clients.
* `DebitCreditAccountJSON` accepts an optional `currency`, defaulting to `GBP`.
* Existing golden tests (Ticket 3) still pass on the COBOL path (DTO additions are additive).

**Dependencies:** Ticket 1 (schema).

---

## Ticket 6 — Account inquiry in Java (replaces `INQACC.cbl` / `INQACCCU.cbl`)

**Scope**

* Implement the account read paths in Java to return **all** per-currency balances from
  `ACCOUNT_BALANCE`, behind the inquiry toggle.

**Affected files**

* `web/db2/Account.java` — `getAccount(...)` (and the `getAccounts(...)` variants) join/load
  `ACCOUNT_BALANCE` rows.
* `api/json/AccountsResource.java` — `getAccountInternal(Long)` and
  `getAccountsByCustomerInternal(...)` return per-currency balances when the Java toggle is on.

**Acceptance criteria**

* With the inquiry toggle **on**, inquiry returns per-currency balances from
  `ACCOUNT_BALANCE`; with it **off**, behavior is unchanged (COBOL path).
* For GBP-only accounts, the Java response is equivalent to the COBOL golden baseline
  (Ticket 3).

**Dependencies:** Tickets 1, 3, 4, 5.

---

## Ticket 7 — Debit / credit in Java (replaces `DBCRFUN.cbl`)

**Scope**

* Make debit/credit **currency-aware**: target the matching `ACCOUNT_BALANCE` row instead
  of `UPDATE ACCOUNT SET ACCOUNT_ACTUAL_BALANCE ...`.
* Thread currency through the resource methods and record `PROCTRAN_CURRENCY` on each
  processed transaction.

**Affected files**

* `web/db2/Account.java` — `debitCredit(BigDecimal)` becomes currency-aware
  (e.g. `debitCredit(BigDecimal, String currency)`), updating the specific
  `ACCOUNT_BALANCE` row.
* `api/json/AccountsResource.java` — `debitCreditAccount(...)`, `debitAccountInternal(...)`,
  `creditAccountInternal(...)` thread the currency through.
* `api/json/ProcessedTransactionResource.java` — `writeInternal(...)` sets `PROCTRAN_CURRENCY`.
* `web/db2/ProcessedTransaction.java` — persist `PROCTRAN_CURRENCY`.

**Acceptance criteria**

* With the debit/credit toggle **on**, funds move on the specified `ACCOUNT_BALANCE` row
  and the resulting `PROCTRAN` row records `PROCTRAN_CURRENCY`.
* A debit/credit against a currency with no `ACCOUNT_BALANCE` row is rejected with a clear
  error (no implicit row creation, no FX).
* GBP behavior matches the COBOL golden baseline (Ticket 3).

**Dependencies:** Tickets 1, 3, 4, 5 (and 6 recommended).

---

## Ticket 8 — Same-currency transfer in Java (replaces `XFRFUN.cbl`)

**Scope**

* Implement local transfer in Java: debit source and credit target on the **same**
  specified currency balance; **reject** when source and target currency differ. No FX.

**Affected files**

* `api/json/AccountsResource.java` — `transferLocalInternal(...)` performs the two-sided
  update on one currency via the Ticket-7 currency-aware `debitCredit`.
* `api/json/TransferLocalJSON.java` — carries the transfer currency.

**Acceptance criteria**

* With the transfer toggle **on**, a same-currency transfer debits source and credits
  target atomically on that currency's `ACCOUNT_BALANCE` rows.
* A transfer where the requested currency is absent on either account, or where a
  cross-currency transfer is attempted, is denied with a clear, specific error.
* Two `PROCTRAN` rows are written with the correct `PROCTRAN_CURRENCY`.
* GBP behavior matches the COBOL golden baseline (Ticket 3).

**Dependencies:** Tickets 1, 3, 4, 5, 7.

---

## Ticket 9 — Account create / update / delete in Java (replaces `CREACC.cbl` / `UPDACC.cbl` / `DELACC.cbl`)

**Scope**

* On **create**, insert an initial `ACCOUNT_BALANCE` row at the specified/default (`GBP`)
  currency. On **update/delete**, maintain balance rows accordingly (delete cascades to
  `ACCOUNT_BALANCE`).

**Affected files**

* `web/db2/Account.java` — `createAccount(...)`, `updateAccount(...)`, `deleteAccount(...)`
  manage `ACCOUNT_BALANCE` rows.
* `api/json/AccountsResource.java` — corresponding create/update/delete endpoints.

**Acceptance criteria**

* With the account toggle **on**, creating an account also creates one `ACCOUNT_BALANCE`
  row (specified currency, else `GBP`) with the opening balance.
* Deleting an account removes its `ACCOUNT_BALANCE` rows (FK `ON DELETE CASCADE`).
* Update preserves/maintains balance rows without data loss.
* GBP behavior matches the COBOL golden baseline (Ticket 3).

**Dependencies:** Tickets 1, 3, 4, 5.

---

## Ticket 10 — Propagate currency through Spring Boot + z/OS Connect

**Scope**

* Add a `currency` field to the payment interface DTO and propagate it through the
  z/OS Connect service JSON schemas and the associated `.aar`/`.sar` mappings.

**Affected files**

* `src/Z-OS-Connect-Payment-Interface/src/main/java/com/ibm/cics/cip/bank/springboot/paymentinterface/jsonclasses/paymentinterface/DbcrJson.java`
  — add a `commCurrency` (`CommCurrency`) field, defaulting to `GBP`.
* `src/zosconnect_artefacts/services/**/bin/schemas/*.json` — regenerate/update affected
  request/response schemas, notably the `Pay` service and the `CSacc*` services
  (`CSacccre`, `CSaccenq`, `CSaccupd`, `CSaccdel`).
* z/OS Connect `.aar`/`.sar` mappings under `etc/install/springBootUI/aarfiles` and
  `etc/install/springBootUI/sarfiles` (note: the actual repo location is
  `etc/install/springBootUI/…`, not `etc/install/base/…`).

**Acceptance criteria**

* The payment DTO carries `currency` end-to-end (Spring Boot → z/OS Connect → Java/COBOL).
* Updated JSON schemas validate the new `currency` field and default to `GBP` when omitted,
  preserving backward compatibility for existing clients.
* `.aar`/`.sar` mappings reflect the new field so deployed services accept and forward it.

**Dependencies:** Ticket 7 (debit/credit currency), Ticket 8 (transfer currency).

---

## Delivered in this change (Tickets 1–2)

Tickets 1 and 2 are implemented in this PR:

* **Ticket 1** — `ACCOUNT_BALANCE` DDL (table + unique index + FK, `ACCOUNT` primary key)
  and `PROCTRAN_CURRENCY` in `etc/install/base/db2jcl/INSTDB2.jcl`; matching drop logic in
  `DROPDB2.jcl`; DECLARE-TABLE copybook updates in `ACCDB2.cpy` and `PROCDB2.cpy`.
* **Ticket 2** — migration/backfill job `etc/install/base/db2jcl/MIGMCUR.jcl`.

All changes are backward compatible: existing accounts/transactions default to `GBP`,
the COBOL programs remain runnable, and no Java capability is switched over yet (that work
is gated behind the Ticket-4 toggle).
