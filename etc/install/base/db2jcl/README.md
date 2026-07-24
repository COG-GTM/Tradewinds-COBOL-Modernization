# db2jcl

These are Job Control Language (JCL) files used to build the Db2 parts of the base application.

`MIGMCUR.jcl` is a one-off migration job for the multi-currency-per-account work. Run it
against a CBSA subsystem that was installed **before** the `ACCOUNT_BALANCE` table and the
`PROCTRAN_CURRENCY` column existed. It applies that schema, creates one `GBP`
`ACCOUNT_BALANCE` row per existing account (copying the current balances), and backfills
`PROCTRAN_CURRENCY` to `GBP`. A fresh install via `INSTDB2.jcl` already contains this schema
and does not need `MIGMCUR.jcl`.
