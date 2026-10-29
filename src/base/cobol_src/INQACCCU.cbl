       CBL CICS('SP,EDF,DLI')
      ******************************************************************
      *                                                                *
      *  Copyright IBM Corp. 2023                                      *
      *                                                                *
      ******************************************************************
      ******************************************************************
      * This program takes an incoming customer number
      * and determines which accounts it is associated with
      * by accessing the datastore & retrieving
      * the associated account records matching on the customer number
      ******************************************************************

       IDENTIFICATION DIVISION.
       PROGRAM-ID. INQACCCU.
       AUTHOR. James O'Grady.


       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
      *SOURCE-COMPUTER.   IBM-370 WITH DEBUGGING MODE.
       SOURCE-COMPUTER.  IBM-370.
       OBJECT-COMPUTER.  IBM-370.

       INPUT-OUTPUT SECTION.

       DATA DIVISION.
       FILE SECTION.

       WORKING-STORAGE SECTION.

       COPY SORTCODE.



      *
      * Get the ACCOUNT DB2 copybook
      *
       01 WS-DSV.
          COPY DATASVC.

      * Envelope returned by GET /data/account/{sc}?customerNumber=
      * (at most 20 accounts, ordered by account number).
       01 WS-DSV-RESP.
          03 WS-RESP-SUCCESS            PIC X.
          03 WS-RESP-FAILCODE           PIC X.
          03 WS-RESP-ABEND              PIC X(4).
          03 WS-RESP-COUNT              PIC 9(4).
          03 WS-RESP-ACCOUNTS OCCURS 20.
             COPY ACCJSON.

       01 WS-ACC-IX                     PIC S9(8) COMP.
       01 EIBRCODE-NICE.
          03 EIBRCODE-FIRST               PIC X.
          03 EIBRCODE-SECOND              PIC X.
          03 EIBRCODE-THIRD               PIC X.
          03 EIBRCODE-FOURTH              PIC X.
          03 EIBRCODE-FIFTH               PIC X.
          03 EIBRCODE-SIXTH               PIC X.

      * Account data service (DATASVC) areas


       01 WS-CICS-WORK-AREA.
          03 WS-CICS-RESP                 PIC S9(8) COMP.
          03 WS-CICS-RESP2                PIC S9(8) COMP.
          03 WS-CICS-RESP-DISPLAY         PIC +9(8) DISPLAY.

       01 EXIT-BROWSE-LOOP                PIC X VALUE 'N'.

       01 OUTPUT-DATA.
           COPY ACCOUNT.

       01 RETURNED-DATA.
           03 RETURNED-EYE-CATCHER        PIC X(4).
           03 RETURNED-CUST-NO            PIC 9(10).
           03 RETURNED-KEY.
              05 RETURNED-SORT-CODE       PIC 9(6).
              05 RETURNED-NUMBER          PIC 9(8).
           03 RETURNED-TYPE               PIC X(8).
           03 RETURNED-INTEREST-RATE      PIC 9(4)V99.
           03 RETURNED-OPENED             PIC 9(8).
           03 RETURNED-OVERDRAFT-LIMIT    PIC 9(8).
           03 RETURNED-LAST-STMT-DATE     PIC 9(8).
           03 RETURNED-NEXT-STMT-DATE     PIC 9(8).
           03 RETURNED-AVAILABLE-BALANCE  PIC S9(10)V99.
           03 RETURNED-ACTUAL-BALANCE     PIC S9(10)V99.

       01 DESIRED-KEY.
           03 DESIRED-KEY-CUSTOMER        PIC 9(10).
           03 DESIRED-KEY-SORTCODE        PIC 9(6).

       01 DB2-DATE-REFORMAT.
          03 DB2-DATE-REF-YR              PIC 9(4).
          03 FILLER                       PIC X.
          03 DB2-DATE-REF-MNTH            PIC 99.
          03 FILLER                       PIC X.
          03 DB2-DATE-REF-DAY             PIC 99.

       01 DATA-STORE-TYPE                 PIC X.
          88 DATASTORE-TYPE-DB2              VALUE '2'.
          88 DATASTORE-TYPE-VSAM             VALUE 'V'.

       01 DB2-EXIT-LOOP                   PIC X.
       01 FETCH-DATA-CNT                  PIC 9(4) COMP.
       01 WS-CUST-ALT-KEY-LEN             PIC S9(4) COMP VALUE +10.


       01 CUSTOMER-KY.
          03 REQUIRED-SORT-CODE           PIC 9(6)  VALUE 0.
          03 REQUIRED-ACC-NUM             PIC 9(8)  VALUE 0.


       01 MY-ABEND-CODE                   PIC XXXX.

       01 WS-STORM-DRAIN                  PIC X VALUE 'N'.

       01 STORM-DRAIN-CONDITION           PIC X(20).

       01 CUSTOMER-AREA.
       COPY CUSTOMER.

       01 INQCUST-COMMAREA.
          COPY INQCUST.

       01 WS-U-TIME                       PIC S9(15) COMP-3.
       01 WS-ORIG-DATE                    PIC X(10).
       01 WS-ORIG-DATE-GRP REDEFINES WS-ORIG-DATE.
          03 WS-ORIG-DATE-DD                 PIC 99.
          03 FILLER                          PIC X.
          03 WS-ORIG-DATE-MM                 PIC 99.
          03 FILLER                          PIC X.
          03 WS-ORIG-DATE-YYYY               PIC 9999.

       01 WS-ORIG-DATE-GRP-X.
          03 WS-ORIG-DATE-DD-X            PIC XX.
          03 FILLER                       PIC X VALUE '.'.
          03 WS-ORIG-DATE-MM-X            PIC XX.
          03 FILLER                       PIC X VALUE '.'.
          03 WS-ORIG-DATE-YYYY-X          PIC X(4).


       01 WS-TIME-DATA.
           03 WS-TIME-NOW                 PIC 9(6).
           03 WS-TIME-NOW-GRP REDEFINES WS-TIME-NOW.
              05 WS-TIME-NOW-GRP-HH          PIC 99.
              05 WS-TIME-NOW-GRP-MM          PIC 99.
              05 WS-TIME-NOW-GRP-SS          PIC 99.

       01 WS-ABEND-PGM                       PIC X(8) VALUE 'ABNDPROC'.

       01 ABNDINFO-REC.
           COPY ABNDINFO.

       LINKAGE SECTION.
       01 DFHCOMMAREA.
          COPY INQACCCU.

       PROCEDURE DIVISION USING DFHCOMMAREA.
       PREMIERE SECTION.
       A010.
           MOVE 'N' TO COMM-SUCCESS
           MOVE '0' TO COMM-FAIL-CODE

           EXEC CICS HANDLE ABEND
              LABEL(ABEND-HANDLING)
           END-EXEC.

           MOVE SORTCODE TO REQUIRED-SORT-CODE OF CUSTOMER-KY.

      *
      *    CUSTOMER-CHECK LINKS to program INQCUST to retrieve the
      *    customer information.
      *

           PERFORM CUSTOMER-CHECK.

      *
      *    If a  matching customer was not returned then set fail flags
      *
           IF CUSTOMER-FOUND = 'N'
              MOVE 'N' TO COMM-SUCCESS
              MOVE '1' TO COMM-FAIL-CODE
              PERFORM GET-ME-OUT-OF-HERE
           END-IF


           PERFORM READ-ACCOUNT-DATASVC
      *
      * Return the ACCOUNT data to the COMMAREA
      *

           PERFORM GET-ME-OUT-OF-HERE.

       A999.
           EXIT.


       READ-ACCOUNT-DATASVC SECTION.
       RAD010.
      *
      *    GET /data/account/{sortcode}?customerNumber=nnnnnnnnnn
      *    replaces the ACC-CURSOR OPEN / FETCH (FETCH-DATA) / CLOSE.
      *
           MOVE ZERO TO NUMBER-OF-ACCOUNTS.
           MOVE SPACES TO DSV-PATH.
           SET DSV-GET TO TRUE.
           MOVE 0 TO DSV-REQ-LEN.
           STRING '/data/account/' DELIMITED BY SIZE
                  SORTCODE DELIMITED BY SIZE
                  '?customerNumber=' DELIMITED BY SIZE
                  CUSTOMER-NUMBER OF DFHCOMMAREA DELIMITED BY SIZE
             INTO DSV-PATH
           END-STRING.

           PERFORM CALL-DATASVC.

      *    Legacy cursor failures set COMM-SUCCESS 'N' with fail code
      *    2/3/4 and did not abend; a transport failure maps to '3'.
           IF DSV-RC NOT = '0'
              DISPLAY 'INQACCCU: data service call failed RC=' DSV-RC
                 ' RESP=' DSV-CICS-RESP ' RESP2=' DSV-CICS-RESP2
              MOVE 'N' TO COMM-SUCCESS
              MOVE '3' TO COMM-FAIL-CODE
              GO TO RAD999
           END-IF.

           INITIALIZE WS-DSV-RESP.
           JSON PARSE DSV-RESP-BODY(1:DSV-RESP-LEN)
                INTO WS-DSV-RESP
                NAME OF WS-RESP-SUCCESS  IS 'success'
                     WS-RESP-FAILCODE    IS 'failCode'
                     WS-RESP-ABEND       IS 'abendCode'
                     WS-RESP-COUNT       IS 'numberOfAccounts'
                     WS-RESP-ACCOUNTS    IS 'accounts'
                     COPY ACCJSONN.
                ON EXCEPTION
                   DISPLAY 'INQACCCU: bad data service response HTTP='
                      DSV-HTTP-STATUS ' JSON-CODE=' JSON-CODE
                   MOVE 'N' TO WS-RESP-SUCCESS
                   MOVE '3' TO WS-RESP-FAILCODE
           END-JSON.

           IF WS-RESP-ABEND NOT = SPACES
              EXEC CICS ABEND ABCODE(WS-RESP-ABEND)
                   CANCEL
              END-EXEC
           END-IF.

           IF WS-RESP-SUCCESS NOT = 'Y'
              MOVE 'N' TO COMM-SUCCESS
              MOVE WS-RESP-FAILCODE TO COMM-FAIL-CODE
              IF COMM-FAIL-CODE = SPACE
                 MOVE '3' TO COMM-FAIL-CODE
              END-IF
              GO TO RAD999
           END-IF.

           IF WS-RESP-COUNT > 20
              MOVE 20 TO WS-RESP-COUNT
           END-IF.
           PERFORM VARYING WS-ACC-IX FROM 1 BY 1
                   UNTIL WS-ACC-IX > WS-RESP-COUNT
              MOVE WS-ACC-IX TO NUMBER-OF-ACCOUNTS
              MOVE JA-EYE(WS-ACC-IX)
                 TO COMM-EYE(NUMBER-OF-ACCOUNTS)
              MOVE JA-CUSTNO(WS-ACC-IX)
                 TO COMM-CUSTNO(NUMBER-OF-ACCOUNTS)
              MOVE JA-SCODE(WS-ACC-IX)
                 TO COMM-SCODE(NUMBER-OF-ACCOUNTS)
              MOVE JA-ACCNO(WS-ACC-IX)
                 TO COMM-ACCNO(NUMBER-OF-ACCOUNTS)
              MOVE JA-ACC-TYPE(WS-ACC-IX)
                 TO COMM-ACC-TYPE(NUMBER-OF-ACCOUNTS)
              COMPUTE COMM-INT-RATE(NUMBER-OF-ACCOUNTS) =
                 FUNCTION NUMVAL(JA-INT-RATE(WS-ACC-IX))
              MOVE JA-OPENED(WS-ACC-IX)
                 TO COMM-OPENED(NUMBER-OF-ACCOUNTS)
              MOVE JA-OVERDRAFT(WS-ACC-IX)
                 TO COMM-OVERDRAFT(NUMBER-OF-ACCOUNTS)
              MOVE JA-LAST-STMT(WS-ACC-IX)
                 TO COMM-LAST-STMT-DT(NUMBER-OF-ACCOUNTS)
              MOVE JA-NEXT-STMT(WS-ACC-IX)
                 TO COMM-NEXT-STMT-DT(NUMBER-OF-ACCOUNTS)
              COMPUTE COMM-ACTUAL-BAL(NUMBER-OF-ACCOUNTS) =
                 FUNCTION NUMVAL(JA-ACTUAL-BAL(WS-ACC-IX))
              COMPUTE COMM-AVAIL-BAL(NUMBER-OF-ACCOUNTS) =
                 FUNCTION NUMVAL(JA-AVAIL-BAL(WS-ACC-IX))
           END-PERFORM.

           MOVE 'Y' TO COMM-SUCCESS.

       RAD999.
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
      *    Return control back to CICS
      *
           EXEC CICS RETURN
           END-EXEC.

           GOBACK.

       GMOFH999.
           EXIT.


      *
      * Check for Storm Drain Condition (DB2)
      *
       ABEND-HANDLING SECTION.
       AH010.

           EXEC CICS ASSIGN
              ABCODE(MY-ABEND-CODE)
           END-EXEC.

      *
      *    Evaluate the Abend code that is returned
      *     - For DB2 AD2Z ... provide some diagnostics.
      *     - For VSAM RLS abends: AFCR, AFCS and AFCT record the
      *       Abend as happening but do not abend ... leave this to
      *       CPSM WLM "Storm drain" (Abend probability) to handle
      *     If not a "storm drain" ... take the abend afterwards
      *
           EVALUATE MY-ABEND-CODE

      *
      *       DB2 AD2Z abend
      *
              WHEN 'AD2Z'
                 DISPLAY 'AD2Z ABEND IN INQACCCU FOR CUSTOMER '
                    CUSTOMER-NUMBER OF DFHCOMMAREA

      *
      *          VSAM RLS abends, subject to CPSM WLM Storm Drain check
      *          if handled (as here) and Workload Abend Thresholds are
      *          Set.
      *
              WHEN 'AFCR'

              WHEN 'AFCS'

              WHEN 'AFCT'
                 MOVE 'Y' TO WS-STORM-DRAIN
                 DISPLAY 'INQACCCU: Check-For-Storm-Drain-VSAM: Storm '
                        'Drain condition (Abend ' MY-ABEND-CODE ') '
                        'has been met.'

                 EXEC CICS SYNCPOINT ROLLBACK
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

                    MOVE 0 TO ABND-SQLCODE

                    STRING 'AH010 -Unable to perform SYNCPOINT '
                       DELIMITED BY SIZE,
                       'ROLLBACK' DELIMITED BY SIZE,
                       ' Possible integrity issue following VSAM RLS '
                       DELIMITED BY SIZE,
                       ' abend.' DELIMITED BY SIZE,
                       'EIBRESP=' DELIMITED BY SIZE,
                       ABND-RESPCODE DELIMITED BY SIZE,
                       ' RESP2=' DELIMITED BY SIZE,
                       ABND-RESP2CODE DELIMITED BY SIZE
                       INTO ABND-FREEFORM
                    END-STRING

                    EXEC CICS LINK PROGRAM(WS-ABEND-PGM)
                           COMMAREA(ABNDINFO-REC)
                    END-EXEC

                    DISPLAY 'INQACCC: Unable to perform Syncpoint.'
                       ' Rollback. Possible Integrity issue following '
                       ' VSAM RLS abend RESP CODE=' WS-CICS-RESP
                       ' RESP2 CODE=' WS-CICS-RESP2

                    EXEC CICS ABEND
                       ABCODE ('HROL')
                       CANCEL
                    END-EXEC

                 END-IF

                 MOVE 'N' TO COMM-SUCCESS

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


       CUSTOMER-CHECK SECTION.
       CC010.
      *
      *    Retrieve customer information by linking to INQCUST
      *

           IF CUSTOMER-NUMBER IN DFHCOMMAREA = ZERO
              MOVE 'N' TO CUSTOMER-FOUND
              MOVE ZERO TO NUMBER-OF-ACCOUNTS
              GO TO CC999
           END-IF.

           IF CUSTOMER-NUMBER IN DFHCOMMAREA = '9999999999'
              MOVE 'N' TO CUSTOMER-FOUND
              MOVE ZERO TO NUMBER-OF-ACCOUNTS
              GO TO CC999
           END-IF.

           INITIALIZE INQCUST-COMMAREA.

           MOVE CUSTOMER-NUMBER IN DFHCOMMAREA TO INQCUST-CUSTNO.

           EXEC CICS LINK PROGRAM('INQCUST ')
              COMMAREA(INQCUST-COMMAREA)
           END-EXEC.

           IF INQCUST-INQ-SUCCESS = 'Y'
              MOVE 'Y' TO CUSTOMER-FOUND
           ELSE
              MOVE 'N' TO CUSTOMER-FOUND
              MOVE ZERO TO NUMBER-OF-ACCOUNTS
           END-IF.

       CC999.
           EXIT.


       POPULATE-TIME-DATE SECTION.
       PTD010.
      D    DISPLAY 'POPULATE-TIME-DATE SECTION'.

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
