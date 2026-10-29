      ******************************************************************
      *                                                                *
      *  Copyright IBM Corp. 2023                                      *
      *                                                                *
      ******************************************************************
      * JSON PARSE receiver for one account object returned by the
      * account data service (/banking/data/account, see dataservice/
      * account/API.md). Keys and type arrive fixed width, dates as
      * DDMMYYYY, decimals as strings (convert with FUNCTION NUMVAL).
      * Use with COPY ACCJSONN as the last items of the NAME phrase.
             05 JA-EYE                PIC X(4).
             05 JA-CUSTNO             PIC X(10).
             05 JA-SCODE              PIC X(6).
             05 JA-ACCNO              PIC X(8).
             05 JA-ACC-TYPE           PIC X(8).
             05 JA-INT-RATE           PIC X(12).
             05 JA-OPENED             PIC X(8).
             05 JA-OVERDRAFT          PIC 9(8).
             05 JA-LAST-STMT          PIC X(8).
             05 JA-NEXT-STMT          PIC X(8).
             05 JA-AVAIL-BAL          PIC X(16).
             05 JA-ACTUAL-BAL         PIC X(16).
