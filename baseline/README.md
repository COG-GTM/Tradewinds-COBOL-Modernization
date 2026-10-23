# CBSA legacy baseline (GnuCOBOL harness)

This directory is the behavioural baseline for the Java 21 migration. Every
transcript under `transcripts/` was produced by **actually running the legacy
COBOL** (`src/base/cobol_src`, unmodified) under GnuCOBOL 3.1 with a thin
CICS/DB2/VSAM stub runtime (`legacy/`). Nothing here is hand-written expected
output: there are **no golden fixtures**.

| Path | Content |
|---|---|
| `seed/seed.sql` | Canonical seed (30 customers, 76 accounts of all 5 types, 8 PROCTRAN, 4 CONTROL), canonical column names, sort code 987654 |
| `seed/schema-legacy-harness.sql` | SQLite DDL used by the harness (CUSTOMER per the canonical contract; ACCOUNT/PROCTRAN/CONTROL with DB2 DDL names from `etc/install/base/db2jcl/CRETB0*.jcl`) |
| `transcripts/<PROGRAM>/<case>.json` | 107 recorded flows |
| `results/legacy-results.json` | Output of `legacy/run-all.sh` (107/107 match) |

## Runtime classification (per program)

All 29 programs in `src/base/cobol_src` are translated by `legacy/tools/cbsa_pp.py`
and compile under `cobc -std=ibm`. **Every program below runs under GnuCOBOL + stubs**;
none falls back to golden fixtures.

| Program | How it runs | Transcripts |
|---|---|---|
| GETCOMPY, GETSCODE | GnuCOBOL + stubs, LINK | 1 + 1 |
| INQCUST | GnuCOBOL + stubs, LINK (VSAM CUSTOMER read, random/last customer) | 6 |
| CRECUST | GnuCOBOL + stubs, LINK; async credit check via RUN TRANSID / FETCH ANY / containers | 8 |
| CRDTAGY1-5 | GnuCOBOL + stubs, run as **child tasks** of CRECUST (OCR1-OCR5); visible in every CRECUST transcript's `trace` (`RUN_TRANSID`, `LINK CRDTAGYn`, `CHILD_DONE`, `FETCH_CHILD`) | (via CRECUST) |
| UPDCUST, DELCUS | GnuCOBOL + stubs, LINK | 6 + 5 |
| CREACC, INQACC, INQACCCU, UPDACC, DELACC | GnuCOBOL + stubs, LINK (DB2 ACCOUNT/PROCTRAN/CONTROL, cursors) | 10 + 5 + 5 + 5 + 3 |
| DBCRFUN, XFRFUN | GnuCOBOL + stubs, LINK (incl. SYNCPOINT ROLLBACK paths) | 12 + 8 |
| ABNDPROC | GnuCOBOL + stubs, LINK (abend record written to the ABNDFILE event) | 1 |
| BANKDATA | GnuCOBOL + stubs, **batch** (`PARM='1,30,1,42'`); generates `seed/seed.sql` via `legacy/make-seed.sh` | (seed) |
| BNKMENU, BNK1DCS, BNK1CCS, BNK1CAC, BNK1DAC, BNK1UAC, BNK1CRA, BNK1TFN, BNK1CCA | GnuCOBOL + stubs, **terminal mode**: BMS maps generated from `src/base/bms_src` by `legacy/tools/bms2cpy.py`; RECEIVE MAP input / SEND MAP output as named map fields | 12, 4, 3, 3, 2, 1, 2, 2, 2 |

Screen transcripts cover a single pseudo-conversational step each (first display,
or one ENTER/PF key with the map fields given). Multi-step conversations that
depend on state carried in a previous step's COMMAREA (e.g. BNK1DCS PF5 delete,
BNK1UAC PF5 apply) are not recorded; the underlying business programs
(DELCUS, UPDACC) are covered directly.

## How to run

Prerequisites: `cobc` (GnuCOBOL 3.x, `apt-get install gnucobol libcob4-dev`), `gcc`,
`libsqlite3-dev`, `python3`.

```bash
legacy/build.sh                       # translate + compile all 29 programs, build legacy/build/cbsarun
legacy/make-seed.sh                   # BANKDATA + seed activity -> baseline/seed/seed.sql (deterministic)
legacy/run-transcript.sh baseline/transcripts/XFRFUN/ok.json   # MATCH or field-level DIFF, exit 0/1
legacy/run-all.sh                     # replay all -> baseline/results/legacy-results.json
legacy/record-all.sh                  # regenerate legacy/cases/*.json and re-record every transcript
```

Each replay copies a fresh SQLite database built from `seed/schema-legacy-harness.sql`
+ `seed/seed.sql`, applies the case's optional `setup_sql`, runs the program and
compares `commarea_out`, `abend` and `db_delta` (and `trace`) with the transcript.

### Pieces

* `legacy/tools/cbsa_pp.py` - rewrites every `EXEC CICS` / `EXEC SQL` block into
  `CALL 'CBSACICS'` / `CALL 'CBSASQL'` with the operands passed BY REFERENCE plus a
  per-program metadata file describing the command; `FUNCTION RANDOM` becomes
  `CBSARND`/`CBSARNDS`; adds DFHEIBLK/DFHCOMMAREA linkage.
* `legacy/runtime/cbsastub.c` - the stub runtime (C, libcob API + SQLite) and the
  `cbsarun` driver. CICS: LINK (dynamic CALL with COMMAREA), RETURN [TRANSID], ABEND,
  HANDLE ABEND, ASSIGN, ASKTIME, FORMATTIME, DELAY (virtual time), ENQ/DEQ,
  SYNCPOINT [ROLLBACK], READ [UPDATE/TOKEN] / WRITE / REWRITE / DELETE / STARTBR /
  READNEXT / READPREV / ENDBR on the CUSTOMER file (backed by table CUSTOMER),
  RUN TRANSID CHILD / FETCH [ANY] / PUT & GET CONTAINER, BIF DEEDIT, INQUIRE,
  SET TERMINAL, SEND MAP/TEXT/CONTROL, RECEIVE MAP. DB2: SELECT INTO, INSERT,
  UPDATE, DELETE, cursors (DECLARE/OPEN/FETCH/CLOSE), COMMIT/ROLLBACK, SQLCODE/SQLERRD.
  LE: CEEDAYS, CEELOCT, CEEGMT, CEEDATM.
* `legacy/tools/cobcopy.py` - copybook parser used to encode/decode COMMAREAs as named fields.
* `legacy/tools/harness.py` - record / replay / replay-all / apply.

### Named counters and the customer control record

CBSA's customer counter is the VSAM control record (key sort code `000000`,
number `9999999999`, copybook CUSTCTRL) read/rewritten under `ENQ`. The harness
stores it as CONTROL rows `987654-CUSTOMER-COUNT` / `987654-CUSTOMER-LAST`, next to
the DB2 rows `987654-ACCOUNT-COUNT` / `987654-ACCOUNT-LAST` used by CREACC. The
programs do not use `GET/PUT COUNTER` (not present in the source); ENQ/DEQ are
recorded but always granted (single-task harness).

## Determinism

* **Time** - `CBSA_FIXED_TIME` (ISO-8601, default `2026-01-15T10:30:00Z`) drives
  ASKTIME/FORMATTIME, the LE date services and DB2 `CURRENT DATE/TIMESTAMP`. The
  driver also sets GnuCOBOL's `COB_CURRENT_DATE` so `FUNCTION CURRENT-DATE`
  (BANKDATA, CRECUST review date) uses the same instant. `EXEC CICS DELAY` does
  not sleep; it advances the task's virtual clock (so e.g. CRECUST's PROCTRAN
  time is 10:30:03 after its 3-second DELAY).
* **Task numbers** - `EIBTASKN` = `CBSA_TASKN` (default 100). Child task *n*
  (RUN TRANSID order, 1-based) gets `EIBTASKN = parent + n`.
* **Random** - `CBSA_RANDOM_SEED` (default 0; transcripts record `42` unless the
  case overrides it). `FUNCTION RANDOM` is replaced by a `java.util.Random`-compatible
  generator so the Java service can reproduce it with `new Random(seed)`:
  * `RANDOM(s)` (with argument): `state = ((CBSA_RANDOM_SEED + s) XOR 0x5DEECE66D) mod 2^48`,
    then returns `nextDouble()`.
  * `RANDOM` (no argument): `nextDouble()` from the current state.
  * `next(bits)`: `state = (state * 0x5DEECE66D + 0xB) mod 2^48; return state >>> (48 - bits)`;
    `nextDouble() = ((next(26) << 27) + next(27)) * 2^-53` - i.e. exactly
    `new java.util.Random(CBSA_RANDOM_SEED + s).nextDouble()`.

### Credit-agency algorithm (CRECUST + CRDTAGY1-5)

1. CRECUST puts the customer into container `CIPA`..`CIPE` on channel
   `CIPCREDCHANN` and issues `RUN TRANSID('OCR1'..'OCR5') CHILD`.
2. Each CRDTAGYn (task number `T = parent EIBTASKN + n`) computes
   `delay = INT((3-1) * RANDOM(T)) + 1` (1-2 s, virtual) then
   `score = INT((999-1) * RANDOM) + 1` and puts it back in its container. With the
   generator above: `r = new Random(seed + T); delay = (int)(2*r.nextDouble())+1;
   score = (int)(998*r.nextDouble())+1`.
3. CRECUST `DELAY`s 3 s then loops `FETCH ANY NOSUSPEND`; every child has finished
   (max delay 2 s < 3 s), so all 5 scores are averaged:
   `credit score = INT(sum / count)`.
4. Review date = `FUNCTION CURRENT-DATE` + `INT((21-1) * RANDOM(EIBTASKN)) + 1` days
   (EIBTASKN of the CRECUST task, i.e. `new Random(seed + taskn)`).

Example: seed 42, task 100, fixed time 2026-01-15 -> see
`transcripts/CRECUST/ok-seed-42.json`.

## Seed

`legacy/make-seed.sh` runs BANKDATA (`PARM='1,30,1,42'`, `CBSA_RANDOM_SEED=42`,
fixed time `2026-01-15T10:30:00Z`) which writes the CUSTOMER VSAM file and the DB2
ACCOUNT/CONTROL rows, loads the customers into CUSTOMER, then runs
`legacy/cases/seed-activity.json` through the harness (CREACC MORTGAGE accounts
74 and 75 - BANKDATA's 30-customer run creates none - a CURRENT account 76 with
overdraft 500 and balance 0, four DBCRFUN payments and one XFRFUN transfer, which
produce the PROCTRAN rows), and dumps everything to `seed/seed.sql`. Re-running it
reproduces the file byte for byte. Dates are written as ISO strings
(`'1979-07-03'`), which SQLite, H2 and PostgreSQL all accept for DATE columns.

## Transcript format

```json
{ "program": "XFRFUN", "flow": "transfer", "case": "ok", "description": "...",
  "mode": "link", "env": {"CBSA_FIXED_TIME": "...", "CBSA_RANDOM_SEED": "42", "CBSA_TASKN": "100"},
  "setup_sql": [], "commarea_in": {...named fields...}, "commarea_out": {...},
  "abend": null | {"code": "SAME", "program": "XFRFUN"},
  "db_delta": {"CUSTOMER|ACCOUNT|PROCTRAN|CONTROL": {"inserted": [rows], "updated": [{"key", "changes": {col: [old, new]}}], "deleted": [rows]}},
  "trace": [["LINK", "INQCUST", "269"], ...] }
```

For terminal-mode transcripts `commarea_in` is `{aid, commarea, map_fields}` and
`commarea_out.screen` lists `RECEIVE_MAP`, `SEND_MAP`, every non-empty `FIELD`
sent, `SEND_TEXT`/`SEND_CONTROL` and the `RETURN_TRANSID`/`RETURN_COMMAREA`.
Numeric COMMAREA dates are DDMMYYYY integers as in the copybooks.

## Legacy defects and oddities observed (not fixed)

1. **Abend time `HH:MM:MM`** - the `ABND-TIME` STRING concatenates
   `WS-TIME-NOW-GRP-MM` twice instead of minutes then seconds. Present in 23
   programs (all BNK1*, BNKMENU, CRDTAGY1-5, CREACC, CRECUST, DBCRFUN, DELACC,
   DELCUS, INQACC, INQACCCU, INQCUST, XFRFUN). Found by reading the source; no
   recorded flow reaches those abend paths.
2. **DBCRFUN ignores the overdraft limit** - a payment-interface debit
   (`COMM-FACILTYPE = 496`) fails with code 3 whenever `available balance + amount < 0`,
   even inside the overdraft (`DBCRFUN/within-overdraft`: balance 0, limit 500, debit 400 -> refused).
3. **Teller debits have no funds check** - with any FACILTYPE other than 496 a
   debit can drive any account negative (`DBCRFUN/insufficient-funds-teller`,
   `BNK1CRA/debit-insufficient`).
4. **LOAN/MORTGAGE** - only payment-interface requests are refused (fail code 4);
   teller credits/debits are applied (`DBCRFUN/loan-teller`).
5. **Zero amount** - DBCRFUN with amount 0.00 succeeds and still writes a PROCTRAN row.
6. **XFRFUN has no funds check** - `XFRFUN/insufficient-funds` succeeds and leaves
   the from-account negative; the same applies to BNK1TFN.
7. **XFRFUN same account** abends the task with `SAME` instead of returning a fail code.
8. **XFRFUN to-account not found** returns fail code 2 after two SYNCPOINT ROLLBACKs.
9. **UPDACC does not validate the account type** - `CHEQUE` is written
   (`UPDACC/invalid-type`); only a blank type is rejected. Balances in the
   COMMAREA are ignored.
10. **UPDCUST** only updates name/address; DOB and credit score in the COMMAREA
    are ignored. A blank name keeps the old name.
11. **BNK1DCS non-numeric customer number** - `BIF DEEDIT` turns `ABC` into
    `0000000000`, which INQCUST treats as "random customer", so the screen shows a
    random customer with "Customer lookup successful.".
12. **BNK1CAC overdraft length underflow** - `SUBTRACT <trailing blanks> FROM OVERDRL`
    assumes the field is transmitted with its trailing blanks. If a terminal sends
    a short field (e.g. `0` with L=1) OVERDRL goes negative and
    `INSPECT OVERDRI(1:OVERDRL)` is an invalid reference modification (GnuCOBOL
    runtime crash; undefined on z/OS without SSRANGE). Screen transcripts therefore
    send fields blank-padded to their full width, as a 3270 does for a field
    pre-filled with blanks.
13. **BANKDATA account data** - with `1,30,1,42` it creates no MORTGAGE accounts;
    LAST/NEXT statement dates are fixed literals; CURRENT overdraft limit is always 100.
14. **CRECUST review date and BANKDATA use `FUNCTION CURRENT-DATE`** (the real
    system clock), not CICS time - the only wall-clock dependency in the estate;
    the harness pins it via `COB_CURRENT_DATE`.
15. **Credit scores depend on the CICS task number** (`RANDOM(EIBTASKN)`), so on a
    real region two identical requests get different scores.
16. **Typo** in DELCUS abend free-form text: "Unbale to DELETE CUSTOMER VSAM rec".
17. **INQACCCU** returns success with 0 accounts for an existing customer without
    accounts, but fail code 1 for a non-existent customer.
18. **CREACC** rejects lower-case types (`isa`, fail code A); the 10th account is
    refused with fail code 8 (`> 9` accounts check), the 9th is allowed.

## Duplicate note

Earlier XFRFUN-only PRs #1, #2 and #4 are superseded by this programme.
