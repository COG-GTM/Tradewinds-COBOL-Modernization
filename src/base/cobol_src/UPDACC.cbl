       PROCESS CICS,NODYNAM,NSYMBOL(NATIONAL),TRUNC(STD)
       CBL CICS('SP,EDF,DLI')
      ******************************************************************
      *                                                                *
      *  Copyright IBM Corp. 2023                                      *
      *                                                                *
      ******************************************************************
      ******************************************************************
      * This program gets called when someone updates the account
      * details (this excludes the balance which must be updated by
      * crediting or debitting money to/from the account).
      *
      * The program receives as input all of the fields which make
      * up the Account record.
      *
      * It then calls the account data service & updates the associated
      * Account record.
      *
      * Because it is only permissible to change a limited number of
      * fields on the Account record, and it is NOT possible to amend
      * the balance, no record needs to be written to PR0CTRAN (as the
      * the balance cannot be amended using this method). At the
      * current time, no details of the change are recorded (all that
      * can be amended is the Account Type, the Interest Rate,
      * the overdraft limit and the last and next statement dates). The
      * presentation layer is responsible for ensuring that the fields
      * are validated.
      *
      * If the Account cannot be updated then a failure flag is returned
      * to the calling program.
      *
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. UPDACC.
       AUTHOR. Jon Collett.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
      * SOURCE-COMPUTER.   IBM-370 WITH DEBUGGING MODE.
       SOURCE-COMPUTER.  IBM-370.
       OBJECT-COMPUTER.  IBM-370.

       INPUT-OUTPUT SECTION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.

       COPY SORTCODE.



       77 SYSIDERR-RETRY                PIC 999.

      * Account data service (DATASVC) areas
       01 WS-DSV.
          COPY DATASVC.

      * Envelope + account returned by PUT /data/account/{sc}/{num}
       01 WS-DSV-RESP.
          03 WS-RESP-SUCCESS            PIC X.
          03 WS-RESP-FAILCODE           PIC X.
          03 WS-RESP-ABEND              PIC X(4).
          03 WS-RESP-ACCOUNT.
             COPY ACCJSON.

       01 WS-PTR                        PIC S9(8) COMP.
       01 WS-EDIT-RATE                  PIC Z(3)9.99.
       01 WS-EDIT-OVERDRAFT             PIC Z(7)9.

       01 WS-CICS-WORK-AREA.
          05 WS-CICS-RESP               PIC S9(8) COMP.
          05 WS-CICS-RESP2              PIC S9(8) COMP.


       LOCAL-STORAGE SECTION.


       01 WS-ACC-DATA.
          COPY ACCOUNT.

       01 WS-EIBTASKN12                 PIC 9(12)     VALUE 0.

      *
      * Pull in the input and output data structures
      *
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

       01 REJ-REASON                    PIC XX        VALUE SPACES.

       01 CUSTOMER-KY.
          03 REQUIRED-SORT-CODE         PIC 9(6)      VALUE 0.
          03 REQUIRED-ACC-NUM           PIC 9(8)      VALUE 0.

       01 STORM-DRAIN-CONDITION         PIC X(20).

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
           COPY UPDACC.

       PROCEDURE DIVISION.
       PREMIERE SECTION.
       A010.

           MOVE SORTCODE TO COMM-SCODE.
           MOVE SORTCODE TO DESIRED-SORT-CODE.

      *
      *           Update the account information
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
      *
      *    PUT /data/account/{sortcode}/{number}: the data service
      *    reads the row, rejects a blank account type and updates
      *    ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE and
      *    ACCOUNT_OVERDRAFT_LIMIT (formerly SELECT + UPDATE here).
      *
           MOVE COMM-ACCNO TO DESIRED-ACC-NO.
           MOVE COMM-INT-RATE TO WS-EDIT-RATE.
           MOVE COMM-OVERDRAFT TO WS-EDIT-OVERDRAFT.

           MOVE SPACES TO DSV-PATH DSV-REQ-BODY.
           SET DSV-PUT TO TRUE.
           STRING '/data/account/' DELIMITED BY SIZE
                  DESIRED-SORT-CODE DELIMITED BY SIZE
                  '/' DELIMITED BY SIZE
                  DESIRED-ACC-NO DELIMITED BY SIZE
             INTO DSV-PATH
           END-STRING.
           MOVE 1 TO WS-PTR.
           STRING '{"accountType":"' DELIMITED BY SIZE
                  COMM-ACC-TYPE DELIMITED BY SIZE
                  '","interestRate":' DELIMITED BY SIZE
                  FUNCTION TRIM(WS-EDIT-RATE) DELIMITED BY SIZE
                  ',"overdraftLimit":' DELIMITED BY SIZE
                  FUNCTION TRIM(WS-EDIT-OVERDRAFT) DELIMITED BY SIZE
                  '}' DELIMITED BY SIZE
             INTO DSV-REQ-BODY WITH POINTER WS-PTR
           END-STRING.
           COMPUTE DSV-REQ-LEN = WS-PTR - 1.

           PERFORM CALL-DATASVC.

      *
      *    UPDACC never abended on a database failure: it only set
      *    COMM-SUCCESS to 'N'. Keep that for transport failures too.
      *
           IF DSV-RC NOT = '0'
              MOVE 'N' TO COMM-SUCCESS
              DISPLAY 'ERROR: UPDACC data service call failed RC='
                 DSV-RC ' RESP=' DSV-CICS-RESP
                 ' RESP2=' DSV-CICS-RESP2
              GO TO UAD999
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
                   MOVE 'N' TO WS-RESP-SUCCESS
                   DISPLAY 'ERROR: UPDACC bad data service response '
                      'HTTP=' DSV-HTTP-STATUS ' JSON-CODE=' JSON-CODE
           END-JSON.

           IF WS-RESP-SUCCESS NOT = 'Y'
              MOVE 'N' TO COMM-SUCCESS
              DISPLAY 'ERROR: UPDACC data service returned HTTP '
                 DSV-HTTP-STATUS ' failCode=' WS-RESP-FAILCODE
              GO TO UAD999
           END-IF.

           MOVE JA-EYE                TO COMM-EYE.
           MOVE JA-CUSTNO             TO COMM-CUSTNO.
           MOVE JA-SCODE              TO COMM-SCODE.
           MOVE JA-ACCNO              TO COMM-ACCNO.
           MOVE JA-ACC-TYPE           TO COMM-ACC-TYPE.
           COMPUTE COMM-INT-RATE = FUNCTION NUMVAL(JA-INT-RATE).
           MOVE JA-OPENED             TO COMM-OPENED.
           MOVE JA-OVERDRAFT          TO COMM-OVERDRAFT.
           MOVE JA-LAST-STMT          TO COMM-LAST-STMT-DT.
           MOVE JA-NEXT-STMT          TO COMM-NEXT-STMT-DT.
           COMPUTE COMM-AVAIL-BAL = FUNCTION NUMVAL(JA-AVAIL-BAL).
           COMPUTE COMM-ACTUAL-BAL = FUNCTION NUMVAL(JA-ACTUAL-BAL).

           MOVE 'Y' TO COMM-SUCCESS.

       UAD999.
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
       GMOOH010.

           EXEC CICS RETURN
           END-EXEC.

       GMOOH999.
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
