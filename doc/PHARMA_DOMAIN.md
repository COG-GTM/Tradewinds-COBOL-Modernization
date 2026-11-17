# Menarini Pharma Trade Desk: domain guide

This fork re-themes the IBM CICS Bank Sample Application (CBSA) as a **pharma distribution trade desk**, the kind of
order-to-cash back office a European pharma group runs for its pharmacies, hospitals and wholesalers. It is a
**synthetic demo** for a COBOL to Java migration conversation. It is not Menarini code and holds no Menarini data.

The architecture, program names (`BNK1*`, `CREACC`, `DBCRFUN`...), copybook field names, Db2 tables and APIs are
unchanged on purpose. Only the business vocabulary, screens, validation values and generated sample data changed, so
the "before" estate still looks like a real CICS application that grew over time.

## Glossary: old CBSA term to pharma term

| CBSA (banking) | Trade Desk (pharma) | Where it shows |
|---|---|---|
| Bank / CICS Banking Sample Application | Menarini Pharma Trade Desk | 3270 title bars, `GETCOMPY`, web UIs |
| Customer | Trade customer: a pharmacy, hospital, clinic or wholesaler, held under its pharmacist or buyer of record | all customer screens |
| Date of birth | Licence date (the date the pharmacy or hospital licence was registered) | create/display customer |
| Credit score | Credit rating (from the credit agencies `CRDTAGY1-5`) | customer screens |
| Account | Trade account | all account screens |
| Sort code | Depot code (the distribution centre that serves the account) | everywhere |
| Interest rate | Trade discount % | create/update account |
| Overdraft limit | Credit limit | create/update account |
| Available balance / actual balance | Available credit / ledger balance | account screens |
| Credit / debit funds | Post a payment (credit) or an invoice (debit) | menu option 6, payment UI |
| Transfer funds | Transfer credit between trade accounts (credit-note reallocation) | menu option 7 |

## Trade account types

| Old type | New type | Meaning | Rules carried over |
|---|---|---|---|
| `CURRENT` | `RX` | Prescription medicines sold to a pharmacy | none |
| `ISA` | `OTC` | Over-the-counter and consumer health products | none |
| `SAVING` | `EXPORT` | Export distributor account | none |
| `LOAN` | `TENDER` | Regional tender contract (ASL / regional health authority) | discount cannot be 0; no counter payments through the payment interface |
| `MORTGAGE` | `HOSPITAL` | Hospital supply contract | discount cannot be 0; no counter payments through the payment interface |

`TENDER` and `HOSPITAL` keep the special handling the bank gave `LOAN` and `MORTGAGE` in `DBCRFUN` and `BNK1UAC`:
they are contract accounts settled through the tender process, so the payment interface refuses to post to them.

## Sample data

`BANKDATA` now generates Italian pharmacists and buyers (names, `Via`/`Viale`/`Corso`... addresses, Italian towns),
so the seeded customers look like a pharmacy network instead of retail bank customers.
