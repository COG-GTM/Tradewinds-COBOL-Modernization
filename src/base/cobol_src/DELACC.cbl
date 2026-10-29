       CBL CICS('SP,EDF')
      ******************************************************************
      *                                                                *
      *  Copyright IBM Corp. 2023                                      *
      *                                                                *
      ******************************************************************
      ******************************************************************
      * This program takes an incoming account number it then accesses
      * the account datastore and retrieves the associated account
      * record matching on the customer number & the account_type
      * and then deletes it.
      *
      * If no matching customer_number and account_type combination can
      * found it will return an error flag and the visual1sation layer
      * should process this accordingly.
      *
      * Should there be any issues, the program will abend.
      *
      * It is assumed that the incoming customer number will be valid.
      *****************************************************************

       IDENTIFICATION DIVISION.
       PROGRAM-ID. DELACC.
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



       01 SYSIDERR-RETRY               PIC 999.
       01 FILE-RETRY                   PIC 999.
       01 WS-EXIT-RETRY-LOOP           PIC X VALUE ' '.

      * Account data service (DATASVC) areas
       01 WS-DSV.
          COPY DATASVC.

      * Envelope + deleted account returned by
      * DELETE /data/account/{sc}/{num}
       01 WS-DSV-RESP.
          03 WS-RESP-SUCCESS            PIC X.
          03 WS-RESP-FAILCODE           PIC X.
          03 WS-RESP-ABEND              PIC X(4).
          03 WS-RESP-ACCOUNT.
             COPY ACCJSON.

       01 WS-DSV-TASKREF                PIC 9(12) VALUE 0.

       01 WS-CICS-WORK-AREA.
           05 WS-CICS-RESP              PIC S9(8) COMP.
           05 WS-CICS-RESP2             PIC S9(8) COMP.
           05 WS-EIBRESP-DISPLAY        PIC S9(8) DISPLAY
                                   SIGN LEADING SEPARATE.

       01 WS-APPLID                    PIC X(8).

       01 EXIT-BROWSE-LOOP             PIC X VALUE 'N'.

       01 OUTPUT-DATA.
           COPY ACCOUNT.

       01 PROCTRAN-AREA.
          COPY PROCTRAN.

       01 PROCTRAN-RIDFLD              PIC S9(8) COMP.
       77 PROCTRAN-RETRY               PIC 999.

       01 ACCOUNT-ACT-BAL-STORE        PIC S9(10)V99 VALUE 0.

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

       01 WS-EIBTASKN12                PIC 9(12) VALUE 0.

       01 ACCOUNT-KEY-RID.
          03 REQUIRED-SORT-CODE        PIC 9(6)  VALUE 0.
          03 REQUIRED-ACC-NUM          PIC 9(8)  VALUE 0.

       01 MY-TCB                       PIC S9(8) BINARY.

       01 MY-TCB-STRING                PIC X(8).

       01 WS-ACC-KEY-LEN               PIC S9(4) COMP VALUE +14.
       01 WS-ACC-NUM                   PIC S9(4) COMP VALUE 0.

       01 WS-U-TIME                    PIC S9(15) COMP-3.
       01 WS-ORIG-DATE                 PIC X(10).
       01 WS-ORIG-DATE-GRP REDEFINES WS-ORIG-DATE.
          03 WS-ORIG-DATE-DD              PIC 99.
          03 FILLER                       PIC X.
          03 WS-ORIG-DATE-MM              PIC 99.
          03 FILLER                       PIC X.
          03 WS-ORIG-DATE-YYYY            PIC 9999.

       01 WS-ORIG-DATE-GRP-X.
          03 WS-ORIG-DATE-DD-X         PIC XX.
          03 FILLER                    PIC X VALUE '.'.
          03 WS-ORIG-DATE-MM-X         PIC XX.
          03 FILLER                    PIC X VALUE '.'.
          03 WS-ORIG-DATE-YYYY-X       PIC X(4).


       01 WS-TOKEN                     PIC S9(8) BINARY.


       01 STORM-DRAIN-CONDITION        PIC X(20).

       01 ACCOUNT-CONTROL.
           COPY ACCTCTRL.

       01 WS-TIME-DATA.
           03 WS-TIME-NOW              PIC 9(6).
           03 WS-TIME-NOW-GRP REDEFINES WS-TIME-NOW.
              05 WS-TIME-NOW-GRP-HH       PIC 99.
              05 WS-TIME-NOW-GRP-MM       PIC 99.
              05 WS-TIME-NOW-GRP-SS       PIC 99.

       01 WS-ABEND-PGM                    PIC X(8) VALUE 'ABNDPROC'.

       01 ABNDINFO-REC.
           COPY ABNDINFO.


       LINKAGE SECTION.
       COPY DELACC REPLACING DELACC-COMMAREA BY DFHCOMMAREA.


       PROCEDURE DIVISION USING DFHCOMMAREA.
       PREMIERE SECTION.
       A010.

           MOVE SORTCODE TO REQUIRED-SORT-CODE OF ACCOUNT-KEY-RID.

      *
      *          Get the account record
      *
           PERFORM DELETE-ACCOUNT-DATASVC.
           PERFORM GET-ME-OUT-OF-HERE.

       A999.
           EXIT.


      *
      * Call the account data service and process the data
      *
       DELETE-ACCOUNT-DATASVC SECTION.
       DAD010.
      *
      *    DELETE /data/account/{sortcode}/{number}?taskRef=EIBTASKN
      *    replaces READ-ACCOUNT-DB2, DEL-ACCOUNT-DB2 and
      *    WRITE-PROCTRAN-DB2: the data service reads the row,
      *    deletes it and writes the ODA PROCTRAN row atomically and
      *    returns the deleted account.
      *
           MOVE EIBTASKN TO WS-DSV-TASKREF.
           MOVE SPACES TO DSV-PATH.
           SET DSV-DELETE TO TRUE.
           MOVE 0 TO DSV-REQ-LEN.
           STRING '/data/account/' DELIMITED BY SIZE
                  SORTCODE DELIMITED BY SIZE
                  '/' DELIMITED BY SIZE
                  DELACC-ACCNO DELIMITED BY SIZE
                  '?taskRef=' DELIMITED BY SIZE
                  WS-DSV-TASKREF DELIMITED BY SIZE
             INTO DSV-PATH
           END-STRING.

           PERFORM CALL-DATASVC.

      *    Transport failure: legacy abended HRAC when the ACCOUNT
      *    read failed.
           IF DSV-RC NOT = '0'
              DISPLAY 'DELACC: data service call failed RC=' DSV-RC
                 ' RESP=' DSV-CICS-RESP ' RESP2=' DSV-CICS-RESP2
              EXEC CICS ABEND ABCODE('HRAC')
                   NODUMP
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
                   DISPLAY 'DELACC: bad data service response HTTP='
                      DSV-HTTP-STATUS ' JSON-CODE=' JSON-CODE
                   EXEC CICS ABEND ABCODE('HRAC')
                        NODUMP
                   END-EXEC
           END-JSON.

      *    HRAC (read failed) or HWPT (PROCTRAN insert failed): the
      *    data service has already rolled back; re-issue the abend.
           IF WS-RESP-ABEND NOT = SPACES
              EXEC CICS ABEND ABCODE(WS-RESP-ABEND)
              END-EXEC
           END-IF.

           MOVE ' ' TO DELACC-SUCCESS.
           IF WS-RESP-SUCCESS NOT = 'Y'
      *       '1' = not found, '3' = DELETE failed
              MOVE 'N' TO DELACC-DEL-SUCCESS
              MOVE WS-RESP-FAILCODE TO DELACC-DEL-FAIL-CD
              GO TO DAD999
           END-IF.

           MOVE JA-EYE                TO DELACC-EYE.
           MOVE JA-CUSTNO             TO DELACC-CUSTNO.
           MOVE JA-SCODE              TO DELACC-SCODE.
           MOVE JA-ACCNO              TO DELACC-ACCNO.
           MOVE JA-ACC-TYPE           TO DELACC-ACC-TYPE.
           COMPUTE DELACC-INT-RATE = FUNCTION NUMVAL(JA-INT-RATE).
           MOVE JA-OPENED             TO DELACC-OPENED.
           MOVE JA-OVERDRAFT          TO DELACC-OVERDRAFT.
           MOVE JA-LAST-STMT          TO DELACC-LAST-STMT-DT.
           MOVE JA-NEXT-STMT          TO DELACC-NEXT-STMT-DT.
           COMPUTE DELACC-AVAIL-BAL = FUNCTION NUMVAL(JA-AVAIL-BAL).
           COMPUTE DELACC-ACTUAL-BAL =
              FUNCTION NUMVAL(JA-ACTUAL-BAL).
           MOVE 'Y' TO DELACC-DEL-SUCCESS.
           MOVE ' ' TO DELACC-DEL-FAIL-CD.

       DAD999.
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

           GOBACK.

       GMOFH999.
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
