       CBL CICS('SP,EDF')
      ******************************************************************
      *                                                                *
      *  Copyright IBM Corp. 2023                                      *
      *                                                                *
      ******************************************************************
      ******************************************************************
      * This program takes account information from the BMS
      * application (cust no, name, address and DOB) and then enqueues
      * the Named Counter for ACCOUNT, increments the counter and takes
      * the new account number, & attempt to update the ACCOUNT
      * datastore on DB2. If that is successful, write a rec to
      * the PROCTRAN datastore. Then, if all of that works, DEQUEUE
      * the named counter and return the ACCOUNT number.
      * If for any reason the write to the ACCOUNT or PROCTRAN
      * datatsore is unsuccessful, then we need to decrement the Named
      * Counter (restoring it to the start position) and DEQUEUE the
      * Named Counter.
      *
      *
      ******************************************************************

       IDENTIFICATION DIVISION.
       PROGRAM-ID. CREACC.
       AUTHOR. Jon Collett.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
      * SOURCE-COMPUTER.  IBM-370 WITH DEBUGGING MODE.
       SOURCE-COMPUTER.  IBM-370.
       OBJECT-COMPUTER.  IBM-370.

       INPUT-OUTPUT SECTION.

       DATA DIVISION.
       FILE SECTION.

       WORKING-STORAGE SECTION.


       COPY SORTCODE.



       77 SYSIDERR-RETRY PIC 999.

      * Account data service (DATASVC) areas
       01 WS-DSV.
          COPY DATASVC.

      * Envelope + created account returned by POST /data/account
       01 WS-DSV-RESP.
          03 WS-RESP-SUCCESS            PIC X.
          03 WS-RESP-FAILCODE           PIC X.
          03 WS-RESP-ABEND              PIC X(4).
          03 WS-RESP-ACCOUNT.
             COPY ACCJSON.

       01 WS-PTR                        PIC S9(8) COMP.
       01 WS-DSV-TASKREF                PIC 9(12) VALUE 0.
       01 WS-EDIT-RATE                  PIC Z(3)9.99.
       01 WS-EDIT-OVERDRAFT             PIC Z(7)9.
       01 WS-EDIT-AVAIL-BAL             PIC -(10)9.99.
       01 WS-EDIT-ACTUAL-BAL            PIC -(10)9.99.

       01 PROCTRAN-AREA.
          COPY PROCTRAN.

       01 PROCTRAN-RIDFLD                   PIC S9(8) COMP.

       01 PROCTRAN-RETRY                    PIC 999.
       01 WS-EXIT-RETRY-LOOP                PIC X VALUE ' '.

       01 WS-CICS-WORK-AREA.
          05 WS-CICS-RESP                   PIC S9(8) COMP.
          05 WS-CICS-RESP2                  PIC S9(8) COMP.
          05 WS-EIBRESP-DISPLAY             PIC S9(8) DISPLAY
                                               SIGN LEADING SEPARATE.

       01 WS-CUSTOMER-NO-NUM                PIC 9(10).

       LOCAL-STORAGE SECTION.
       01 FILE-RETRY                        PIC 999.

       01 OUTPUT-DATA.
           COPY ACCOUNT.

       01 OUTPUTC-DATA.
           COPY CUSTOMER.

       01 RETURN-DATA.
          03 RETURN-DATA-EYECATCHER         PIC X(4).
          03 RETURN-DATA-NUMBER             PIC 9(10) DISPLAY.
          03 RETURN-DATA-NAME               PIC X(60).
          03 RETURN-DATA-ADDRESS            PIC X(160).
          03 RETURN-DATA-DATE-OF-BIRTH      PIC 9(8).


       01 CUSTOMER-KY.
          03 REQUIRED-SORT-CODE             PIC 9(6) VALUE 0.
          03 REQUIRED-CUST-NUMBER           PIC 9(10) VALUE 0.

       01 ACCOUNT-KY.
          03 REQUIRED-SORT-CODE2            PIC 9(6) VALUE 0.
          03 REQUIRED-ACC-NUMBER            PIC 9(8) VALUE 0.

       01 RANDOM-CUSTOMER                   PIC 9(10) VALUE 0.
       01 HIGHEST-CUST-NUMBER               PIC 9(10) VALUE 0.

       01 EXIT-VSAM-READ                    PIC X VALUE 'N'.
       01 EXIT-DB2-READ                     PIC X VALUE 'N'.

       01 WS-V-RETRIED                      PIC X VALUE 'N'.
       01 WS-D-RETRIED                      PIC X VALUE 'N'.

       01 WS-ERROR                          PIC X(40) VALUE ALL '#'.

      *
      * CUSTOMER NCS definitions
      *
       01 NCS-ACC-NO-STUFF.
          03 NCS-ACC-NO-NAME.
             05 NCS-ACC-NO-ACT-NAME         PIC X(8)
                                 VALUE 'CBSAACCT'.
             05 NCS-ACC-NO-TEST-SORT        PIC X(6)
                                 VALUE '      '.
             05 NCS-ACC-NO-FILL             PIC XX
                                 VALUE '  '.

          03 NCS-ACC-NO-INC                 PIC 9(16) COMP
                                 VALUE 0.
          03 NCS-ACC-NO-VALUE               PIC 9(16) COMP
                                 VALUE 0.

          03 NCS-ACC-NO-RESP                PIC XX VALUE '00'.



       01 WS-DISP-CUST-NO-VAL               PIC S9(18) DISPLAY.

       01 WS-ACC-REC-LEN                    PIC S9(4) COMP VALUE 0.

       01 NCS-UPDATED                       PIC X VALUE 'N'.

       01 WS-U-TIME                         PIC S9(15) COMP-3.
       01 WS-ORIG-DATE                      PIC X(10).
       01 WS-ORIG-DATE-GRP REDEFINES WS-ORIG-DATE.
          03 WS-ORIG-DATE-DD                PIC 99.
          03 FILLER                         PIC X.
          03 WS-ORIG-DATE-MM                PIC 99.
          03 FILLER                         PIC X.
          03 WS-ORIG-DATE-YYYY              PIC 9999.

       01 DONT-CARE                         PIC 9(8) BINARY.
       01 LEAP-YEAR                         PIC 9(8) BINARY.

       01 WS-ORIG-DATE-GRP-X.
          03 WS-ORIG-DATE-DD-X              PIC XX.
          03 FILLER                         PIC X VALUE '.'.
          03 WS-ORIG-DATE-MM-X              PIC XX.
          03 FILLER                         PIC X VALUE '.'.
          03 WS-ORIG-DATE-YYYY-X            PIC X(4).

       01 WS-STDT-X                         PIC X(8).
       01 WS-STDT-9  REDEFINES WS-STDT-X.
          03 WS-STDT-9-NUM                  PIC 9(8).
       01 WS-STDT-9-NUMERIC                 PIC 9(8).

       01 WS-INTEGER                        PIC S9(9) COMP VALUE 0.

       01 WS-FUTURE-DATE                    PIC 9(8).
       01 WS-FUT REDEFINES WS-FUTURE-DATE.
          03 WS-FUTURE-YY                   PIC 9(4).
          03 WS-FUTURE-MM                   PIC 99.
          03 WS-FUTURE-DD                   PIC 99.

       01 WS-FUTURE-CONV.
          03 WS-FUT-9                       PIC 9(8).
          03 WS-FUT-X REDEFINES WS-FUT-9.
             05 WS-FUT-X-YY                 PIC X(4).
             05 WS-FUT-X-MM                 PIC XX.
             05 WS-FUT-X-DD                 PIC XX.

       01 NCS-ACC-NO-DISP                   PIC 9(16) VALUE 0.

       01 STORED-SORTCODE                   PIC X(6)  VALUE SPACES.
       01 STORED-CUSTNO                     PIC X(10) VALUE SPACES.
       01 STORED-ACCTYPE                    PIC X(8)  VALUE SPACES.
       01 STORED-LST-STMT                   PIC X(8)  VALUE SPACES.
       01 STORED-NXT-STMT                   PIC X(8)  VALUE SPACES.
       01 STORED-ACCNO                      PIC X(8)  VALUE SPACES.

       01 WS-EIBTASKN12                     PIC 9(12) VALUE 0.

       01 ACCOUNT-KY3.
          03 REQUIRED-SORT-CODE3            PIC 9(6) VALUE 0.
          03 REQUIRED-ACCT-NUMBER3          PIC 9(8) VALUE 0.

       01 ACCOUNT-KY3-BYTES REDEFINES ACCOUNT-KY3 PIC X(14).

       01 INQCUST-COMMAREA.
          COPY INQCUST.

       01 INQACCCU-COMMAREA.
           COPY INQACCCU REPLACING ==NUMBER-OF-ACCOUNTS.==
           BY ==NUMBER-OF-ACCOUNTS IN INQACCCU-COMMAREA.==.

       01 STORM-DRAIN-CONDITION             PIC X(20).

       01 ACCOUNT-CONTROL.
          COPY ACCTCTRL.


       01 WS-TIME-DATA.
           03 WS-TIME-NOW                   PIC 9(6).
           03 WS-TIME-NOW-GRP REDEFINES WS-TIME-NOW.
              05 WS-TIME-NOW-GRP-HH         PIC 99.
              05 WS-TIME-NOW-GRP-MM         PIC 99.
              05 WS-TIME-NOW-GRP-SS         PIC 99.

       01 WS-ABEND-PGM                      PIC X(8) VALUE 'ABNDPROC'.

       01 ABNDINFO-REC.
           COPY ABNDINFO.

       LINKAGE SECTION.
       01 DFHCOMMAREA.
           COPY CREACC.


       PROCEDURE DIVISION USING DFHCOMMAREA.
       PREMIERE SECTION.
       P010.

           MOVE SORTCODE TO
              REQUIRED-SORT-CODE
              REQUIRED-SORT-CODE2.

           MOVE ZERO TO ACCOUNT-NUMBER.
           INITIALIZE INQCUST-COMMAREA

      *
      *    We need to validate that the supplied CUSTOMER actually
      *    exists by linking to INQCUST.
      *

           MOVE COMM-CUSTNO IN DFHCOMMAREA TO INQCUST-CUSTNO.

           EXEC CICS LINK PROGRAM('INQCUST ')
                     COMMAREA(INQCUST-COMMAREA)
                     RESP(WS-CICS-RESP)
           END-EXEC.

      *
      *    If the link failed for some reason, or the link to INQCUST
      *    indicated that customer information could not be
      *    successfully retrieved then set fail flags and finished.
      *
           IF EIBRESP IS NOT EQUAL TO DFHRESP(NORMAL)
           OR INQCUST-INQ-SUCCESS IS NOT EQUAL TO 'Y'

             MOVE 'N' TO COMM-SUCCESS IN DFHCOMMAREA
             MOVE '1' TO COMM-FAIL-CODE IN DFHCOMMAREA

             PERFORM GET-ME-OUT-OF-HERE

           END-IF

      *
      *    Link to INQACCCU to  get account information
      *
           PERFORM CUSTOMER-ACCOUNT-COUNT.

           IF WS-CICS-RESP IS NOT EQUAL TO DFHRESP(NORMAL)
             DISPLAY 'Error counting accounts'
             MOVE 'N' to COMM-SUCCESS IN DFHCOMMAREA
             MOVE 'N' to COMM-SUCCESS IN INQACCCU-COMMAREA
             MOVE '9' TO COMM-FAIL-CODE IN DFHCOMMAREA

             PERFORM GET-ME-OUT-OF-HERE
           END-IF

           IF COMM-SUCCESS IN INQACCCU-COMMAREA = 'N'
             DISPLAY 'Error counting accounts'
             MOVE 'N' to COMM-SUCCESS IN DFHCOMMAREA
             MOVE '9' TO COMM-FAIL-CODE IN DFHCOMMAREA

             PERFORM GET-ME-OUT-OF-HERE
           END-IF


           IF NUMBER-OF-ACCOUNTS IN INQACCCU-COMMAREA > 9
             MOVE 'N' TO COMM-SUCCESS IN DFHCOMMAREA
             MOVE '8' TO COMM-FAIL-CODE IN DFHCOMMAREA

             PERFORM GET-ME-OUT-OF-HERE
           END-IF

      *
      *    Validate the type of account e.g. MORTGAGE etc.
      *
           PERFORM ACCOUNT-TYPE-CHECK

           IF COMM-SUCCESS OF DFHCOMMAREA = 'N'
             PERFORM GET-ME-OUT-OF-HERE
           END-IF


      *
      *    Having verified that the CUSTOMER exists we can now process
      *    the ACCOUNT information.  Enqueue the Named Counter for
      *    ACCOUNT.
      *
           PERFORM ENQ-NAMED-COUNTER.

      *
      *    Get the next ACCOUNT number from the ACCOUNT CONTROL Table
      *
           PERFORM WRITE-ACCOUNT-DATASVC.
           PERFORM GET-ME-OUT-OF-HERE.

       P999.
           EXIT.


       ENQ-NAMED-COUNTER SECTION.
       ENC010.

           MOVE SORTCODE TO NCS-ACC-NO-TEST-SORT.

           EXEC CICS ENQ
              RESOURCE(NCS-ACC-NO-NAME)
              LENGTH(16)
              RESP(WS-CICS-RESP)
              RESP2(WS-CICS-RESP2)
           END-EXEC.

           IF WS-CICS-RESP NOT = DFHRESP(NORMAL)
             MOVE 'N' TO COMM-SUCCESS IN DFHCOMMAREA
             MOVE '3' TO COMM-FAIL-CODE IN DFHCOMMAREA
             PERFORM GET-ME-OUT-OF-HERE
           END-IF.

       ENC999.
           EXIT.


       DEQ-NAMED-COUNTER SECTION.
       DNC010.

           MOVE SORTCODE TO NCS-ACC-NO-TEST-SORT.

           EXEC CICS DEQ
              RESOURCE(NCS-ACC-NO-NAME)
              LENGTH(16)
              RESP(WS-CICS-RESP)
              RESP2(WS-CICS-RESP2)
           END-EXEC.

           IF WS-CICS-RESP NOT = DFHRESP(NORMAL)
             MOVE 'N' TO COMM-SUCCESS IN DFHCOMMAREA
             MOVE '5' TO COMM-FAIL-CODE IN DFHCOMMAREA
             PERFORM GET-ME-OUT-OF-HERE
           END-IF.

       DNC999.
           EXIT.


       WRITE-ACCOUNT-DATASVC SECTION.
       WAD010.
      *
      *    POST /data/account replaces FIND-NEXT-ACCOUNT (CONTROL
      *    SELECT/UPDATE), WRITE-ACCOUNT-DB2 (INSERT ACCOUNT) and
      *    WRITE-PROCTRAN-DB2 (INSERT PROCTRAN 'OCA') as one unit of
      *    work. accountNumber is omitted so the data service
      *    allocates it from CONTROL <sortcode>-ACCOUNT-LAST, and it
      *    sets opened/last statement = today, next = today + 30.
      *
           MOVE EIBTASKN TO WS-DSV-TASKREF.
           MOVE COMM-INT-RT IN DFHCOMMAREA TO WS-EDIT-RATE.
           MOVE COMM-OVERDR-LIM IN DFHCOMMAREA TO WS-EDIT-OVERDRAFT.
           MOVE COMM-AVAIL-BAL IN DFHCOMMAREA TO WS-EDIT-AVAIL-BAL.
           MOVE COMM-ACT-BAL IN DFHCOMMAREA TO WS-EDIT-ACTUAL-BAL.

           MOVE SPACES TO DSV-PATH DSV-REQ-BODY.
           SET DSV-POST TO TRUE.
           MOVE '/data/account' TO DSV-PATH.
           MOVE 1 TO WS-PTR.
           STRING '{"sortcode":"' DELIMITED BY SIZE
                  SORTCODE DELIMITED BY SIZE
                  '","customerNumber":"' DELIMITED BY SIZE
                  COMM-CUSTNO IN DFHCOMMAREA DELIMITED BY SIZE
                  '","accountType":"' DELIMITED BY SIZE
                  COMM-ACC-TYPE IN DFHCOMMAREA DELIMITED BY SIZE
                  '","interestRate":"' DELIMITED BY SIZE
                  FUNCTION TRIM(WS-EDIT-RATE) DELIMITED BY SIZE
                  '","overdraftLimit":' DELIMITED BY SIZE
                  FUNCTION TRIM(WS-EDIT-OVERDRAFT) DELIMITED BY SIZE
                  ',"availableBalance":"' DELIMITED BY SIZE
                  FUNCTION TRIM(WS-EDIT-AVAIL-BAL) DELIMITED BY SIZE
                  '","actualBalance":"' DELIMITED BY SIZE
                  FUNCTION TRIM(WS-EDIT-ACTUAL-BAL) DELIMITED BY SIZE
                  '","taskRef":"' DELIMITED BY SIZE
                  WS-DSV-TASKREF DELIMITED BY SIZE
                  '"}' DELIMITED BY SIZE
             INTO DSV-REQ-BODY WITH POINTER WS-PTR
           END-STRING.
           COMPUTE DSV-REQ-LEN = WS-PTR - 1.

           PERFORM CALL-DATASVC.

      *    Transport failure: legacy abended HNCS when the CONTROL
      *    table (first database access) could not be used.
           IF DSV-RC NOT = '0'
              DISPLAY 'CREACC: data service call failed RC=' DSV-RC
                 ' RESP=' DSV-CICS-RESP ' RESP2=' DSV-CICS-RESP2
              PERFORM DEQ-NAMED-COUNTER
              EXEC CICS ABEND
                   ABCODE('HNCS')
              END-EXEC
           END-IF.

           INITIALIZE WS-DSV-RESP.
           JSON PARSE DSV-RESP-BODY(1:DSV-RESP-LEN)
                INTO WS-DSV-RESP
                NAME OF WS-RESP-SUCCESS  IS 'success'
                     WS-RESP-FAILCODE    IS 'failCode'
                     WS-RESP-ABEND       IS 'abendCode'
                     WS-RESP-ACCOUNT     IS 'account'
                     COPY ACCJSONN.
                ON EXCEPTION
                   DISPLAY 'CREACC: bad data service response HTTP='
                      DSV-HTTP-STATUS ' JSON-CODE=' JSON-CODE
                   PERFORM DEQ-NAMED-COUNTER
                   EXEC CICS ABEND
                        ABCODE('HNCS')
                   END-EXEC
           END-JSON.

      *    HNCS (CONTROL failure) or HWPT (PROCTRAN insert failure):
      *    the data service has rolled everything back.
           IF WS-RESP-ABEND NOT = SPACES
              PERFORM DEQ-NAMED-COUNTER
              EXEC CICS ABEND
                   ABCODE(WS-RESP-ABEND)
              END-EXEC
           END-IF.

      *    'A' = account type not allowed, '7' = ACCOUNT insert failed
           IF WS-RESP-SUCCESS NOT = 'Y'
              MOVE 'N' TO COMM-SUCCESS IN DFHCOMMAREA
              MOVE WS-RESP-FAILCODE TO COMM-FAIL-CODE IN DFHCOMMAREA
              PERFORM DEQ-NAMED-COUNTER
              PERFORM GET-ME-OUT-OF-HERE
           END-IF.

           PERFORM DEQ-NAMED-COUNTER.

           MOVE JA-SCODE               TO COMM-SORTCODE.
           MOVE JA-ACCNO               TO COMM-NUMBER.
           MOVE JA-OPENED              TO COMM-OPENED IN DFHCOMMAREA.
           MOVE JA-LAST-STMT
              TO COMM-LAST-STMT-DT IN DFHCOMMAREA.
           MOVE JA-NEXT-STMT
              TO COMM-NEXT-STMT-DT IN DFHCOMMAREA.
           MOVE 'ACCT'                 TO COMM-EYECATCHER.
           MOVE 'Y' TO COMM-SUCCESS IN DFHCOMMAREA.
           MOVE ' ' TO COMM-FAIL-CODE IN DFHCOMMAREA.

       WAD999.
           EXIT.


       CALL-DATASVC SECTION.
       CDS010.
           EXEC CICS LINK PROGRAM('DATASVC')
                COMMAREA(WS-DSV)
                RESP(WS-CICS-RESP)
                RESP2(WS-CICS-RESP2)
           END-EXEC.
           IF WS-CICS-RESP NOT = DFHRESP(NORMAL)
              MOVE '9' TO DSV-RC
              MOVE WS-CICS-RESP TO DSV-CICS-RESP
              MOVE WS-CICS-RESP2 TO DSV-CICS-RESP2
           END-IF.
       CDS999.
           EXIT.
       GET-ME-OUT-OF-HERE SECTION.
       GMOFH010.
      *
      *    Finish
      *
           EXEC CICS RETURN
           END-EXEC.

       GMOFH999.
           EXIT.


       CUSTOMER-ACCOUNT-COUNT SECTION.
       CAC010.
           MOVE 20 TO NUMBER-OF-ACCOUNTS IN INQACCCU-COMMAREA.
           MOVE COMM-CUSTNO IN DFHCOMMAREA
             TO CUSTOMER-NUMBER IN INQACCCU-COMMAREA.

           SET COMM-PCB-POINTER TO NULL

           EXEC CICS LINK PROGRAM('INQACCCU')
                COMMAREA(INQACCCU-COMMAREA)
                RESP(WS-CICS-RESP)
                SYNCONRETURN
           END-EXEC.

       CAC999.
           EXIT.


       ACCOUNT-TYPE-CHECK SECTION.
       ATC010.
      *
      *    Validate that only ISA, MORTGAGE, SAVING, CURRENT and LOAN
      *    are the only account types available.
      *
           EVALUATE TRUE
              WHEN COMM-ACC-TYPE IN DFHCOMMAREA(1:3) = 'ISA'
              WHEN COMM-ACC-TYPE IN DFHCOMMAREA(1:8) = 'MORTGAGE'
              WHEN COMM-ACC-TYPE IN DFHCOMMAREA(1:6) = 'SAVING'
              WHEN COMM-ACC-TYPE IN DFHCOMMAREA(1:7) = 'CURRENT'
              WHEN COMM-ACC-TYPE IN DFHCOMMAREA(1:4) = 'LOAN'
                 MOVE 'Y' TO COMM-SUCCESS OF DFHCOMMAREA
              WHEN OTHER
                 MOVE 'N' TO COMM-SUCCESS OF DFHCOMMAREA
                 MOVE 'A' TO COMM-FAIL-CODE IN DFHCOMMAREA
           END-EVALUATE.

       ATC999.
           EXIT.


       POPULATE-TIME-DATE2 SECTION.
       PTD2010.
      D    DISPLAY 'POPULATE-TIME-DATE2 SECTION'.

           EXEC CICS ASKTIME
              ABSTIME(WS-U-TIME)
           END-EXEC.

           EXEC CICS FORMATTIME
                     ABSTIME(WS-U-TIME)
                     DDMMYYYY(WS-ORIG-DATE)
                     TIME(WS-TIME-NOW)
                     DATESEP
           END-EXEC.

       PTD2999.
           EXIT.
