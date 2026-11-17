# Menarini Pharma Trade Desk: demo flow

A 20 to 25 minute COBOL to Java modernization demo on this fork. It is synthetic: no Menarini code or data.
Story: Menarini's order-to-cash trade desk (pharmacies, hospitals, wholesalers) still runs on COBOL/CICS with 3270 screens.
Devin explains it, plans the migration, and ports one business service to Java/Spring with parity tests, on the
stack Menarini's Florence team already uses (Java, Spring, Maven, Git, Jenkins, Jira, Oracle/PostgreSQL).

See [PHARMA_DOMAIN.md](PHARMA_DOMAIN.md) for the glossary (customer = pharmacy/hospital, account = trade account,
overdraft = credit limit, interest = trade discount, sort code = depot code).

## Before the call (10 min)

1. Render the legacy 3270 screens (no CICS needed):
   ```sh
   python3 tools/bms_preview.py -o bms_preview.html     # 9 screens; open in a browser
   python3 tools/bms_preview.py --text src/base/bms_src/BNK1MAI.bms   # one screen as text
   ```
2. Build and serve the React "after" UI (the backend calls fail without z/OS Connect, the screens still render):
   ```sh
   cd src/bank-application-frontend
   YARN_IGNORE_PATH=1 corepack yarn install
   CI=false YARN_IGNORE_PATH=1 corepack yarn build
   mkdir -p /tmp/site && rm -rf /tmp/site/webui-1.0 && cp -r build /tmp/site/webui-1.0
   python3 -m http.server 3000 -d /tmp/site            # http://localhost:3000/webui-1.0/
   ```
3. Optional: the Spring Boot Customer Services UI shows the same pharma forms on port 19080 (stop the payment UI first, they share the port):
   ```sh
   cd src/Z-OS-Connect-Customer-Services-Interface && mvn -q package -DskipTests
   java -DCBSA_ZOSCONN_HOST=localhost -DCBSA_ZOSCONN_PORT=30701 -jar target/customerservices-1.0.war
   # http://localhost:19080/customerservices-1.0/
   ```
4. Open the repo's DeepWiki and a fresh Devin session so the live steps start instantly.

## Act 1: the "before" (5 min)

| Show | Say |
|---|---|
| `bms_preview.html`, Main Menu (`BNK1MAI`) | "This is the trade desk today: a CICS 3270 green screen. Option 6 posts a payment or invoice, option 7 moves credit between trade accounts." |
| Create Trade Account (`BNK1CAM`) and Update Trade Account (`BNK1UAM`) | Account types RX / OTC / EXPORT / TENDER / HOSPITAL, credit limit, trade discount %, depot code. |
| `src/base/cobol_src/DBCRFUN.cbl` (about 860 lines) | Post payment/invoice. Business rules sit next to Db2 SQL and CICS calls. |
| `src/base/cobol_src/CRECUST.cbl` | Onboarding a pharmacy fans out to five credit agencies (`CRDTAGY1-5`) in parallel with `EXEC CICS RUN TRANSID` and averages the credit rating. |
| `src/base/cobol_copy/` copybooks | The data contract: fixed-width records, `COMP-3` packed decimals. |

Rules worth pointing at (all in the COBOL today):
- `DBCRFUN`: a payment from the payment interface is refused on `TENDER` and `HOSPITAL` contract accounts (fail code 4),
  and refused when it would take available credit below zero (fail code 3). Teller postings skip both checks.
- `CREACC`: a trade customer can hold at most 10 trade accounts (fail code 8).
- `XFRFUN`: a credit transfer must be a positive amount (fail code 4).

## Act 2: Devin understands and plans (5 min, live)

1. **Ask DeepWiki / Devin**: "How does posting a payment to a trade account work end to end, from the 3270 screen
   to Db2? Which rules apply to TENDER and HOSPITAL accounts?" The answer should cite `BNK1CRA` → `DBCRFUN` → Db2
   `ACCOUNT` / `PROCTRAN`.
2. **Inventory prompt** (Devin session):
   > Inventory the COBOL in src/base: list each program with its CICS transaction, BMS map, Db2 tables and
   > callers. Propose a migration order to Java 17 / Spring Boot 3, smallest risk first, with one PR per program.
   > Write it to doc/MIGRATION_PLAN.md.

## Act 3: Devin ports one service (live start, finished PR shown)

Kick this off live (or from a Jira ticket, which is how Menarini tracks work), then switch to a PR from a rehearsal run:

> Port src/base/cobol_src/DBCRFUN.cbl (post payment/invoice) to Java 17 / Spring Boot 3 in a new Maven module
> src/trade-desk-java. Keep the same inputs and outputs as the DBCRFUN commarea (account number, depot code,
> amount, facility type, success flag, fail code, balances). Use Spring Data JPA on PostgreSQL with the ACCOUNT and
> PROCTRAN columns from the DBCRFUN host variables. Carry over every business rule, including the TENDER/HOSPITAL refusal (fail
> code 4) and the negative available credit refusal (fail code 3) for payment-interface postings. Represent COMP-3
> amounts as BigDecimal with scale 2. Add JUnit tests that walk each COBOL branch and name the COBOL paragraph
> they cover. Expose POST /api/trade-accounts/{accountNumber}/postings. Build with mvn verify and open a PR.

What to show in the result:
- A side by side of the COBOL paragraph and the Java method for the TENDER/HOSPITAL rule.
- The JUnit parity tests, one per COBOL branch, all green in CI (Jenkins at Menarini, GitHub Actions here).
- The PR itself: small, reviewable, one program per PR, which is how the rest of the plan would run in parallel.

## Act 4: the "after" (3 min)

| Show | Say |
|---|---|
| React app, `/webui-1.0/` home and Control Panel | Same business, modern UI: Carbon React on REST APIs. |
| Create Trade Account | RX / OTC / EXPORT / TENDER / HOSPITAL dropdown, credit limit, trade discount %. |
| View Account / View Customer | Depot code, available credit, ledger balance, licence date, credit rating. |

Close: "The same pattern scales program by program: Devin plans, ports one slice per PR with parity tests, your
team reviews. Next step is to run it on one of your own systems: an M-Field module, or an AS/400 RPG/COBOL job."

## Caveats to say out loud

- The 3270 screens are rendered from the BMS source, not a live CICS region, and the React and Spring UIs have no
  backend in this demo, so saves fail. The COBOL rules are shown in code, not executed.
- This is IBM's CICS Bank Sample Application (EPL-2.0) re-themed for pharma distribution. It is not Menarini code.
