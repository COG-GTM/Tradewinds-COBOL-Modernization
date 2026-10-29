//BANKDATA JOB 'DATA',NOTIFY=&SYSUID,CLASS=A,MSGCLASS=H,
//          MSGLEVEL=(1,1),REGION=4M
//*
//* Copyright IBM Corp. 2023
//*
//*
//*
//*
//* Change @BANK_PREFIX@ to be CBSA.CICSBSA
//*  - the high level qualifiers of your VSAM *
//*
//* Change @BANK_JSON_DIR@ to a z/OS UNIX directory, e.g. /tmp
//* - where BANKDATA writes the generated ACCOUNT rows (JSON)
//*
//* Change @BANK_DATASVC_URL@ to e.g. http://localhost:19080
//* - scheme, host and port of the Liberty web tier that hosts
//*   /banking/data (the account data service)
//*
//* Change @BANK_SORTCODE@ to 987654
//* - the SORTCODE compiled into BANKDATA (copybook SORTCODE)
//*
//* Change @BANK_LOADLIB@ to CBSA.CICSBSA.LOADLIB
//* - to the name of the load library
//*
//******************************************************************
//******************************************************************
//******************************************************************
//* BANKDAT1                                                       *
//*                                                                *
//*
//*
//*                                                                *
//******************************************************************
//BANKDAT0 EXEC PGM=IDCAMS
//SYSOUT DD SYSOUT=*
//SYSPRINT DD SYSOUT=*
//SYSIN DD *
  DELETE @BANK_PREFIX@.ABNDFILE
  SET MAXCC=0
  DEFINE CLUSTER (NAME(@BANK_PREFIX@.ABNDFILE)-
                     CYL(6 6) -
                     KEYS(12 0) -
                     RECORDSIZE(681 681) -
                     SHAREOPTIONS(2 3) -
                     INDEXED -
                     LOG(NONE) -
                     REUSE -
                     FREESPACE(3 3))-
            DATA (NAME(@BANK_PREFIX@.ABNDFLE.DATA) -
                 ) -
            INDEX (NAME(@BANK_PREFIX@.ABNDFILE.INDEX) -
                 )
/*
//BANKDAT1 EXEC PGM=IDCAMS
//SYSOUT DD SYSOUT=*
//SYSPRINT DD SYSOUT=*
//SYSIN DD *
  DELETE @BANK_PREFIX@.CUSTOMER
  SET MAXCC=0
   DEFINE CLUSTER (NAME(@BANK_PREFIX@.CUSTOMER)-
                      CYL(50 50) -
                      KEYS(16 4) -
                      RECORDSIZE(259,259) -
                      SHAREOPTIONS(2 3) -
                      INDEXED -
                       -
                      LOG(UNDO) -
                       -
                       -
                     ) -
             DATA (NAME(@BANK_PREFIX@.CUSTOMER.DATA) -
                  ) -
             INDEX (NAME(@BANK_PREFIX@.CUSTOMER.INDEX) -
                  )
/*
/*
//******************************************************************
//*
//******************************************************************
//* BANKDAT5                                                       *
//*                                                                *
//* Executes Cobol program BANKDATA, which generates random data   *
//*                                                                *
//* The parameters are:                                            *
//* 1) Starting Customer Number                                    *
//* 2) Final Customer Number                                       *
//* 3) Customer Number Increment (1 for every number)              *
//* 4) Random number seed                                          *
//* 6) Optionally. supply a named counter pool                     *
//******************************************************************
//******************************************************************
//BANKDAT5 EXEC PGM=BANKDATA,REGION=0M,
//         PARM='1,10000,1,1000000000000000'
//STEPLIB  DD DISP=SHR,DSN=@BANK_LOADLIB@
//VSAM     DD DISP=SHR,DSN=@BANK_PREFIX@.CUSTOMER
//*INPUT FILES
//*OUTPUT FILES
//* ACCOUNT rows as the JSON array body of the reset endpoint
//ACCJSON  DD PATH='@BANK_JSON_DIR@/bankdata-accounts.json',
//            PATHOPTS=(OWRONLY,OCREAT,OTRUNC),
//            PATHMODE=(SIRUSR,SIWUSR,SIRGRP),
//            FILEDATA=TEXT
//SYSPRINT DD SYSOUT=*
//SYSABOUT DD SYSOUT=*
//SYSDBOUT DD SYSOUT=*
//DISPLAY  DD SYSOUT=*
//SYSOUT   DD SYSOUT=*
//******************************************************************
//* BANKDAT6                                                       *
//*                                                                *
//* POSTs the generated ACCOUNT rows to the account data service,  *
//* which replaces the ACCOUNT and account CONTROL rows for the    *
//* sortcode in one transaction (formerly Db2 SQL in BANKDATA).    *
//* The file is converted from EBCDIC to UTF-8 first; curl -f      *
//* gives a non-zero RC on any HTTP error. Every STDPARM line is a *
//* complete command ending in &&, so the script works whether     *
//* BPXBATCH joins the records with or without newlines.           *
//******************************************************************
//BANKDAT6 EXEC PGM=BPXBATCH,COND=(4,LT)
//STDOUT   DD SYSOUT=*
//STDERR   DD SYSOUT=*
//STDPARM  DD *
SH cd @BANK_JSON_DIR@ &&
 F=bankdata-accounts.utf8.json &&
 iconv -f IBM-1047 -t UTF-8 bankdata-accounts.json > $F &&
 U=@BANK_DATASVC_URL@/banking/data/admin/account &&
 Q="$U/reset?sortcode=@BANK_SORTCODE@" &&
 H='Content-Type: application/json' &&
 curl -sS -f -X POST -H "$H" --data-binary @$F "$Q"
/*
