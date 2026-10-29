       CBL CICS('SP,EDF,DLI')
      ******************************************************************
      *                                                                *
      *  Copyright IBM Corp. 2023                                      *
      *                                                                *
      ******************************************************************

      ******************************************************************
      * This program takes an incoming account number. It then accesses
      * the DB2 datastore & retrieves the associated account record/row
      * matching on the account number & the account_type.
      *
      * Should there be any issues, the program will abend.
      *
      ******************************************************************

       IDENTIFICATION DIVISION.
       PROGRAM-ID. INQACC.
       AUTHOR. Jon Collett.

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


      * Get the ACCOUNT DB2 copybook
       01 WS-DSV.
          COPY DATASVC.

      * Envelope + account returned by GET /data/account/...
       01 WS-DSV-RESP.
          03 WS-RESP-SUCCESS            PIC X.
          03 WS-RESP-FAILCODE           PIC X.
          03 WS-RESP-ABEND              PIC X(4).
          03 WS-RESP-ACCOUNT.
             COPY ACCJSON.

       01 WS-DSV-ABEND                  PIC X(4).

       01 WS-CICS-WORK-AREA.
          05 WS-CICS-RESP              PIC S9(8) COMP.
          05 WS-CICS-RESP2             PIC S9(8) COMP.

       01 EXIT-BROWSE-LOOP             PIC X VALUE 'N'.

       01 OUTPUT-DATA.
           COPY ACCOUNT.

       01 RETURNED-DATA.
           03 RETURNED-EYE-CATCHER     PIC X(4).
           03 RETURNED-CUST-NO         PIC 9(10).
           03 RETURNED-KEY.
              05 RETURNED-SORT-CODE       PIC 9(6).
              05 RETURNED-NUMBER          PIC 9(8).
           03 RETURNED-TYPE            PIC X(8).
           03 RETURNED-INTEREST-RATE   PIC 9(4)V99.
           03 RETURNED-OPENED          PIC 9(8).
           03 RETURNED-OVERDRAFT-LIMIT PIC 9(8).
           03 RETURNED-LAST-STMT-DATE  PIC 9(8).
           03 RETURNED-NEXT-STMT-DATE  PIC 9(8).
           03 RETURNED-AVAILABLE-BALANCE  PIC S9(10)V99.
           03 RETURNED-ACTUAL-BALANCE     PIC S9(10)V99.

       01 DESIRED-KEY                  PIC 9(10) BINARY.

       01 DB2-DATE-REFORMAT.
          03 DB2-DATE-REF-YR           PIC 9(4).
          03 FILLER                    PIC X.
          03 DB2-DATE-REF-MNTH         PIC 99.
          03 FILLER                    PIC X.
          03 DB2-DATE-REF-DAY          PIC 99.

       01 DATA-STORE-TYPE              PIC X.
          88 DATASTORE-TYPE-DB2           VALUE '2'.
          88 DATASTORE-TYPE-VSAM          VALUE 'V'.

       01 DB2-EXIT-LOOP                PIC X.
       01 FETCH-DATA-CNT               PIC 9(4) COMP.
       01 WS-CUST-ALT-KEY-LEN          PIC S9(4) COMP VALUE +10.

       01 ACCOUNT-KY.
          03 REQUIRED-SORT-CODE        PIC 9(6)  VALUE 0.
          03 REQUIRED-ACC-NUM          PIC 9(8)  VALUE 0.

       01 MY-ABEND-CODE                PIC XXXX.

       01 WS-STORM-DRAIN               PIC X VALUE 'N'.

       01 STORM-DRAIN-CONDITION        PIC X(20).


      *
      * ACCOUNT NCS definitions
      *
       01 NCS-ACC-NO-STUFF.
          03 NCS-ACC-NO-NAME.
             05 NCS-ACC-NO-ACT-NAME    PIC X(8)
                                          VALUE 'HBNKACCT'.
             05 NCS-ACC-NO-TEST-SORT   PIC X(6)
                                          VALUE '      '.
             05 NCS-ACC-NO-FILL        PIC XX
                                          VALUE '  '.
          03 NCS-ACC-NO-INC            PIC 9(16) COMP
                                          VALUE 0.
          03 NCS-ACC-NO-VALUE          PIC 9(16) COMP
                                          VALUE 0.
          03 NCS-ACC-NO-RESP           PIC XX VALUE '00'.

       01 WS-DISP-ACC-NO-VAL           PIC S9(18) DISPLAY.

       01 ACCOUNT-KY2.
          03 REQUIRED-SORT-CODE2       PIC 9(6) VALUE 0.
          03 REQUIRED-ACC-NUMBER2      PIC 9(8) VALUE 0.

       01 WS-POINTER USAGE POINTER.
       01 WS-POINTER-BYTES  REDEFINES WS-POINTER  PIC X(8).
       01 WS-POINTER-NUMBER  REDEFINES WS-POINTER PIC 9(8) BINARY.

       01 WS-POINTER-NUMBER-DISPLAY    PIC 9(8) DISPLAY.

       01 WS-U-TIME                    PIC S9(15) COMP-3.
       01 WS-ORIG-DATE                 PIC X(10).
       01 WS-ORIG-DATE-GRP REDEFINES WS-ORIG-DATE.
          03 WS-ORIG-DATE-DD           PIC 99.
          03 FILLER                    PIC X.
          03 WS-ORIG-DATE-MM           PIC 99.
          03 FILLER                    PIC X.
          03 WS-ORIG-DATE-YYYY         PIC 9999.

       01 WS-ORIG-DATE-GRP-X.
          03 WS-ORIG-DATE-DD-X         PIC XX.
          03 FILLER                    PIC X VALUE '.'.
          03 WS-ORIG-DATE-MM-X         PIC XX.
          03 FILLER                    PIC X VALUE '.'.
          03 WS-ORIG-DATE-YYYY-X       PIC X(4).

       01 WS-TIME-DATA.
           03 WS-TIME-NOW              PIC 9(6).
           03 WS-TIME-NOW-GRP REDEFINES WS-TIME-NOW.
              05 WS-TIME-NOW-GRP-HH       PIC 99.
              05 WS-TIME-NOW-GRP-MM       PIC 99.
              05 WS-TIME-NOW-GRP-SS       PIC 99.

       01 WS-ABEND-PGM                 PIC X(8) VALUE 'ABNDPROC'.

       01 ABNDINFO-REC.
           COPY ABNDINFO.

       LINKAGE SECTION.
       COPY INQACC REPLACING INQACC-COMMAREA BY DFHCOMMAREA.


       PROCEDURE DIVISION USING DFHCOMMAREA.
       PREMIERE SECTION.
       A010.

           INITIALIZE OUTPUT-DATA.
      *
      *    Set up the abend handling
      *
           EXEC CICS HANDLE
              ABEND LABEL(ABEND-HANDLING)
           END-EXEC.

           MOVE SORTCODE TO REQUIRED-SORT-CODE OF ACCOUNT-KY.
      *
      *          Get the ACCOUNT row from DB2
      *
           IF INQACC-ACCNO = 99999999
             PERFORM READ-ACCOUNT-LAST
           ELSE
             PERFORM READ-ACCOUNT-DATASVC
           END-IF


      *
      *    Return the ACCOUNT data to the COMMAREA
      *

           IF ACCOUNT-TYPE = SPACES OR LOW-VALUES
              MOVE 'N' TO INQACC-SUCCESS
           ELSE
              MOVE ACCOUNT-EYE-CATCHER       TO INQACC-EYE
              MOVE ACCOUNT-CUST-NO           TO INQACC-CUSTNO
              MOVE ACCOUNT-SORT-CODE         TO INQACC-SCODE
              MOVE ACCOUNT-NUMBER            TO INQACC-ACCNO
              MOVE ACCOUNT-TYPE              TO INQACC-ACC-TYPE
              MOVE ACCOUNT-INTEREST-RATE     TO INQACC-INT-RATE
              MOVE ACCOUNT-OPENED            TO INQACC-OPENED
              MOVE ACCOUNT-OVERDRAFT-LIMIT   TO INQACC-OVERDRAFT
              MOVE ACCOUNT-LAST-STMT-DATE    TO INQACC-LAST-STMT-DT
              MOVE ACCOUNT-NEXT-STMT-DATE    TO INQACC-NEXT-STMT-DT
              MOVE ACCOUNT-AVAILABLE-BALANCE TO INQACC-AVAIL-BAL
              MOVE ACCOUNT-ACTUAL-BALANCE    TO INQACC-ACTUAL-BAL
              MOVE 'Y'                       TO INQACC-SUCCESS
           END-IF.

           PERFORM GET-ME-OUT-OF-HERE.

       A999.
           EXIT.


       READ-ACCOUNT-DATASVC SECTION.
       RAD010.
      *
      *    GET /data/account/{sortcode}/{number} replaces the
      *    ACC-CURSOR OPEN / FETCH / CLOSE (READ-ACCOUNT-DB2 and
      *    FETCH-DATA).
      *
           MOVE SPACES TO DSV-PATH.
           SET DSV-GET TO TRUE.
           MOVE 0 TO DSV-REQ-LEN.
           STRING '/data/account/' DELIMITED BY SIZE
                  SORTCODE DELIMITED BY SIZE
                  '/' DELIMITED BY SIZE
                  INQACC-ACCNO DELIMITED BY SIZE
             INTO DSV-PATH
           END-STRING.
           PERFORM CALL-DATASVC.
           IF DSV-RC NOT = '0'
              DISPLAY 'INQACC: data service call failed RC=' DSV-RC
                 ' RESP=' DSV-CICS-RESP ' RESP2=' DSV-CICS-RESP2
              MOVE 'HRAC' TO WS-DSV-ABEND
              PERFORM ABEND-DATASVC
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
                   DISPLAY 'INQACC: bad data service response HTTP='
                      DSV-HTTP-STATUS ' JSON-CODE=' JSON-CODE
                   MOVE 'HRAC' TO WS-DSV-ABEND
                   PERFORM ABEND-DATASVC
           END-JSON.
           IF WS-RESP-ABEND NOT = SPACES
              MOVE WS-RESP-ABEND TO WS-DSV-ABEND
              PERFORM ABEND-DATASVC
           END-IF.

      *    Not found (legacy SQLCODE +100): blank account type makes
      *    A010 return INQACC-SUCCESS = 'N'.
           IF WS-RESP-SUCCESS NOT = 'Y'
              INITIALIZE OUTPUT-DATA
              MOVE SORTCODE TO ACCOUNT-SORT-CODE OF OUTPUT-DATA
              MOVE INQACC-ACCNO TO ACCOUNT-NUMBER OF OUTPUT-DATA
              GO TO RAD999
           END-IF.

           PERFORM MAP-ACCOUNT-TO-OUTPUT.

       RAD999.
           EXIT.


       MAP-ACCOUNT-TO-OUTPUT SECTION.
       MAO010.
           MOVE JA-EYE       TO ACCOUNT-EYE-CATCHER OF OUTPUT-DATA.
           MOVE JA-CUSTNO    TO ACCOUNT-CUST-NO OF OUTPUT-DATA.
           MOVE JA-SCODE     TO ACCOUNT-SORT-CODE OF OUTPUT-DATA.
           MOVE JA-ACCNO     TO ACCOUNT-NUMBER OF OUTPUT-DATA.
           MOVE JA-ACC-TYPE  TO ACCOUNT-TYPE OF OUTPUT-DATA.
           COMPUTE ACCOUNT-INTEREST-RATE OF OUTPUT-DATA =
              FUNCTION NUMVAL(JA-INT-RATE).
           MOVE JA-OPENED    TO ACCOUNT-OPENED OF OUTPUT-DATA.
           MOVE JA-OVERDRAFT TO ACCOUNT-OVERDRAFT-LIMIT OF OUTPUT-DATA.
           MOVE JA-LAST-STMT TO ACCOUNT-LAST-STMT-DATE OF OUTPUT-DATA.
           MOVE JA-NEXT-STMT TO ACCOUNT-NEXT-STMT-DATE OF OUTPUT-DATA.
           COMPUTE ACCOUNT-AVAILABLE-BALANCE OF OUTPUT-DATA =
              FUNCTION NUMVAL(JA-AVAIL-BAL).
           COMPUTE ACCOUNT-ACTUAL-BALANCE OF OUTPUT-DATA =
              FUNCTION NUMVAL(JA-ACTUAL-BAL).
       MAO999.
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


      *    Same abend the legacy SQL path issued (HRAC / HNCS); the
      *    data service has already rolled back its work.
       ABEND-DATASVC SECTION.
       ABD010.
           EXEC CICS ABEND ABCODE(WS-DSV-ABEND)
                CANCEL
                NODUMP
           END-EXEC.
       ABD999.
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

           EXEC CICS ASSIGN ABCODE(MY-ABEND-CODE)
           END-EXEC.

      *
      *    Evaluate the Abend code that is returned
      *      - For DB2 AD2Z ... provide some diagnostics
      *      - For VSAM RLS abends: AFCR, AFCS and AFCT record the
      *     Abend as happening but do not abend ... leave this to
      *     CPSM WLM "Storm drain" (Abend probability) to handle
      *    If not a "storm drain" ... take the abend afterwards
      *
           EVALUATE MY-ABEND-CODE

      *
      *       DB2 AD2Z abend
      *
              WHEN 'AD2Z'

                 DISPLAY 'AD2Z ABEND IN INQACC FOR ACCOUNT '
                    INQACC-ACCNO

      *
      *          VSAM RLS abends, subject to CPSM WLM Storm Drain check
      *          if handled (as here) and Workload Abend Thresholds are
      *          set.
      *
              WHEN 'AFCR'

              WHEN 'AFCS'

              WHEN 'AFCT'
                 MOVE 'Y' TO WS-STORM-DRAIN
                 DISPLAY 'INQACC: Check-For-Storm-Drain-VSAM: Storm '
                       'Drain condition (Abend ' MY-ABEND-CODE ') '
                       'has been met.'

                 EXEC CICS SYNCPOINT ROLLBACK
                    RESP(WS-CICS-RESP)
                    RESP2(WS-CICS-RESP2)
                 END-EXEC

                IF WS-CICS-RESP NOT = DFHRESP(NORMAL)

      *
      *            Preserve the RESP and RESP2, then set up the
      *            standard ABEND info before getting the applid,
      *            date/time etc. and linking to the Abend Handler
      *            program.
      *
                   INITIALIZE ABNDINFO-REC
                   MOVE EIBRESP    TO ABND-RESPCODE
                   MOVE EIBRESP2   TO ABND-RESP2CODE
      *
      *            Get supplemental information
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

                   STRING 'AH010 -Unable to perform SYNCPOINT ROLLBACK.'
                       DELIMITED BY SIZE,
                       ' Possible integrity issue following VSAM RLS '
                       DELIMITED BY SIZE,
                       ' abend.' DELIMITED BY SIZE,
                       ' EIBRESP=' DELIMITED BY SIZE,
                       ABND-RESPCODE DELIMITED BY SIZE,
                       ' RESP2=' DELIMITED BY SIZE,
                       ABND-RESP2CODE DELIMITED BY SIZE
                       INTO ABND-FREEFORM
                   END-STRING

                   EXEC CICS LINK PROGRAM(WS-ABEND-PGM)
                           COMMAREA(ABNDINFO-REC)
                   END-EXEC

                   DISPLAY 'INQACC: Unable to perform Syncpoint '
                           'Rollback. Possible Integrity issue '
                           ' following VSAM RLS abend. '
                           ' RESP CODE=' WS-CICS-RESP
                           ' RESP2 CODE=' WS-CICS-RESP2

                   EXEC CICS ABEND
                      ABCODE ('HROL')
                      NODUMP
                       CANCEL
                   END-EXEC

                END-IF

                MOVE 'N' TO INQACC-SUCCESS

                EXEC CICS RETURN
                END-EXEC

           END-EVALUATE.

           IF WS-STORM-DRAIN = 'N'
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
              MOVE MY-ABEND-CODE TO ABND-CODE

              EXEC CICS ASSIGN PROGRAM(ABND-PROGRAM)
              END-EXEC

              MOVE ZEROS      TO ABND-SQLCODE

              STRING 'AH010 -WVS-STORM-DRAIN=N'
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

              EXEC CICS ABEND ABCODE( MY-ABEND-CODE)
              NODUMP
              CANCEL
              END-EXEC

           END-IF.

       AH999.
           EXIT.

      *
      * Access the ACCOUNT Named Counter Server
      *
       READ-ACCOUNT-LAST SECTION.
       RAN010.

            PERFORM GET-LAST-ACCOUNT-DATASVC.
            MOVE REQUIRED-ACC-NUMBER2 TO NCS-ACC-NO-VALUE.

       RAN999.
           EXIT.


       GET-LAST-ACCOUNT-DATASVC SECTION.
       GLAD010.
      *
      *    GET /data/account/{sortcode}/last replaces the SELECT ...
      *    ORDER BY ACCOUNT_NUMBER DESC FETCH FIRST 1 ROW ONLY. Any
      *    failure, including no accounts, abends HNCS as before.
      *
           INITIALIZE OUTPUT-DATA.
           MOVE SPACES TO DSV-PATH.
           SET DSV-GET TO TRUE.
           MOVE 0 TO DSV-REQ-LEN.
           STRING '/data/account/' DELIMITED BY SIZE
                  SORTCODE DELIMITED BY SIZE
                  '/last' DELIMITED BY SIZE
             INTO DSV-PATH
           END-STRING.
           PERFORM CALL-DATASVC.
           IF DSV-RC NOT = '0'
              DISPLAY 'INQACC: data service call failed RC=' DSV-RC
                 ' RESP=' DSV-CICS-RESP ' RESP2=' DSV-CICS-RESP2
              MOVE 'HNCS' TO WS-DSV-ABEND
              PERFORM ABEND-DATASVC
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
                   DISPLAY 'INQACC: bad data service response HTTP='
                      DSV-HTTP-STATUS ' JSON-CODE=' JSON-CODE
                   MOVE 'HNCS' TO WS-DSV-ABEND
                   PERFORM ABEND-DATASVC
           END-JSON.
           IF WS-RESP-SUCCESS NOT = 'Y'
              DISPLAY 'INQACC - ACCOUNT NCS ' NCS-ACC-NO-NAME
                 ' CANNOT BE ACCESSED. HTTP=' DSV-HTTP-STATUS
              MOVE 'HNCS' TO WS-DSV-ABEND
              PERFORM ABEND-DATASVC
           END-IF.

           PERFORM MAP-ACCOUNT-TO-OUTPUT.

       GLAD999.
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
