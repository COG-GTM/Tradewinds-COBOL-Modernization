//MIGMCUR JOB 'DB2',NOTIFY=&SYSUID,CLASS=A,MSGCLASS=H,
//          MSGLEVEL=(1,1),REGION=4M
//*
//* Copyright IBM Corp. 2023, 2026
//*
//* CBSA Modernization - Ticket 2 : Multi-currency data migration.
//*
//* Applies the Ticket 1 schema to an EXISTING CBSA Db2 subsystem and
//* backfills per-account balances and PROCTRAN currency to GBP.
//* This job is intended to be run ONCE against a subsystem that was
//* installed before ACCOUNT_BALANCE / PROCTRAN_CURRENCY existed.
//* A fresh install via INSTDB2 already contains this schema and does
//* not need this job.
//*
// EXPORT SYMLIST=*
// JCLLIB ORDER=CBSA.DB2.JCL.INSTALL
// INCLUDE MEMBER=DEFAULT
//*
//* Please change DSNV12DP to the appropriate value for your installation
//* This is the integrated catalog facility catalog for the storage group
//*
//* This is your Db2 load library
//JOBLIB  DD  DISP=SHR,DSN=&DB2HLQ..SDSNLOAD
//MIGRATE EXEC PGM=IKJEFT01,DYNAMNBR=20
//SYSTSPRT DD  SYSOUT=*
//SYSPRINT DD  SYSOUT=*
//SYSUDUMP DD  SYSOUT=*
//SYSTSIN  DD  *,SYMBOLS=(EXECSYS)
  DSN SYSTEM(&DB2SYS.)
  RUN PROGRAM(DSNTEP2)  PLAN(&DSNTEPP) -
       LIB('&DSNTEPL') PARMS('/ALIGN(MID)')
  END
//SYSIN    DD  *,SYMBOLS=(EXECSYS)
SET CURRENT SQLID = '&DB2OWNER';

--
-- Ticket 1 schema, applied to an already-installed CBSA subsystem.
-- The ACCOUNT primary key reuses the existing ACCTINDX unique index,
-- which is required before ACCOUNT_BALANCE can reference ACCOUNT.
--
ALTER TABLE ACCOUNT
   ADD PRIMARY KEY (ACCOUNT_SORTCODE, ACCOUNT_NUMBER);

CREATE STOGROUP ACCTBAL VOLUMES('*','*','*','*','*') VCAT DSNV12DP;

CREATE TABLESPACE ACCTBAL IN CBSA USING STOGROUP ACCTBAL;

CREATE TABLE ACCOUNT_BALANCE (
                    ACCOUNT_SORTCODE               CHAR(6) NOT NULL,
                    ACCOUNT_NUMBER                 CHAR(8) NOT NULL,
                    ACCOUNT_CURRENCY               CHAR(3) NOT NULL,
                    AVAILABLE_BALANCE              DECIMAL(12, 2) NOT NULL
                                                   WITH DEFAULT,
                    ACTUAL_BALANCE                 DECIMAL(12, 2) NOT NULL
                                                   WITH DEFAULT,
                    PRIMARY KEY (ACCOUNT_SORTCODE,
                                 ACCOUNT_NUMBER,
                                 ACCOUNT_CURRENCY),
                    CONSTRAINT ACCTBAL_FK
                       FOREIGN KEY (ACCOUNT_SORTCODE, ACCOUNT_NUMBER)
                       REFERENCES ACCOUNT (ACCOUNT_SORTCODE, ACCOUNT_NUMBER)
                       ON DELETE CASCADE
                   )
IN CBSA.ACCTBAL   NOT VOLATILE
CARDINALITY  AUDIT NONE  DATA CAPTURE NONE;

CREATE UNIQUE INDEX ACCBALIX
  ON ACCOUNT_BALANCE(ACCOUNT_SORTCODE,ACCOUNT_NUMBER,ACCOUNT_CURRENCY)
  USING STOGROUP ACCTBAL;

ALTER TABLE PROCTRAN
   ADD COLUMN PROCTRAN_CURRENCY CHAR(3) NOT NULL WITH DEFAULT 'GBP';

COMMIT;

--
-- Ticket 2 backfill : one GBP ACCOUNT_BALANCE row per existing ACCOUNT,
-- copying the current single-column balances. Guarded by NOT EXISTS so
-- the statement can be re-run without creating duplicates.
--
INSERT INTO ACCOUNT_BALANCE
       (ACCOUNT_SORTCODE, ACCOUNT_NUMBER, ACCOUNT_CURRENCY,
        AVAILABLE_BALANCE, ACTUAL_BALANCE)
SELECT A.ACCOUNT_SORTCODE, A.ACCOUNT_NUMBER, 'GBP',
       A.ACCOUNT_AVAILABLE_BALANCE, A.ACCOUNT_ACTUAL_BALANCE
FROM   ACCOUNT A
WHERE  A.ACCOUNT_EYECATCHER = 'ACCT'
  AND  NOT EXISTS (SELECT 1
                   FROM   ACCOUNT_BALANCE B
                   WHERE  B.ACCOUNT_SORTCODE = A.ACCOUNT_SORTCODE
                     AND  B.ACCOUNT_NUMBER   = A.ACCOUNT_NUMBER
                     AND  B.ACCOUNT_CURRENCY = 'GBP');

--
-- Ticket 2 backfill : default currency on pre-existing transactions.
-- (ALTER ... WITH DEFAULT already materialises 'GBP' for existing rows;
--  this makes the intent explicit and covers any blank values.)
--
UPDATE PROCTRAN
   SET PROCTRAN_CURRENCY = 'GBP'
 WHERE PROCTRAN_CURRENCY IS NULL
    OR PROCTRAN_CURRENCY = ' ';

COMMIT;
/*
