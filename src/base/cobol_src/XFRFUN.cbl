       PROCESS CICS,NODYNAM,NSYMBOL(NATIONAL),TRUNC(STD)
       CBL CICS('SP,EDF')
      ******************************************************************
      *                                                                *
      *  Copyright IBM Corp. 2023                                      *
      *                                                                *
      ******************************************************************

      ******************************************************************
      * This program gets called when someone initiates a transfer
      * of funds (perhaps) over the counter or via a web application.
      *
      * This program takes an incoming sortcode/account number (key)
      * from the account where the money is transfered from and a
      * sortcode/acount number (key) combination for the account where
      * the money is being transferred to. Additionally, there will
      * also be an incoming amount.
      *
      * This program needs to be able to cope with the data in any
      * format. No checking is made on overdraft limits.
      *
      * If the transfer can be made successfully after looking up
      * the account information then the account information will be
      * updated and the PROCTRAN (Processed Transaction) datastore
      * will be updated to record the successful transaction.
      *
      * If the transaction is a success, then the available balance
      * and the actual balance for the account being debited and the
      * account being credited will be returned.
      *
      * If the update to either the ACCOUNT or PROCTRAN datastore is
      * unsuccessful, then the transaction is deemed to have failed.
      *
      * Should there be some kind of datastore failure or other
      * unpredictable failure we should report it, back out any
      * updates already made and abend the transaction.
      *
      *****************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. XFRFUN.
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


      * DATASVC commarea: one HTTP call to the Java data service
      * (/banking/data/movement/transfer) that replaces EXEC SQL.
       01 WS-DATASVC.
          COPY DATASVC.
       01 WS-DSV-PTR                    PIC S9(8) COMP.
       01 WS-DSV-TASKN                  PIC Z(11)9.
       01 WS-DSV-AMOUNT                 PIC +9(10).99.
       01 WS-DSV-RESPONSE.
          03 WS-DSV-SUCCESS             PIC X.
          03 WS-DSV-FAIL-CODE           PIC X.
          03 WS-DSV-ABEND-CODE          PIC X(4).
          03 WS-DSV-FAVBAL              PIC X(16).
          03 WS-DSV-FACTBAL             PIC X(16).
          03 WS-DSV-TAVBAL              PIC X(16).
          03 WS-DSV-TACTBAL             PIC X(16).

       01 WS-CICS-WORK-AREA.
          05 WS-CICS-RESP               PIC S9(8) COMP.
          05 WS-CICS-RESP2              PIC S9(8) COMP.


       LOCAL-STORAGE SECTION.
       01 SYSIDERR-RETRY                PIC 999.
       01 FILE-RETRY                    PIC 999.
       01 WS-EXIT-RETRY-LOOP            PIC X         VALUE ' '.

       01 DB2-DATE-REFORMAT.
          03 DB2-DATE-REF-YR            PIC 9(4).
          03 FILLER                     PIC X.
          03 DB2-DATE-REF-MNTH          PIC 99.
          03 FILLER                     PIC X.
          03 DB2-DATE-REF-DAY           PIC 99.


      *
      * Pull in the input and output data structures
      *
       01 WS-ACC-DATA.
          COPY ACCOUNT.

       01 WS-ACC-DATA2.
          COPY ACCOUNT REPLACING "ACCOUNT" BY "ACCOUNT2".

       01 WS-EIBTASKN12                 PIC 9(12)     VALUE 0.

      *
      * Pull in the input and output data structures
      *

       01 DESIRED-ACC-KEY.
          03 DESIRED-SORT-CODE          PIC 9(6).
          03 DESIRED-ACC-NO             PIC 9(8).

      * 01 DESIRED-ACC-KEY2.
      *    03 DESIRED-SORT-CODE2         PIC 9(6).
      *    03 DESIRED-ACC-NO2            PIC 9(8).

       01 NEW-ACCOUNT-AVAILABLE-BALANCE PIC S9(10)V99 VALUE 0.
       01 NEW-ACCOUNT-ACTUAL-BALANCE    PIC S9(10)V99 VALUE 0.
       01 WS-ACC-REC-LEN                PIC S9(4) COMP
                                                      VALUE 0.

       01 NEW-ACCOUNT-AVAILABLE-BALANCE2
                                        PIC S9(10)V99 VALUE 0.
       01 NEW-ACCOUNT-ACTUAL-BALANCE2   PIC S9(10)V99 VALUE 0.
       01 WS-ACC-REC-LEN2               PIC S9(4) COMP
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

       01 REJ-REASON                    PIC XX        VALUE SPACES.

       01 PROCTRAN-AREA.
          COPY PROCTRAN.

       01 REJTRAN-RIDFLD                PIC S9(8) COMP.
       01 PROCTRAN-RIDFLD               PIC S9(8) COMP.

       01 WS-REJ-REAS.
          03 WS-REASON-TEXT             PIC X(26)     VALUE SPACES.
          03 WS-TO-ACC                  PIC 9(14) DISPLAY
                                                      VALUE 0.
      * 01 WS-REJ-COMBINED.
      *    03 WS-REJ-COMBINED-SORT       PIC X(6).
      *    03 WS-REJ-COMBINED-ACC        PIC X(8).
      *
      * 01 WS-PROC-COMBINED.
      *    03 WS-PROC-COMBINED-SORT      PIC X(6).
      *    03 WS-PROC-COMBINED-ACC       PIC X(8).

       01 WS-PROC-REAS.
          03 WS-PROC-TEXT               PIC X(26)     VALUE SPACES.
          03 WS-PROC-TO                 PIC 9(14) DISPLAY
                                                      VALUE 0.
       01 WS-TESTING-DB-NAME.
          03 WS-TESTING-TYPE            PIC X(9)      VALUE 'STTESTER.'.
          03 WS-TESTING-TABLE-NAME      PIC X(4)      VALUE 'PLOP'.



       01 STMTBUF.
          49 STMTLEN                    PIC S9(4) COMP
                                                      VALUE +78.
          49 STMTTXT                    PIC X(78).

       01 STMTBUF2.
          49 STMTLEN2                   PIC S9(4) COMP
                                                      VALUE +81.
          49 STMTTXT2                   PIC X(81).

       01 DISP-LOT.
          03 DISP-SIGN                  PIC X.
          03 DISP-SQLCD                 PIC 9999.

       01 WS-WANTED.
          03 VAR-LEN                    PIC S9(4) COMP
                                                      VALUE +78.
          03 VAR-TXT                    PIC X(78).

       01 WS-WANTED2.
          03 VAR-LEN2                   PIC S9(4) COMP
                                                      VALUE +81.
          03 VAR-TXT2                   PIC X(81).

       01 WS-INPUT-TAB-NAME             PIC X(4).
       01 WS-INPUT-TAB-NAME2            PIC X(7).

       01 WS-PASSED-DATA.
          02 WS-TEST-KEY                PIC X(4).
          02 WS-SORT-CODE               PIC 9(6).
          02 WS-CUSTOMER-RANGE.
             07 WS-CUSTOMER-RANGE-TOP   PIC X.
             07 WS-CUSTOMER-RANGE-MIDDLE
                                        PIC X.
             07 WS-CUSTOMER-RANGE-BOTTOM
                                        PIC X.
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

       LINKAGE SECTION.

       01 DFHCOMMAREA.
           COPY XFRFUN.


       PROCEDURE DIVISION USING DFHCOMMAREA.
       PREMIERE SECTION.
       A010.

           EXEC CICS HANDLE ABEND
              LABEL(ABEND-HANDLING)
           END-EXEC.


           MOVE SORTCODE TO COMM-FSCODE COMM-TSCODE.

           MOVE SORTCODE TO DESIRED-SORT-CODE.

      *
      *    If the amount being transferred is negative, then
      *    flag this as a failure and finish.
      *
           IF COMM-AMT <= ZERO
             MOVE 'N' TO COMM-SUCCESS
             MOVE '4' TO COMM-FAIL-CODE
             PERFORM GET-ME-OUT-OF-HERE
           END-IF.

      *
           PERFORM UPDATE-ACCOUNT-DATASVC

      *
      *    The COMMAREA values have now been set so all we need to do
      *    is finish
      *
           PERFORM GET-ME-OUT-OF-HERE.

       A999.
           EXIT.


       UPDATE-ACCOUNT-DATASVC SECTION.
       UAD010.

           MOVE 'N' TO COMM-SUCCESS.
      *
      *    Are we trying to transfer from and to the same account?
      *    We don't allow that.
      *
           IF COMM-FACCNO = COMM-TACCNO
              AND COMM-FSCODE = COMM-TSCODE
      *
      *       Preserve the RESP and RESP2, then set up the
      *       standard ABEND info before getting the applid,
      *       date/time etc. and linking to the Abend Handler
      *       program.
      *
              INITIALIZE ABNDINFO-REC
              MOVE EIBRESP    TO ABND-RESPCODE
              MOVE EIBRESP2   TO ABND-RESP2CODE
      *
      *       Get supplemental information
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
              MOVE 'SAME'      TO ABND-CODE

              EXEC CICS ASSIGN PROGRAM(ABND-PROGRAM)
              END-EXEC

              MOVE ZEROS      TO ABND-SQLCODE

              STRING 'UAD010 - Cannot transfer to the same account '
                   DELIMITED BY SIZE,
                   ' EIBRESP=' DELIMITED BY SIZE,
                   ABND-RESPCODE DELIMITED BY SIZE,
                   ' RESP2=' DELIMITED BY SIZE,
                   ABND-RESP2CODE DELIMITED BY SIZE
                   INTO ABND-FREEFORM
              END-STRING

              EXEC CICS LINK PROGRAM(WS-ABEND-PGM)
                       COMMAREA(ABNDINFO-REC)
              END-EXEC

              DISPLAY 'Cannot transfer to the same account'

              EXEC CICS ABEND
                 ABCODE('SAME')
                 NODUMP
                 CANCEL
              END-EXEC

           END-IF.

      *
      *    Former UPDATE-ACCOUNT-DATASVC-FROM/-TO + WRITE-TO-PROCTRAN-DB2:
      *    the data service locks both ACCOUNT rows (lower account
      *    number first), updates both balances and writes the TFR
      *    PROCTRAN row in one unit of work, retrying deadlocks and
      *    rolling everything back on any failure.
      *
           MOVE COMM-AMT TO WS-DSV-AMOUNT.
           MOVE EIBTASKN TO WS-DSV-TASKN.
           INITIALIZE DSV-REQ-BODY.
           MOVE 1 TO WS-DSV-PTR.
           STRING '{"sortCode":"' DELIMITED BY SIZE
                  COMM-FSCODE DELIMITED BY SIZE
                  '","fromAccountNumber":"' DELIMITED BY SIZE
                  COMM-FACCNO DELIMITED BY SIZE
                  '","toAccountNumber":"' DELIMITED BY SIZE
                  COMM-TACCNO DELIMITED BY SIZE
                  '","amount":"' DELIMITED BY SIZE
                  WS-DSV-AMOUNT DELIMITED BY SIZE
                  '","taskNumber":' DELIMITED BY SIZE
                  FUNCTION TRIM(WS-DSV-TASKN) DELIMITED BY SIZE
                  '}' DELIMITED BY SIZE
              INTO DSV-REQ-BODY
              WITH POINTER WS-DSV-PTR
           END-STRING.
           COMPUTE DSV-REQ-LEN = WS-DSV-PTR - 1.
           MOVE 'POST' TO DSV-METHOD.
           MOVE '/data/movement/transfer' TO DSV-PATH.

           EXEC CICS LINK PROGRAM('DATASVC')
                COMMAREA(WS-DATASVC)
           END-EXEC.

      *
      *    Transport failure: abend as for a failed Db2 call on the
      *    TO account read (RUF2); nothing has been committed.
      *
           IF NOT DSV-OK
              DISPLAY 'XFRFUN: DATASVC call failed, DSV-RC=' DSV-RC
                 ' RESP=' DSV-CICS-RESP ' RESP2=' DSV-CICS-RESP2
              EXEC CICS ABEND
                 ABCODE('RUF2')
              END-EXEC
           END-IF.

           INITIALIZE WS-DSV-RESPONSE.
           JSON PARSE DSV-RESP-BODY(1:DSV-RESP-LEN)
              INTO WS-DSV-RESPONSE
              NAME OF WS-DSV-SUCCESS IS 'success'
                      WS-DSV-FAIL-CODE IS 'failCode'
                      WS-DSV-ABEND-CODE IS 'abendCode'
                      WS-DSV-FAVBAL IS 'fromAvailableBalance'
                      WS-DSV-FACTBAL IS 'fromActualBalance'
                      WS-DSV-TAVBAL IS 'toAvailableBalance'
                      WS-DSV-TACTBAL IS 'toActualBalance'
              ON EXCEPTION
                 DISPLAY 'XFRFUN: unparseable DATASVC response, HTTP '
                    DSV-HTTP-STATUS ' JSON-CODE=' JSON-CODE
                 EXEC CICS ABEND
                    ABCODE('RUF2')
                 END-EXEC
           END-JSON.

      *
      *    FROM / RUF2 / RUF3 / WPCD / SAME: the abends XFRFUN issued
      *
           IF WS-DSV-ABEND-CODE NOT = SPACES
              EXEC CICS ABEND
                 ABCODE(WS-DSV-ABEND-CODE)
                 NODUMP
              END-EXEC
           END-IF.

           MOVE WS-DSV-SUCCESS TO COMM-SUCCESS.
           MOVE WS-DSV-FAIL-CODE TO COMM-FAIL-CODE.
           IF WS-DSV-FAVBAL NOT = SPACES
              COMPUTE COMM-FAVBAL = FUNCTION NUMVAL(WS-DSV-FAVBAL)
           END-IF.
           IF WS-DSV-FACTBAL NOT = SPACES
              COMPUTE COMM-FACTBAL = FUNCTION NUMVAL(WS-DSV-FACTBAL)
           END-IF.
           IF WS-DSV-TAVBAL NOT = SPACES
              COMPUTE COMM-TAVBAL = FUNCTION NUMVAL(WS-DSV-TAVBAL)
           END-IF.
           IF WS-DSV-TACTBAL NOT = SPACES
              COMPUTE COMM-TACTBAL = FUNCTION NUMVAL(WS-DSV-TACTBAL)
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
      *
      *    Deal with any abends
      *
           EXEC CICS ASSIGN
              ABCODE(MY-ABEND-CODE)
           END-EXEC.

      *
      *    Evaluate the Abend code that is returned
      *     - For DB2 AD2Z ... provide some diagnostics
      *     - For VSAM RLS abends: AFCR, AFCS and AFCT record the
      *    Abend as happening but do not abend ... leave this to
      *    CPSM WLM "Storm drain" (Abend probability) to handle
      *    If not a "storm drain" ... take the abend afterwards
      *
           EVALUATE MY-ABEND-CODE

      *
              WHEN 'AFCR'
              WHEN 'AFCS'
              WHEN 'AFCT'
                 MOVE 'Y' TO WS-STORM-DRAIN
                 DISPLAY 'XFRFUN: Check-For-Storm-Drain-VSAM: Storm '
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

                    STRING 'AH010 - Unable to perform SYNCPOINT '
                       DELIMITED BY SIZE,
                       'ROLLBACK.' DELIMITED BY SIZE,
                       ' Possible integrity issue following VSAM RLS '
                       DELIMITED BY SIZE,
                       'abend.'
                       DELIMITED BY SIZE,
                       ' EIBRESP=' DELIMITED BY SIZE,
                       ABND-RESPCODE DELIMITED BY SIZE,
                       ' RESP2=' DELIMITED BY SIZE,
                       ABND-RESP2CODE DELIMITED BY SIZE
                       INTO ABND-FREEFORM
                    END-STRING

                    EXEC CICS LINK PROGRAM(WS-ABEND-PGM)
                           COMMAREA(ABNDINFO-REC)
                    END-EXEC

                    DISPLAY 'XFRFUN: Unable to perform Syncpoint '
                       'Rollback. Possible Integrity issue following '
                       ' VSAM RLS abend RESP CODE='
                       WS-CICS-RESP ' RESP2 CODE=' WS-CICS-RESP2

                    EXEC CICS
                       ABEND
                       ABCODE ('HROL')
                    END-EXEC

                 END-IF

                 MOVE 'N' TO COMM-SUCCESS
                 MOVE '2' TO COMM-FAIL-CODE

                 EXEC CICS RETURN
                 END-EXEC

           END-EVALUATE.

           IF WS-STORM-DRAIN = 'N'

              EXEC CICS ABEND ABCODE( MY-ABEND-CODE)
              NODUMP
              END-EXEC

           END-IF.

       AH999.
           EXIT.


       POPULATE-TIME-DATE SECTION.
       PTD010.

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
