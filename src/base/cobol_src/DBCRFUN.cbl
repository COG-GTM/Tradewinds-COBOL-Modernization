       PROCESS CICS,NODYNAM,NSYMBOL(NATIONAL),TRUNC(STD)
       CBL CICS('SP,EDF')
      ******************************************************************
      *                                                                *
      *  Copyright IBM Corp. 2023                                      *
      *                                                                *
      ******************************************************************
      ******************************************************************
      * This program will get called when someone pays some cash in to
      * the bank over the counter or takes out some cash out over the
      * counter.
      *
      * This program takes an account number and amount, it then
      * accesses the DB2 datastore and retrieves the associated
      * Account record and applies the amount and returns the
      * updated actual amount and the available balance from the Account
      * datastore.
      *
      * If the update is successfully applied to the Account then a
      * record is written to the PROCTRAN (Processed Transaction)
      * datastore.
      *
      * If the transaction cannot be successfullty applied to the
      * Account datastore, because the account no longer exists
      * (for example) then the SUCCESS flag is set to 'N' and a fail
      * code gets written to the return data, for the calling routine
      * to handle.
      *
      ******************************************************************

       IDENTIFICATION DIVISION.
       PROGRAM-ID. DBCRFUN.
       AUTHOR. Jon Collett.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
      *SOURCE-COMPUTER.   IBM-370 WITH DEBUGGING MODE.
       SOURCE-COMPUTER.  IBM-370.
       OBJECT-COMPUTER.  IBM-370.

       INPUT-OUTPUT SECTION.


       DATA DIVISION.
       WORKING-STORAGE SECTION.

       COPY SORTCODE.



       77 SYSIDERR-RETRY                PIC 999.

      * DATASVC commarea: one HTTP call to the Java data service
      * (/banking/data/movement/debitCredit) that replaces EXEC SQL.
       01 WS-DATASVC.
          COPY DATASVC.
       01 WS-DSV-PTR                    PIC S9(8) COMP.
       01 WS-DSV-LINK-RESP              PIC S9(8) COMP.
       01 WS-DSV-LINK-RESP2             PIC S9(8) COMP.
       01 WS-DSV-TASKN                  PIC Z(11)9.
       01 WS-DSV-FACILTYPE              PIC -(8)9.
       01 WS-DSV-RESPONSE.
          03 WS-DSV-SUCCESS             PIC X.
          03 WS-DSV-FAIL-CODE           PIC X.
          03 WS-DSV-ABEND-CODE          PIC X(4).
          03 WS-DSV-AVAIL-BAL           PIC X(16).
          03 WS-DSV-ACTUAL-BAL          PIC X(16).

       01 WS-CICS-WORK-AREA.
          05 WS-CICS-RESP               PIC S9(8) COMP.
          05 WS-CICS-RESP2              PIC S9(8) COMP.


       LOCAL-STORAGE SECTION.
       01 FILE-RETRY                    PIC 999.
       01 WS-EXIT-RETRY-LOOP            PIC X         VALUE ' '.

       01 DB2-DATE-REFORMAT.
          03 DB2-DATE-REF-YR            PIC 9(4).
          03 FILLER                     PIC X.
          03 DB2-DATE-REF-MNTH          PIC 99.
          03 FILLER                     PIC X.
          03 DB2-DATE-REF-DAY           PIC 99.

       01 DATA-STORE-TYPE               PIC X.
          88 DATASTORE-TYPE-DLI                       VALUE '1'.
          88 DATASTORE-TYPE-DB2                       VALUE '2'.
          88 DATASTORE-TYPE-VSAM                      VALUE 'V'.

       01 WS-ACC-DATA.
          COPY ACCOUNT.

       01 WS-EIBTASKN12                 PIC 9(12)     VALUE 0.

       01 DESIRED-ACC-KEY.
          03 DESIRED-SORT-CODE          PIC 9(6).
          03 DESIRED-ACC-NO             PIC 9(8).

       01 NEW-ACCOUNT-AVAILABLE-BALANCE PIC S9(10)V99 VALUE 0.
       01 NEW-ACCOUNT-ACTUAL-BALANCE    PIC S9(10)V99 VALUE 0.
       01 WS-ACC-REC-LEN                PIC S9(4) COMP
                                                      VALUE 0.

       01 WS-U-TIME                     PIC S9(15) COMP-3.
       01 WS-ORIG-DATE                  PIC X(10).
       01 WS-ORIG-DATE-GRP REDEFINES WS-ORIG-DATE.
          03 WS-ORIG-DATE-DD            PIC 99.
          03 FILLER                     PIC X.
          03 WS-ORIG-DATE-MM            PIC 99.
          03 FILLER                     PIC X.
          03 WS-ORIG-DATE-YYYY          PIC 9999.

       01 WS-ORIG-DATE-GRP-X.
          03 WS-ORIG-DATE-DD-X          PIC XX.
          03 FILLER                     PIC X         VALUE '.'.
          03 WS-ORIG-DATE-MM-X          PIC XX.
          03 FILLER                     PIC X         VALUE '.'.
          03 WS-ORIG-DATE-YYYY-X        PIC X(4).

       01 PROCTRAN-AREA.
          COPY PROCTRAN.

       01 WS-PASSED-DATA.
          02 WS-TEST-KEY                PIC X(4).
          02 WS-SORT-CODE               PIC 9(6).
          02 WS-CUSTOMER-RANGE.
             07 WS-CUSTOMER-RANGE-TOP   PIC X.
             07 WS-CUSTOMER-RANGE-MIDDLE
                                        PIC X.
             07 WS-CUSTOMER-RANGE-BOTTOM
                                        PIC X.

      * 01 CUSTOMER-KY.
      *    03 REQUIRED-SORT-CODE         PIC 9(6)      VALUE 0.
      *    03 REQUIRED-ACC-NUM           PIC 9(8)      VALUE 0.

       01 PROCTRAN-RIDFLD               PIC S9(8) COMP.


       01 MY-ABEND-CODE                 PIC XXXX.

       01 WS-STORM-DRAIN                PIC X         VALUE 'N'.
       01 STORM-DRAIN-CONDITION         PIC X(20).

       01 NUMERIC-AMOUNT-DISPLAY        PIC +9(10).99.

       01 WS-TIME-DATA.
          03 WS-TIME-NOW                PIC 9(6).
          03 WS-TIME-NOW-GRP REDEFINES WS-TIME-NOW.
             05 WS-TIME-NOW-GRP-HH      PIC 99.
             05 WS-TIME-NOW-GRP-MM      PIC 99.
             05 WS-TIME-NOW-GRP-SS      PIC 99.

       01 WS-ABEND-PGM                  PIC X(8)      VALUE 'ABNDPROC'.

       01 ABNDINFO-REC.
           COPY ABNDINFO.

       01 WS-SUFFICIENT-FUNDS           PIC X VALUE 'N'.
       01 WS-DIFFERENCE                 PIC S9(10)V99.

       LINKAGE SECTION.
       01 DFHCOMMAREA.
          COPY PAYDBCR.


       PROCEDURE DIVISION USING DFHCOMMAREA.
       PREMIERE SECTION.
       A010.
           MOVE 'N' TO COMM-SUCCESS
           MOVE '0' TO COMM-FAIL-CODE

      *
      *    Set up some Abend handling
      *
           EXEC CICS HANDLE ABEND
              LABEL(ABEND-HANDLING)
           END-EXEC.

           MOVE SORTCODE TO COMM-SORTC.
           MOVE SORTCODE TO DESIRED-SORT-CODE.

      *
      *    Determine what kind of ACCOUNT datastore we should
      *    be accessing
      *


      *
      *           Go and update the ACCOUNT record
      *
            PERFORM UPDATE-ACCOUNT-DATASVC.
      *
      *    The COMMAREA values have now been set so all we need to do
      *    is finish
      *

           PERFORM GET-ME-OUT-OF-HERE.

       A999.
           EXIT.


       UPDATE-ACCOUNT-DATASVC SECTION.
       UAD010.
      *
      *    Former UPDATE-ACCOUNT-DB2 + WRITE-TO-PROCTRAN-DB2: the data
      *    service reads the ACCOUNT row, applies the DBCRFUN rules
      *    (fail codes 1-4), updates ACCOUNT and writes PROCTRAN
      *    (DEB/CRE/PDR/PCR) in one unit of work, rolling back if the
      *    PROCTRAN insert fails.
      *
           MOVE COMM-ACCNO TO DESIRED-ACC-NO.
           MOVE COMM-AMT TO NUMERIC-AMOUNT-DISPLAY.
           MOVE EIBTASKN TO WS-DSV-TASKN.
           MOVE COMM-FACILTYPE TO WS-DSV-FACILTYPE.

           INITIALIZE DSV-REQ-BODY.
           MOVE 1 TO WS-DSV-PTR.
           STRING '{"sortCode":"' DELIMITED BY SIZE
                  COMM-SORTC DELIMITED BY SIZE
                  '","accountNumber":"' DELIMITED BY SIZE
                  DESIRED-ACC-NO DELIMITED BY SIZE
                  '","amount":"' DELIMITED BY SIZE
                  NUMERIC-AMOUNT-DISPLAY DELIMITED BY SIZE
                  '","facilityType":' DELIMITED BY SIZE
                  FUNCTION TRIM(WS-DSV-FACILTYPE) DELIMITED BY SIZE
                  ',"applId":"' DELIMITED BY SIZE
                  FUNCTION TRIM(COMM-APPLID) DELIMITED BY SIZE
                  '","userId":"' DELIMITED BY SIZE
                  FUNCTION TRIM(COMM-USERID) DELIMITED BY SIZE
                  '","taskNumber":' DELIMITED BY SIZE
                  FUNCTION TRIM(WS-DSV-TASKN) DELIMITED BY SIZE
                  '}' DELIMITED BY SIZE
              INTO DSV-REQ-BODY
              WITH POINTER WS-DSV-PTR
           END-STRING.
           COMPUTE DSV-REQ-LEN = WS-DSV-PTR - 1.
           MOVE 'POST' TO DSV-METHOD.
           MOVE '/data/movement/debitCredit' TO DSV-PATH.

      *    RC '9': LINK itself failed (e.g. PGMIDERR), not DATASVC
           MOVE SPACE TO DSV-RC.
           EXEC CICS LINK PROGRAM('DATASVC')
                COMMAREA(WS-DATASVC)
                RESP(WS-DSV-LINK-RESP)
                RESP2(WS-DSV-LINK-RESP2)
           END-EXEC.
           IF WS-DSV-LINK-RESP NOT = DFHRESP(NORMAL)
              MOVE '9' TO DSV-RC
              MOVE WS-DSV-LINK-RESP TO DSV-CICS-RESP
              MOVE WS-DSV-LINK-RESP2 TO DSV-CICS-RESP2
           END-IF.

      *
      *    Transport failure: same outcome as a failed Db2 call
      *    (fail code '2', no abend)
      *
           IF NOT DSV-OK
              DISPLAY 'DBCRFUN: DATASVC call failed, DSV-RC=' DSV-RC
                 ' RESP=' DSV-CICS-RESP ' RESP2=' DSV-CICS-RESP2
                 ' ACCOUNT=' COMM-SORTC '/' DESIRED-ACC-NO
              MOVE 'N' TO COMM-SUCCESS
              MOVE '2' TO COMM-FAIL-CODE
              GO TO UAD999
           END-IF.

           INITIALIZE WS-DSV-RESPONSE.
           JSON PARSE DSV-RESP-BODY(1:DSV-RESP-LEN)
              INTO WS-DSV-RESPONSE
              NAME OF WS-DSV-SUCCESS IS 'success'
                      WS-DSV-FAIL-CODE IS 'failCode'
                      WS-DSV-ABEND-CODE IS 'abendCode'
                      WS-DSV-AVAIL-BAL IS 'availableBalance'
                      WS-DSV-ACTUAL-BAL IS 'actualBalance'
              ON EXCEPTION
                 DISPLAY 'DBCRFUN: unparseable DATASVC response, HTTP '
                    DSV-HTTP-STATUS ' JSON-CODE=' JSON-CODE
                 MOVE 'N' TO COMM-SUCCESS
                 MOVE '2' TO COMM-FAIL-CODE
                 GO TO UAD999
           END-JSON.

           IF WS-DSV-ABEND-CODE NOT = SPACES
              EXEC CICS ABEND
                 ABCODE(WS-DSV-ABEND-CODE)
              END-EXEC
           END-IF.

           MOVE WS-DSV-SUCCESS TO COMM-SUCCESS.
           MOVE WS-DSV-FAIL-CODE TO COMM-FAIL-CODE.
           IF WS-DSV-AVAIL-BAL NOT = SPACES
              COMPUTE COMM-AV-BAL = FUNCTION NUMVAL(WS-DSV-AVAIL-BAL)
           END-IF.
           IF WS-DSV-ACTUAL-BAL NOT = SPACES
              COMPUTE COMM-ACT-BAL = FUNCTION NUMVAL(WS-DSV-ACTUAL-BAL)
           END-IF.

       UAD999.
           EXIT.
       GET-ME-OUT-OF-HERE SECTION.
       GMOOH010.
           EXEC CICS RETURN
           END-EXEC.
           GOBACK.

       GMOOH999.
           EXIT.


       ABEND-HANDLING SECTION.
       AH010.

           EXEC CICS ASSIGN ABCODE(MY-ABEND-CODE)
           END-EXEC.

      *
      *    Evaluate the Abend code that is returned
      *      - For DB2 AD2Z ... provide some diagnostics
      *      - For VSAM RLS abends: AFCR, AFCS and AFCT record the
      *        Abend as happening but do not abend ... leave this to
      *        CPSM WLM "Storm drain" (Abend probability) to handle
      *    If not a "storm drain" ... take the abend afterwards
      *

           EVALUATE MY-ABEND-CODE
      *
              WHEN 'AFCR'
              WHEN 'AFCS'
              WHEN 'AFCT'
                 MOVE 'Y' TO WS-STORM-DRAIN
                 DISPLAY 'DBCRFUN: Check-For-Storm-Drain-VSAM: Storm '
                        'Drain condition (Abend ' MY-ABEND-CODE ') '
                        'has been met.'

                 EXEC CICS SYNCPOINT
                    ROLLBACK
                    RESP(WS-CICS-RESP)
                    RESP2(WS-CICS-RESP2)
                 END-EXEC

                 IF WS-CICS-RESP NOT = DFHRESP(NORMAL)
      *
      *             Preserve the RESP and RESP2, then set up the
      *             standard ABEND info before getting the applid,
      *             date/time etc. and linking to the Abend Handler
      *             program.
      *
                    INITIALIZE ABNDINFO-REC
                    MOVE EIBRESP    TO ABND-RESPCODE
                    MOVE EIBRESP2   TO ABND-RESP2CODE
      *
      *             Get supplemental information
      *
                    EXEC CICS ASSIGN APPLID(ABND-APPLID)
                    END-EXEC

                    MOVE EIBTASKN   TO ABND-TASKNO-KEY
                    MOVE EIBTRNID   TO ABND-TRANID

                    PERFORM POPULATE-TIME-DATE

                    MOVE WS-ORIG-DATE TO ABND-DATE
                    STRING WS-TIME-NOW-GRP-HH DELIMITED BY SIZE,
                       ':' DELIMITED BY SIZE,
                        WS-TIME-NOW-GRP-MM DELIMITED BY SIZE,
                        ':' DELIMITED BY SIZE,
                        WS-TIME-NOW-GRP-MM DELIMITED BY SIZE
                        INTO ABND-TIME
                    END-STRING

                    MOVE WS-U-TIME   TO ABND-UTIME-KEY
                    MOVE 'HROL'      TO ABND-CODE

                    EXEC CICS ASSIGN PROGRAM(ABND-PROGRAM)
                    END-EXEC

                    MOVE ZEROS      TO ABND-SQLCODE

                    STRING 'AH010 - Unable to perform Synpoint '
                       DELIMITED BY SIZE,
                       'Rollback.' DELIMITED BY SIZE,
                       ' Possible Integrity issue following VSAM RLS '
                       DELIMITED BY SIZE,
                       'abend.' DELIMITED BY SIZE,
                       'EIBRESP=' DELIMITED BY SIZE,
                       ABND-RESPCODE DELIMITED BY SIZE,
                       ' RESP2=' DELIMITED BY SIZE,
                       ABND-RESP2CODE DELIMITED BY SIZE
                       INTO ABND-FREEFORM
                    END-STRING

                    EXEC CICS LINK PROGRAM(WS-ABEND-PGM)
                           COMMAREA(ABNDINFO-REC)
                    END-EXEC

                    DISPLAY 'DBCRFUN: Unable to perform Synpoint '
                       'Rollback.'
                       ' Possible Integrity issue following VSAM RLS'
                       ' abend'
                       ' RESP CODE=' WS-CICS-RESP
                       ' RESP2 CODE=' WS-CICS-RESP2

                    EXEC CICS ABEND
                       ABCODE ('HROL')
                       CANCEL
                    END-EXEC

                 END-IF

                 MOVE 'N' TO COMM-SUCCESS
                 MOVE '2' TO COMM-FAIL-CODE

                 EXEC CICS RETURN
                 END-EXEC

           END-EVALUATE.

           IF WS-STORM-DRAIN = 'N'
              EXEC CICS ABEND
                 ABCODE( MY-ABEND-CODE)
                 NODUMP
                 CANCEL
              END-EXEC

           END-IF.

       AH999.
           EXIT.


       POPULATE-TIME-DATE SECTION.
       PTD10.

           EXEC CICS ASKTIME
              ABSTIME(WS-U-TIME)
           END-EXEC.

           EXEC CICS FORMATTIME
                     ABSTIME(WS-U-TIME)
                     DDMMYYYY(WS-ORIG-DATE)
                     TIME(WS-TIME-NOW)
                     DATESEP
           END-EXEC.

       PTD999.
           EXIT.
