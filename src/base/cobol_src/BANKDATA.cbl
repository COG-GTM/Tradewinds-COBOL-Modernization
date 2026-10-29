      ******************************************************************
      *                                                                *
      *  Copyright IBM Corp. 2023                                      *
      *                                                                *
      ******************************************************************

      ******************************************************************

      ******************************************************************
      *                                                                *
      * Title: BANKDATA                                                *
      *                                                                *
      *                                                                *
      * Description: Batch program to initialise the data used in the  *
      *              bank application. Datastores populated by         *
      *              this program include CUSTOMER (VSAM) and ACCOUNT  *
      *              (account data service via ACCJSON file).          *
      *                                                                *
      *                                                                *
      * Input: parm='fffffff,ttttttt,ssssss,rrrrrr'                    *
      *        where fffffff is the key to start generating from       *
      *              ttttttt is the last key to generate               *
      *              sssssss is the step for generation                *
      *              rrrrrrr is the random event seed                  *
      *                                                                *
      * Output: The populated VSAM file CUSTOMER and the ACCJSON file  *
      *         (JSON array of ACCOUNT rows for the reset endpoint).   *
      *                                                                *
      *                                                                *
      *                                                                *
      ******************************************************************
       IDENTIFICATION DIVISION.
      *AUTHOR. JON COLLETT.
       PROGRAM-ID. BANKDATA.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
      * SOURCE-COMPUTER. MAINFRAME WITH DEBUGGING MODE.
       SOURCE-COMPUTER. MAINFRAME.
      *****************************************************************
      *** File Control                                              ***
      *****************************************************************
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT CUSTOMER-FILE
                  ASSIGN TO VSAM
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS RANDOM
                  RECORD KEY   IS CUSTOMER-KEY
                  FILE STATUS  IS CUSTOMER-VSAM-STATUS.
      *
      *    Generated ACCOUNT rows, as the JSON array body for
      *    POST /banking/data/admin/account/reset (see BANKDATA.jcl)
      *
           SELECT ACCOUNT-JSON-FILE
                  ASSIGN TO ACCJSON
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS  IS ACCJSON-STATUS.

       DATA DIVISION.
      *****************************************************************
      *** File Section                                              ***
      *****************************************************************
       FILE SECTION.

       FD  CUSTOMER-FILE.
       01  CUSTOMER-RECORD-STRUCTURE.
       COPY CUSTOMER.
       FD  ACCOUNT-JSON-FILE
           RECORDING MODE IS F.
       01  ACCOUNT-JSON-RECORD         PIC X(400).



      *****************************************************************
      *** Working storage                                           ***
      *****************************************************************
       WORKING-STORAGE SECTION.




       77  WS-ACCOUNT-OPENED-DAY PIC 99.
       77  WS-ACCOUNT-OPENED-MONTH PIC 99.
       77  WS-ACCOUNT-OPENED-YEAR PIC 9999.

       01 GEN-ACCOUNT-ROW.
           03 GEN-ACCOUNT-DATA.
              05 GEN-ACCOUNT-EYECATCHER         PIC X(4).
              05 GEN-ACCOUNT-CUST-NO            PIC X(10).
              05 GEN-ACCOUNT-KEY.
                 07 GEN-ACCOUNT-SORT-CODE       PIC X(6).
                 07 GEN-ACCOUNT-NUMBER          PIC X(8).
              05 GEN-ACCOUNT-TYPE               PIC X(8).
              05 GEN-ACCOUNT-INTEREST-RATE      PIC S9(4)V99 COMP-3.
              05 GEN-ACCOUNT-OPENED             PIC X(10).
              05 GEN-ACCOUNT-OPENED-GROUP REDEFINES GEN-ACCOUNT-OPENED.
                 07 GEN-ACCOUNT-OPENED-DAY      PIC XX.
                 07 GEN-ACCOUNT-OPENED-DELIM1   PIC X.
                 07 GEN-ACCOUNT-OPENED-MONTH    PIC XX.
                 07 GEN-ACCOUNT-OPENED-DELIM2   PIC X.
                 07 GEN-ACCOUNT-OPENED-YEAR     PIC X(4).
              05 GEN-ACCOUNT-OVERDRAFT-LIMIT    PIC S9(9) COMP.
              05 GEN-ACCOUNT-LAST-STMT-DATE     PIC X(10).
              05 GEN-ACCOUNT-LAST-STMT-GROUP
                 REDEFINES GEN-ACCOUNT-LAST-STMT-DATE.
                  07 GEN-ACCOUNT-LAST-STMT-DAY   PIC XX.
                  07 GEN-ACCOUNT-LAST-STMT-DELIM1 PIC X.
                  07 GEN-ACCOUNT-LAST-STMT-MONTH  PIC XX.
                  07 GEN-ACCOUNT-LAST-STMT-DELIM2 PIC X.
                  07 GEN-ACCOUNT-LAST-STMT-YEAR   PIC X(4).
              05 GEN-ACCOUNT-NEXT-STMT-DATE     PIC X(10).
              05 GEN-ACCOUNT-NEXT-STMT-GROUP
                 REDEFINES GEN-ACCOUNT-NEXT-STMT-DATE.
                 07 GEN-ACCOUNT-NEXT-STMT-DAY    PIC XX.
                 07 GEN-ACCOUNT-NEXT-STMT-DELIM1 PIC X.
                 07 GEN-ACCOUNT-NEXT-STMT-MONTH  PIC XX.
                 07 GEN-ACCOUNT-NEXT-STMT-DELIM2 PIC X.
                 07 GEN-ACCOUNT-NEXT-STMT-YEAR   PIC X(4).
              05 GEN-ACCOUNT-AVAILABLE-BALANCE  PIC S9(10)V99 COMP-3.
              05 GEN-ACCOUNT-ACTUAL-BALANCE     PIC S9(10)V99 COMP-3.




       01 DISP-REASON-CODE             PIC X(18).

       01  START-KEY                   PIC 9(10) DISPLAY.
       01  END-KEY                     PIC 9(10) DISPLAY.
       01  STEP-KEY                    PIC 9(10) DISPLAY.
       01  TESTNM                      PIC 9(4)  DISPLAY.

       01  CUSTOMER-VSAM-STATUS.
           05 VSAM-STATUS1             PIC X.
           05 VSAM-STATUS2             PIC X.

       01  NEXT-KEY                    PIC 9(10) DISPLAY.

       01  FORENAMES.
           05 FORENAME                 PIC X(20)
                                       OCCURS 100 TIMES
                                       DEPENDING ON FORENAMES-CNT.
       01  FORENAMES-CNT               PIC 9(8) BINARY   VALUE 0.
       01  FORENAMES-PTR               PIC 9(8) BINARY   VALUE 0.

       01  STREET-NAME-TREES.
           05 STREET-NAME-TREE         PIC X(20)
                                       OCCURS 26 TIMES
                                       DEPENDING ON STREET-NAME-T-CNT.
       01  STREET-NAME-T-CNT           PIC 9(8) BINARY   VALUE 0.
       01  STREET-NAME-T-PTR           PIC 9(8) BINARY   VALUE 0.

       01  STREET-NAME-ROADS.
           05 STREET-NAME-ROAD         PIC X(20)
                                       OCCURS 19 TIMES
                                       DEPENDING ON STREET-NAME-R-CNT.
       01  STREET-NAME-R-CNT           PIC 9(8) BINARY   VALUE 0.
       01  STREET-NAME-R-PTR           PIC 9(8) BINARY   VALUE 0.

       01  TOWNS.
           05 TOWN                     PIC X(20)
                                       OCCURS 50 TIMES
                                       DEPENDING ON TOWN-COUNT.
       01  TOWN-COUNT                  PIC 9(8) BINARY   VALUE 0.
       01  TOWN-PTR                    PIC 9(8) BINARY   VALUE 0.

       01  INITIALS.
           05 INITIAL-CHARACTER                  PIC X
                                       OCCURS 30 TIMES.
       01  INITIALS-CNT                PIC 9(8) BINARY   VALUE 0.
       01  INITIALS-PTR                PIC 9(8) BINARY   VALUE 0.

       01 TITLE-WORDS.
           03 TITLE-WORD OCCURS 36 PIC X(9).
       01 TITLE-ALPHABET.
           03 FILLER PIC X(9) VALUE 'Mr'.
           03 FILLER PIC X(9) VALUE 'Mrs'.
           03 FILLER PIC X(9) VALUE 'Miss'.
           03 FILLER PIC X(9) VALUE 'Ms'.
           03 FILLER PIC X(9) VALUE 'Mr'.
           03 FILLER PIC X(9) VALUE 'Mrs'.
           03 FILLER PIC X(9) VALUE 'Miss'.
           03 FILLER PIC X(9) VALUE 'Ms'.
           03 FILLER PIC X(9) VALUE 'Mr'.
           03 FILLER PIC X(9) VALUE 'Mrs'.
           03 FILLER PIC X(9) VALUE 'Miss'.
           03 FILLER PIC X(9) VALUE 'Ms'.
           03 FILLER PIC X(9) VALUE 'Mr'.
           03 FILLER PIC X(9) VALUE 'Mrs'.
           03 FILLER PIC X(9) VALUE 'Miss'.
           03 FILLER PIC X(9) VALUE 'Ms'.
           03 FILLER PIC X(9) VALUE 'Mr'.
           03 FILLER PIC X(9) VALUE 'Mrs'.
           03 FILLER PIC X(9) VALUE 'Miss'.
           03 FILLER PIC X(9) VALUE 'Ms'.
           03 FILLER PIC X(9) VALUE 'Dr'.
           03 FILLER PIC X(9) VALUE 'Drs'.
           03 FILLER PIC X(9) VALUE 'Dr'.
           03 FILLER PIC X(9) VALUE 'Ms'.
           03 FILLER PIC X(9) VALUE 'Dr'.
           03 FILLER PIC X(9) VALUE 'Ms'.
           03 FILLER PIC X(9) VALUE 'Dr'.
           03 FILLER PIC X(9) VALUE 'Ms'.
           03 FILLER PIC X(9) VALUE 'Professor'.
           03 FILLER PIC X(9) VALUE 'Professor'.
           03 FILLER PIC X(9) VALUE 'Professor'.
           03 FILLER PIC X(9) VALUE 'Lord'.
           03 FILLER PIC X(9) VALUE 'Sir'.
           03 FILLER PIC X(9) VALUE 'Sir'.
           03 FILLER PIC X(9) VALUE 'Lady'.
           03 FILLER PIC X(9) VALUE 'Lady'.


       01 TITLE-NUMBER PIC S9(9) BINARY.

       01 HOUSE-NUMBER PIC 99.

       01  SURNAMES.
           05 SURNAME                  PIC X(20)
                                       OCCURS 100 TIMES
                                       DEPENDING ON SURNAMES-CNT.
       01  SURNAMES-CNT                PIC 9(8) BINARY   VALUE 0.
       01  SURNAMES-PTR                PIC 9(8) BINARY   VALUE 0.

       01  RDATE                       PIC X(21).


       01  RDATE-NUMERIC               REDEFINES RDATE
                                       PIC 9(16) USAGE DISPLAY.


       01  RANDOM-SEED                 PIC X(16).
       01  RANDOM-SEED-NUMERIC REDEFINES RANDOM-SEED PIC 9(16)
            USAGE DISPLAY.


       01 WS-CNT                       PIC 9    VALUE 0.
       COPY SORTCODE.

       01 WS-ACCOUNT-NUMBER            PIC 9(8) VALUE 1.


       01 WS-ACCOUNT-TYPES.
           05 WS-ACCOUNT-TYPE          PIC X(8)
                                       OCCURS 5 TIMES
                                       DEPENDING ON
                                       ACCOUNT-TYPES-COUNT.
       01 ACCOUNT-TYPES-COUNT          PIC 9(8) BINARY  VALUE 0.
       01 ACCOUNT-TYPES-PTR            PIC 9(8) BINARY   VALUE 0.

       01 WS-ACCOUNT-INT-RATES.
           05 WS-ACCOUNT-INT-RATE      PIC 9(4)V99
                                       OCCURS 5 TIMES
                                       DEPENDING ON
                                       ACCOUNT-INT-RATES-COUNT.
       01 ACCOUNT-INT-RATES-COUNT      PIC 9(8) BINARY  VALUE 0.
       01 ACCOUNT-INT-RATES-PTR        PIC 9(8) BINARY   VALUE 0.


       01 WS-ACCOUNT-OVERDRAFT-LIMS.
           05 WS-ACCOUNT-OVERDRAFT-LIM PIC 9(8)
                                       OCCURS 5 TIMES
                                       DEPENDING ON
                                       ACCOUNT-OVERDRAFT-COUNT.
       01 ACCOUNT-OVERDRAFT-COUNT      PIC 9(8) BINARY  VALUE 0.
       01 ACCOUNT-OVERDRAFT-PTR        PIC 9(8) BINARY   VALUE 0.

       01 NO-OF-ACCOUNTS               PIC 9(8) BINARY   VALUE 0.

       01 WS-OVERDRAFT-CONVERSION      PIC S9(9) COMP VALUE 0.


       01 WS-PGM-NAME                             PIC X(8) VALUE ' '.



       01 OPENED-DATE-ATTEMPTS           PIC S9(8) DISPLAY
           SIGN LEADING SEPARATE.

       01 OPENED-DATE-VALID              PIC X VALUE 'N'.



       01 COMMIT-COUNT PIC S9(8) BINARY.

       01 ACCJSON-STATUS                 PIC XX VALUE '00'.
       01 ACCJSON-FIRST                  PIC X  VALUE 'Y'.
       01 ACCJSON-EDIT-RATE              PIC Z(3)9.99.
       01 ACCJSON-EDIT-OVERDRAFT         PIC Z(8)9.
       01 ACCJSON-EDIT-AVAIL-BAL         PIC -(10)9.99.
       01 ACCJSON-EDIT-ACTUAL-BAL        PIC -(10)9.99.
       01 ACCJSON-PTR                    PIC S9(8) COMP.

       01 time-data.
           05 tod-clock                PIC X(20).
           05 period-start             PIC X(14).
           05 period-next              COMP-2.
           05 period-next-f            PIC 9(14).
           05 gmt-lilian               PIC S9(8) BINARY.
           05 gmt-seconds              COMP-2.
           05 delay-interval           PIC S9(9) BINARY VALUE 1.
           05 fc                       PIC X(12).
           05 datm-conv                PIC X(80).
           05 datm-picture.
              10 datm-length           PIC 9999 COMP.
              10 datm-format           PIC X(14).

       01 TIMESTAMP-FUNCTION PIC X(40).


       01 WS-CURRENT-DATE-DATA.
           03 WS-CURRENT-DATE.
              05 WS-CURRENT-YYYY         PIC 9(4).
              05 WS-CURRENT-MM           PIC 99.
              05 WS-CURRENT-DD           PIC 99.
           03 WS-CURRENT-TIME.
              05 WS-CURRENT-HOURS        PIC 99.
              05 WS-CURRENT-MINS         PIC 99.
              05 WS-CURRENT-SECS         PIC 99.
              05 WS-CURRENT-MILLI        PIC 99.
           03 WS-DIFFERENCE-FROM-GMT     PIC S9(4).

       01 WS-CURRENT-DATE-9              PIC 9(8) VALUE 0.
       01 WS-TODAY-INT                   PIC 9(8) VALUE 0.
       01 WS-REVIEW-DATE-ADD             PIC 99 VALUE 0.
       01 WS-NEW-REVIEW-DATE-INT         PIC 9(8) VALUE 0.
       01 WS-NEW-REVIEW-YYYYMMDD         PIC 9(8) VALUE 0.

       01 CUSTOMER-CONTROL.
       COPY CUSTCTRL.

       01 ACCOUNT-CONTROL.
       COPY ACCTCTRL.

      * Get the CONTROL table


       01 WS-WEEKDAYS                    PIC X(63) VALUE ' '.
       01 WS-WEEKDAY-GROUP
           REDEFINES WS-WEEKDAYS.
           03 WS-WEEKDAY                 PIC X(9) OCCURS 7 TIMES.
       01 WS-DT                          PIC 9(8) VALUE 0.
       01 WS-DT-REM                      PIC S9(9) COMP VALUE 0.
       01 WS-DAY-OF-WEEK-VAL             PIC 9    VALUE 0.
       01 WS-DAY-TODAY                   PIC X(9) VALUE ' '.


      *****************************************************************
      *** Linkage Storage                                           ***
      *****************************************************************
       LINKAGE SECTION.

       01 PARM-BUFFER.
           05 PARM-LENGTH              PIC 9(4) BINARY.
           05 PARM                     PIC X(256).


      *****************************************************************
      *** Main Processing                                           ***
      *****************************************************************
       PROCEDURE DIVISION USING PARM-BUFFER.
       PREMIERE SECTION.
       A010.
           MOVE 'Starting BANKDATA'
             to TIMESTAMP-FUNCTION
           perform TIMESTAMP

      *
      * Initialise the arrays
      *
      D    DISPLAY 'About to initialise arrays'.
           PERFORM INITIALISE-ARRAYS.


      *
      * Get the parameters
      *
           UNSTRING PARM(1:PARM-LENGTH)
                    DELIMITED BY SPACE OR ','
                    INTO START-KEY
                         END-KEY
                         STEP-KEY
                         RANDOM-SEED.


      D    DISPLAY 'INPUT PARMS ARE: START-KEY=' START-KEY
      D             ' END-KEY=' END-KEY ' STEP-KEY=' STEP-KEY
      D             ' RANDOM-SEED=' RANDOM-SEED '

           IF END-KEY < START-KEY
             MOVE 12 TO RETURN-CODE
             DISPLAY 'Final customer number cannot be smaller than '
               'first customer number'
             GOBACK
           END-IF
           IF step-key = zero
             MOVE 12 TO RETURN-CODE
             DISPLAY 'Gap between customers cannot be zero'
             GOBACK
           END-IF

      *
      * Get today's date and store it as an INTEGER
      *
           PERFORM GET-TODAYS-DATE.

      *
      * Start the ACCJSON reset file for this SortCode
      *
      D    DISPLAY 'About to open ACCJSON'.

           PERFORM OPEN-ACCOUNT-JSON.
      D    DISPLAY 'Back from open ACCJSON'.
      *
      * Initialise the random seed
      *
      *    MOVE FUNCTION CURRENT-DATE TO RDATE.


      D    DISPLAY 'RANDOM SEED IS ' RANDOM-SEED-NUMERIC
           COMPUTE FORENAMES-PTR = FORENAMES-CNT *
                                   FUNCTION RANDOM(RANDOM-SEED-NUMERIC).
      D    DISPLAY 'FORENAMES-PTR IS ' FORENAMES-PTR.
           COMPUTE INITIALS-PTR =  INITIALS-CNT *
                                   FUNCTION RANDOM.
      D    DISPLAY 'INITIALS-PTR IS ' INITIALS-PTR
           COMPUTE SURNAMES-PTR  =  SURNAMES-CNT *
                                   FUNCTION RANDOM.
           COMPUTE TOWN-PTR  =  TOWN-COUNT *
                                   FUNCTION RANDOM.
           COMPUTE STREET-NAME-R-PTR =  STREET-NAME-R-CNT *
                                   FUNCTION RANDOM.
           COMPUTE STREET-NAME-T-PTR =  STREET-NAME-T-CNT *
                                   FUNCTION RANDOM.
           COMPUTE ACCOUNT-TYPES-PTR = ACCOUNT-TYPES-COUNT *
                                   FUNCTION RANDOM.

      *
      * Open the files
      *

           OPEN OUTPUT CUSTOMER-FILE.
           IF CUSTOMER-VSAM-STATUS NOT EQUAL '00' THEN
               DISPLAY 'Error opening CUSTOMER file, status='
                       CUSTOMER-VSAM-STATUS
               MOVE 12 TO RETURN-CODE
               PERFORM PROGRAM-DONE
           END-IF.


      *
      * Populate the CUSTOMER file
      *
           MOVE 'Populating Customer + Account files'
             to TIMESTAMP-FUNCTION
           perform TIMESTAMP
           MOVE ZERO TO COMMIT-COUNT
           MOVE ZERO TO LAST-CUSTOMER-NUMBER NUMBER-OF-CUSTOMERS
           MOVE ZERO TO LAST-ACCOUNT-NUMBER NUMBER-OF-ACCOUNTS
           PERFORM TEST BEFORE
                   VARYING NEXT-KEY FROM START-KEY BY STEP-KEY
                     UNTIL NEXT-KEY > END-KEY

               INITIALIZE CUSTOMER-RECORD IN CUSTOMER-RECORD-STRUCTURE

               SET CUSTOMER-EYECATCHER-VALUE TO TRUE

               MOVE NEXT-KEY TO CUSTOMER-NUMBER
               MOVE NEXT-KEY TO LAST-CUSTOMER-NUMBER
               ADD 1 TO NUMBER-OF-CUSTOMERS GIVING NUMBER-OF-CUSTOMERS

      D        DISPLAY 'Generating data for customer ' customer-number

               COMPUTE TITLE-NUMBER = ((36 - 1)
                                        * FUNCTION RANDOM) + 1
               COMPUTE FORENAMES-PTR = ((FORENAMES-CNT - 1)
                                        * FUNCTION RANDOM) + 1
               COMPUTE INITIALS-PTR  = ((INITIALS-CNT - 1)
                                        * FUNCTION RANDOM) + 1
               COMPUTE SURNAMES-PTR  = ((SURNAMES-CNT - 1)
                                        * FUNCTION RANDOM) + 1
               COMPUTE HOUSE-NUMBER  = (99
                                        * FUNCTION RANDOM) + 1
               COMPUTE STREET-NAME-T-PTR  = ((STREET-NAME-T-CNT - 1)
                                        * FUNCTION RANDOM) + 1
               COMPUTE STREET-NAME-R-PTR  = ((STREET-NAME-R-CNT - 1)
                                        * FUNCTION RANDOM) + 1
               COMPUTE TOWN-PTR           = ((TOWN-COUNT - 1)
                                        * FUNCTION RANDOM) + 1

               MOVE SPACES TO CUSTOMER-NAME

               STRING TITLE-WORD(TITLE-NUMBER) DELIMITED BY SPACE
               ' '  DELIMITED BY SIZE
               FORENAME(FORENAMES-PTR) DELIMITED BY SPACE
               ' '  DELIMITED BY SIZE
               INITIAL-CHARACTER(INITIALS-PTR) DELIMITED BY SPACE
               ' '  DELIMITED BY SIZE
               SURNAME(SURNAMES-PTR) DELIMITED BY SIZE
               INTO CUSTOMER-NAME

               MOVE SPACES TO CUSTOMER-ADDRESS
               STRING
               HOUSE-NUMBER DELIMITED BY SIZE
               ' ' DELIMITED BY SIZE
               STREET-NAME-TREE(STREET-NAME-T-PTR)
               DELIMITED BY SPACE
               ' ' DELIMITED BY SIZE
               STREET-NAME-ROAD(STREET-NAME-R-PTR)
               DELIMITED BY SPACE
               ', ' DELIMITED BY SIZE
               TOWN(TOWN-PTR)
               DELIMITED BY SPACE
               INTO CUSTOMER-ADDRESS

               COMPUTE CUSTOMER-BIRTH-DAY =  ((28 - 1)
                                        * FUNCTION RANDOM) + 1
               COMPUTE CUSTOMER-BIRTH-MONTH =  ((12 - 1)
                                        * FUNCTION RANDOM) + 1
               COMPUTE CUSTOMER-BIRTH-YEAR =  ((2000 - 1900)
                                        * FUNCTION RANDOM) + 1900

               MOVE SORTCODE TO
                  CUSTOMER-SORTCODE


               COMPUTE CUSTOMER-CREDIT-SCORE = ((999 - 1)
                                        * FUNCTION RANDOM) + 1

      *
      *        Generate the random credit score review date. This
      *        should be a date between 1 and 21 days from today
      *

               COMPUTE WS-REVIEW-DATE-ADD = ((21 - 1)
                                        * FUNCTION RANDOM) + 1

               COMPUTE WS-NEW-REVIEW-DATE-INT =
                  WS-TODAY-INT + WS-REVIEW-DATE-ADD

      *
      *        Convert the integer date back to YYYYMMDD format
      *        and store on the CUSTOMER record
      *
               COMPUTE WS-NEW-REVIEW-YYYYMMDD = FUNCTION
                  DATE-OF-INTEGER (WS-NEW-REVIEW-DATE-INT)

               MOVE WS-NEW-REVIEW-YYYYMMDD(1:4) TO
                  CUSTOMER-CS-REVIEW-YEAR
               MOVE WS-NEW-REVIEW-YYYYMMDD(5:2) TO
                  CUSTOMER-CS-REVIEW-MONTH
               MOVE WS-NEW-REVIEW-YYYYMMDD(7:2) TO
                  CUSTOMER-CS-REVIEW-DAY

               WRITE CUSTOMER-RECORD-STRUCTURE

               IF CUSTOMER-VSAM-STATUS NOT EQUAL '00' THEN
                   DISPLAY 'Error writing to VSAM file, status='
                           CUSTOMER-VSAM-STATUS
                   MOVE 12 TO RETURN-CODE
                   PERFORM PROGRAM-DONE
               END-IF
      *
      * Having written out to the CUSTOMER datastore we now need to
      * use some of this information to populate related data on
      * on the ACCOUNT datastore.
      *

               PERFORM DEFINE-ACC

               ADD 1 TO COMMIT-COUNT GIVING COMMIT-COUNT
               IF COMMIT-COUNT > 1000
      D          DISPLAY 'Commit every 1,000 records or so'
                 MOVE ZERO TO COMMIT-COUNT
               END-IF
           END-PERFORM

           MOVE '000000' TO CUSTOMER-CONTROL-SORTCODE
           MOVE '9999999999' TO CUSTOMER-CONTROL-NUMBER
           SET CUSTOMER-CONTROL-EYECATCHER-V TO TRUE
      D    DISPLAY 'ABOUT TO WRITE CUSTOMER-CONTROL-RECORD'
           MOVE CUSTOMER-CONTROL-RECORD
             TO CUSTOMER-RECORD IN CUSTOMER-RECORD-STRUCTURE
           WRITE CUSTOMER-RECORD-STRUCTURE
           IF CUSTOMER-VSAM-STATUS NOT EQUAL '00' THEN
                   DISPLAY 'Error writing CUSTOMER-CONTROL-RECORD file'
                   ', status=' CUSTOMER-VSAM-STATUS
                   MOVE 12 TO RETURN-CODE
                   PERFORM PROGRAM-DONE
           END-IF.
      * CONTROL values come from the reset
      * <<sortcode>>-ACCOUNT-LAST
      * <<sortcode>>-ACCOUNT-COUNT


      *
      *    Close the JSON array. The reset endpoint writes CONTROL
      *    <sortcode>-ACCOUNT-LAST (highest account number in the
      *    array) and <sortcode>-ACCOUNT-COUNT (array size), which
      *    equal LAST-ACCOUNT-NUMBER and NUMBER-OF-ACCOUNTS here.
      *
           PERFORM CLOSE-ACCOUNT-JSON.
           CLOSE CUSTOMER-FILE.

           MOVE 'Finishing BANKDATA'
             to TIMESTAMP-FUNCTION
           perform TIMESTAMP.

       A999.
           EXIT.

      *
      * Finish
      *
       PROGRAM-DONE SECTION.
       PD010.

           GOBACK.
       PD999.
           EXIT.

      *
      * Define and Populate the ACCOUNT datastore
      *
       DEFINE-ACC SECTION.
       DA010.
      D    DISPLAY 'IN DEFINE-ACC SECTION'.

      *
      * Decide how many accounts this customer will have. To allow
      * for growth make it between 1 and 5 (max is 10 at the moment
      * this maximum is dictated by the alternate key index which is
      * currently set to 10).
      *
           COMPUTE NO-OF-ACCOUNTS =  ((5 - 1)
                                        * FUNCTION RANDOM) + 1
      D    DISPLAY 'Creating accounts ' NO-OF-ACCOUNTS
           PERFORM POPULATE-ACC VARYING WS-CNT FROM 1 BY 1
               UNTIL WS-CNT > NO-OF-ACCOUNTS.

       DA999.
           EXIT.

      *
      * Populate account/s records and write to VSAM ACCOUNT datastore
      *
       POPULATE-ACC SECTION.
       PA010.

      *
      * Generate the account open date but it MUST be after
      * the DOB (otherwise that would just be silly)
      *
           MOVE 0 TO GEN-ACCOUNT-OPENED-DAY
                     GEN-ACCOUNT-OPENED-MONTH
                     GEN-ACCOUNT-OPENED-YEAR
           MOVE 0 TO OPENED-DATE-ATTEMPTS
      D    DISPLAY 'About to generate opened date'
           MOVE 'N' TO OPENED-DATE-VALID
           PERFORM GENERATE-OPENED-DATE
                   UNTIL OPENED-DATE-VALID = 'Y'.
      D    DISPLAY 'Back from generate opened date after '
      D    OPENED-DATE-ATTEMPTS ' goes'



      *
      * Now insert the ACCOUNT record into the ACCOUNT table.
      *

           MOVE 'ACCT' TO
              GEN-ACCOUNT-EYECATCHER OF GEN-ACCOUNT-ROW.
           MOVE CUSTOMER-NUMBER TO
              GEN-ACCOUNT-CUST-NO OF GEN-ACCOUNT-ROW.
           MOVE SORTCODE TO
              GEN-ACCOUNT-SORT-CODE OF GEN-ACCOUNT-ROW.
           MOVE WS-ACCOUNT-NUMBER TO
              GEN-ACCOUNT-NUMBER OF GEN-ACCOUNT-ROW.

           MOVE WS-ACCOUNT-NUMBER TO LAST-ACCOUNT-NUMBER

      *    ADD 1 TO NUMBER-OF-ACCOUNTS GIVING NUMBER-OF-ACCOUNTS

      *    ADD 1 TO WS-ACCOUNT-NUMBER GIVING WS-ACCOUNT-NUMBER

           MOVE WS-ACCOUNT-TYPE(WS-CNT) TO
              GEN-ACCOUNT-TYPE OF GEN-ACCOUNT-ROW.
           MOVE WS-ACCOUNT-INT-RATE(WS-CNT) TO
              GEN-ACCOUNT-INTEREST-RATE OF GEN-ACCOUNT-ROW.
           MOVE '.' TO GEN-ACCOUNT-OPENED-DELIM1.
           MOVE '.' TO GEN-ACCOUNT-OPENED-DELIM2.
      *
      * Convert the overdraft into a field compatible with INTEGER on
      * the table
      *
           MOVE WS-ACCOUNT-OVERDRAFT-LIM(WS-CNT) TO
              WS-OVERDRAFT-CONVERSION.
           MOVE WS-OVERDRAFT-CONVERSION TO
              GEN-ACCOUNT-OVERDRAFT-LIMIT OF GEN-ACCOUNT-ROW.
           MOVE '2021' TO
              GEN-ACCOUNT-LAST-STMT-YEAR OF GEN-ACCOUNT-ROW.
           MOVE '.' TO GEN-ACCOUNT-LAST-STMT-DELIM1.
           MOVE '07' TO
              GEN-ACCOUNT-LAST-STMT-MONTH OF GEN-ACCOUNT-ROW.
           MOVE '.' TO GEN-ACCOUNT-LAST-STMT-DELIM2.
           MOVE '01' TO
              GEN-ACCOUNT-LAST-STMT-DAY OF GEN-ACCOUNT-ROW.
           MOVE '2021' TO
              GEN-ACCOUNT-NEXT-STMT-YEAR OF GEN-ACCOUNT-ROW.
           MOVE '.' TO GEN-ACCOUNT-NEXT-STMT-DELIM1.
           MOVE '08' TO
              GEN-ACCOUNT-NEXT-STMT-MONTH OF GEN-ACCOUNT-ROW.
           MOVE '.' TO GEN-ACCOUNT-NEXT-STMT-DELIM2.
           MOVE '01' TO
              GEN-ACCOUNT-NEXT-STMT-DAY OF GEN-ACCOUNT-ROW.
           COMPUTE GEN-ACCOUNT-AVAILABLE-BALANCE
                  = ((999999 - 1)
                                   * FUNCTION RANDOM) + 1.
           MOVE GEN-ACCOUNT-AVAILABLE-BALANCE  TO
              GEN-ACCOUNT-ACTUAL-BALANCE.


      *
      *    If it is a LOAN or a MORTGAGE then it should really have
      *    a negative balance (it doesn't make any sense for these
      *    accounts to have a positive balance).
      *
           IF GEN-ACCOUNT-TYPE OF GEN-ACCOUNT-ROW = 'LOAN    ' OR
           GEN-ACCOUNT-TYPE OF GEN-ACCOUNT-ROW = 'MORTGAGE'
                COMPUTE GEN-ACCOUNT-ACTUAL-BALANCE =
                   0 - GEN-ACCOUNT-ACTUAL-BALANCE OF GEN-ACCOUNT-ROW

                COMPUTE GEN-ACCOUNT-AVAILABLE-BALANCE
                   OF GEN-ACCOUNT-ROW =
                   0 - GEN-ACCOUNT-AVAILABLE-BALANCE OF GEN-ACCOUNT-ROW

           END-IF.

           PERFORM WRITE-ACCOUNT-JSON.
           MOVE WS-ACCOUNT-NUMBER TO LAST-ACCOUNT-NUMBER
           ADD 1 TO NUMBER-OF-ACCOUNTS GIVING NUMBER-OF-ACCOUNTS
           ADD 1 TO WS-ACCOUNT-NUMBER.

       PA999.
           EXIT.

      *
      * Generate the account opened date
      *
       GENERATE-OPENED-DATE SECTION.
       GOD010.

           COMPUTE WS-ACCOUNT-OPENED-DAY
                  =  ((28 - 1)
                                    * FUNCTION RANDOM) + 1.
           MOVE WS-ACCOUNT-OPENED-DAY TO GEN-ACCOUNT-OPENED-DAY.
           COMPUTE WS-ACCOUNT-OPENED-MONTH
                  =  ((12 - 1)
                                    * FUNCTION RANDOM) + 1.
           MOVE WS-ACCOUNT-OPENED-MONTH TO GEN-ACCOUNT-OPENED-MONTH.
           COMPUTE WS-ACCOUNT-OPENED-YEAR
                  =  ((2014 -
                                    CUSTOMER-BIRTH-YEAR)
                                    * FUNCTION RANDOM) +
                                    CUSTOMER-BIRTH-YEAR.
           MOVE WS-ACCOUNT-OPENED-YEAR TO GEN-ACCOUNT-OPENED-YEAR.
      D    DISPLAY 'DATE OF BIRTH IS '
      D             CUSTOMER-BIRTH-DAY
      D             '/'
      D             CUSTOMER-BIRTH-MONTH
      D             '/'
      D             CUSTOMER-BIRTH-YEAR

      D    DISPLAY 'ACCOUNT OPENED  '
      D             GEN-ACCOUNT-OPENED-DAY
      D             '/'
      D             GEN-ACCOUNT-OPENED-MONTH
      D             '/'
      D             GEN-ACCOUNT-OPENED-YEAR

           ADD 1 TO OPENED-DATE-ATTEMPTS GIVING OPENED-DATE-ATTEMPTS.
           IF GEN-ACCOUNT-OPENED-YEAR
              > CUSTOMER-BIRTH-YEAR
             MOVE 'Y' TO OPENED-DATE-VALID
             GO TO GOD999
           END-IF.
           IF OPENED-DATE-ATTEMPTS > 100
             MOVE 'Y' TO OPENED-DATE-VALID
             MOVE CUSTOMER-BIRTH-DAY   TO GEN-ACCOUNT-OPENED-DAY
             MOVE CUSTOMER-BIRTH-MONTH TO GEN-ACCOUNT-OPENED-MONTH
             MOVE CUSTOMER-BIRTH-YEAR  TO GEN-ACCOUNT-OPENED-YEAR
           END-IF.
       GOD999.
           EXIT.


      *****************************************************************
      *** Initialise Arrays                                         ***
      *****************************************************************
       INITIALISE-ARRAYS SECTION.
       IA010.

           MOVE TITLE-ALPHABET TO TITLE-WORDS.

           MOVE 50 TO FORENAMES-CNT.

           MOVE 'Michael' TO FORENAME(01).
           MOVE 'Will'    TO FORENAME(02).
           MOVE 'Geoff'   TO FORENAME(03).
           MOVE 'Chris'   TO FORENAME(04).
           MOVE 'Dave'    TO FORENAME(05).
           MOVE 'Luke'    TO FORENAME(06).
           MOVE 'Adam'    TO FORENAME(07).
           MOVE 'Giuseppe' TO FORENAME(08).
           MOVE 'James'   TO FORENAME(09).
           MOVE 'Jon'     TO FORENAME(10).
           MOVE 'Andy'    TO FORENAME(11).
           MOVE 'Lou'     TO FORENAME(12).
           MOVE 'Robert'  TO FORENAME(13).
           MOVE 'Sam'     TO FORENAME(14).
           MOVE 'Frederick' TO FORENAME(15).
           MOVE 'Buford'  TO FORENAME(16).
           MOVE 'William' TO FORENAME(17).
           MOVE 'Howard'  TO FORENAME(18).
           MOVE 'Anthony' TO FORENAME(19).
           MOVE 'Bruce'   TO FORENAME(20).
           MOVE 'Peter'   TO FORENAME(21).
           MOVE 'Stephen' TO FORENAME(22).
           MOVE 'Donald'  TO FORENAME(23).
           MOVE 'Dennis'  TO FORENAME(24).
           MOVE 'Harold'  TO FORENAME(25).
           MOVE 'Amy'     TO FORENAME(26).
           MOVE 'Belinda' TO FORENAME(27).
           MOVE 'Charlotte' TO FORENAME(28).
           MOVE 'Donna' TO FORENAME(29).
           MOVE 'Felicia' TO FORENAME(30).
           MOVE 'Gretchen' TO FORENAME(31).
           MOVE 'Henrietta' TO FORENAME(32).
           MOVE 'Imogen' TO FORENAME(33).
           MOVE 'Josephine' TO FORENAME(34).
           MOVE 'Kimberley' TO FORENAME(35).
           MOVE 'Lucy' TO FORENAME(36).
           MOVE 'Monica' TO FORENAME(37).
           MOVE 'Natalie' TO FORENAME(38).
           MOVE 'Ophelia' TO FORENAME(39).
           MOVE 'Patricia' TO FORENAME(40).
           MOVE 'Querida' TO FORENAME(41).
           MOVE 'Rachel' TO FORENAME(42).
           MOVE 'Samantha' TO FORENAME(43).
           MOVE 'Tanya'   TO FORENAME(44).
           MOVE 'Ulrika'  TO FORENAME(45).
           MOVE 'Virginia' TO FORENAME(46).
           MOVE 'Wendy'   TO FORENAME(47).
           MOVE 'Xaviera' TO FORENAME(48).
           MOVE 'Yvonne'  TO FORENAME(49).
           MOVE 'Zsa Zsa' TO FORENAME(50).

           MOVE 30 TO INITIALS-CNT.

           MOVE 'ABCDEFGHIJLKMNOPQRSTUVWXYZ    ' TO INITIALS.

           MOVE 50 TO SURNAMES-CNT.

           MOVE 'Jones'        TO SURNAME(01).
           MOVE 'Davidson'     TO SURNAME(02).
           MOVE 'Baker'        TO SURNAME(03).
           MOVE 'Smith'        TO SURNAME(04).
           MOVE 'Taylor'       TO SURNAME(05).
           MOVE 'Evans'        TO SURNAME(06).
           MOVE 'Roberts'      TO SURNAME(07).
           MOVE 'Wright'       TO SURNAME(08).
           MOVE 'Walker'       TO SURNAME(09).
           MOVE 'Green'        TO SURNAME(10).
           MOVE 'Price'        TO SURNAME(11).
           MOVE 'Downton'      TO SURNAME(12).
           MOVE 'Gatting'      TO SURNAME(13).
           MOVE 'Robinson'     TO SURNAME(14).
           MOVE 'Justice'      TO SURNAME(15).
           MOVE 'Tell'         TO SURNAME(16).
           MOVE 'Stark'        TO SURNAME(17).
           MOVE 'Strange'      TO SURNAME(18).
           MOVE 'Parker'       TO SURNAME(19).
           MOVE 'Blake'        TO SURNAME(20).
           MOVE 'Jackson'      TO SURNAME(21).
           MOVE 'Groves'       TO SURNAME(22).
           MOVE 'Palmer'       TO SURNAME(23).
           MOVE 'Ramsbottom'   TO SURNAME(24).
           MOVE 'Lloyd'        TO SURNAME(24).
           MOVE 'Hughes'       TO SURNAME(25).
           MOVE 'Briggs'       TO SURNAME(26).
           MOVE 'Higins'       TO SURNAME(27).
           MOVE 'Goodwin'      TO SURNAME(28).
           MOVE 'Valmont'      TO SURNAME(29).
           MOVE 'Brown'        TO SURNAME(30).
           MOVE 'Hopkins'      TO SURNAME(31).
           MOVE 'Bonney'       TO SURNAME(32).
           MOVE 'Jenkins'      TO SURNAME(33).
           MOVE 'Lloyd'        TO SURNAME(34).
           MOVE 'Wilmore'      TO SURNAME(35).
           MOVE 'Franklin'     TO SURNAME(36).
           MOVE 'Renton'       TO SURNAME(37).
           MOVE 'Seward'       TO SURNAME(38).
           MOVE 'Morris'       TO SURNAME(39).
           MOVE 'Johnson'      TO SURNAME(40).
           MOVE 'Brennan'      TO SURNAME(41).
           MOVE 'Thomson'      TO SURNAME(42).
           MOVE 'Barker'       TO SURNAME(43).
           MOVE 'Corbett'      TO SURNAME(44).
           MOVE 'Weber'        TO SURNAME(45).
           MOVE 'Leigh'        TO SURNAME(46).
           MOVE 'Croft'        TO SURNAME(47).
           MOVE 'Walken'       TO SURNAME(48).
           MOVE 'Dubois'       TO SURNAME(49).
           MOVE 'Stephens'     TO SURNAME(50).

           MOVE 26 TO STREET-NAME-T-CNT.
           MOVE 19 TO STREET-NAME-R-CNT.

           MOVE 'Acacia' to    STREET-NAME-TREE(1).
           MOVE 'Birch' to     STREET-NAME-TREE(2).
           MOVE 'Cypress' to   STREET-NAME-TREE(3).
           MOVE 'Douglas' to   STREET-NAME-TREE(4).
           MOVE 'Elm' to       STREET-NAME-TREE(5).
           MOVE 'Fir' to       STREET-NAME-TREE(6).
           MOVE 'Gorse' to     STREET-NAME-TREE(7).
           MOVE 'Holly' to     STREET-NAME-TREE(8).
           MOVE 'Ironwood' to  STREET-NAME-TREE(9).
           MOVE 'Joshua' to    STREET-NAME-TREE(10).
           MOVE 'Kapok' to     STREET-NAME-TREE(11).
           MOVE 'Laburnam' to  STREET-NAME-TREE(12).
           MOVE 'Maple' to     STREET-NAME-TREE(13).
           MOVE 'Nutmeg' to    STREET-NAME-TREE(14).
           MOVE 'Oak' to       STREET-NAME-TREE(15).
           MOVE 'Pine' to      STREET-NAME-TREE(16).
           MOVE 'Quercine' to  STREET-NAME-TREE(17).
           MOVE 'Rowan' to     STREET-NAME-TREE(18).
           MOVE 'Sycamore' to  STREET-NAME-TREE(19).
           MOVE 'Thorn' to     STREET-NAME-TREE(20).
           MOVE 'Ulmus' to     STREET-NAME-TREE(21).
           MOVE 'Viburnum' to  STREET-NAME-TREE(22).
           MOVE 'Willow' to    STREET-NAME-TREE(23).
           MOVE 'Xylophone' to STREET-NAME-TREE(24).
           MOVE 'Yew' to       STREET-NAME-TREE(25).
           MOVE 'Zebratree' to STREET-NAME-TREE(26).


           MOVE 'Avenue' to    STREET-NAME-ROAD(1).
           MOVE 'Boulevard' to STREET-NAME-ROAD(2).
           MOVE 'Close' to     STREET-NAME-ROAD(3).
           MOVE 'Crescent' to  STREET-NAME-ROAD(4).
           MOVE 'Drive' to     STREET-NAME-ROAD(5).
           MOVE 'Escalade' to  STREET-NAME-ROAD(6).
           MOVE 'Frontage' to  STREET-NAME-ROAD(7).
           MOVE 'Lane' to      STREET-NAME-ROAD(8).
           MOVE 'Mews' to      STREET-NAME-ROAD(9).
           MOVE 'Rise' to      STREET-NAME-ROAD(10).
           MOVE 'Court' to     STREET-NAME-ROAD(11).
           MOVE 'Opening' to   STREET-NAME-ROAD(12).
           MOVE 'Loke' to      STREET-NAME-ROAD(13).
           MOVE 'Square' to    STREET-NAME-ROAD(14).
           MOVE 'Houses' to    STREET-NAME-ROAD(15).
           MOVE 'Gate' to      STREET-NAME-ROAD(16).
           MOVE 'Street' to    STREET-NAME-ROAD(17).
           MOVE 'Grove' to     STREET-NAME-ROAD(18).
           MOVE 'March' to     STREET-NAME-ROAD(19).

           MOVE 50 TO TOWN-COUNT.

           MOVE 'Norwich'      TO TOWN(01).
           MOVE 'Acle   '      TO TOWN(02).
           MOVE 'Aylsham'      TO TOWN(03).
           MOVE 'Wymondham'    TO TOWN(04).
           MOVE 'Attleborough' TO TOWN(05).
           MOVE 'Cromer '      TO TOWN(06).
           MOVE 'Cambridge'    TO TOWN(07).
           MOVE 'Peterborough' TO TOWN(08).
           MOVE 'Weobley'      TO TOWN(09).
           MOVE 'Wembley'      TO TOWN(10).
           MOVE 'Hereford'     TO TOWN(11).
           MOVE 'Ross-on-Wye'  TO TOWN(12).
           MOVE 'Hay-on-Wye'   TO TOWN(13).
           MOVE 'Nottingham'   TO TOWN(14).
           MOVE 'Northampton'  TO TOWN(15).
           MOVE 'Nuneaton'     TO TOWN(16).
           MOVE 'Oxford'       TO TOWN(17).
           MOVE 'Oswestry'     TO TOWN(18).
           MOVE 'Ormskirk'     TO TOWN(19).
           MOVE 'Royston'      TO TOWN(20).
           MOVE 'Chilcomb'     TO TOWN(21).
           MOVE 'Winchester'   TO TOWN(22).
           MOVE 'Wrexham'      TO TOWN(23).
           MOVE 'Crewe  '      TO TOWN(24).
           MOVE 'Plymouth'     TO TOWN(25).
           MOVE 'Portsmouth'   TO TOWN(26).
           MOVE 'Forfar '      TO TOWN(27).
           MOVE 'Fife   '      TO TOWN(28).
           MOVE 'Aberdeen'     TO TOWN(29).
           MOVE 'Glasgow'      TO TOWN(30).
           MOVE 'Birmingham'   TO TOWN(31).
           MOVE 'Bolton'       TO TOWN(32).
           MOVE 'Whitby '      TO TOWN(33).
           MOVE 'Manchester'   TO TOWN(34).
           MOVE 'Chester '     TO TOWN(35).
           MOVE 'Leicester'    TO TOWN(36).
           MOVE 'Lowestoft'    TO TOWN(37).
           MOVE 'Ipswich'      TO TOWN(38).
           MOVE 'Colchester'   TO TOWN(39).
           MOVE 'Dover  '      TO TOWN(40).
           MOVE 'Brighton'     TO TOWN(41).
           MOVE 'Salisbury'    TO TOWN(42).
           MOVE 'Bristol'      TO TOWN(43).
           MOVE 'Bath   '      TO TOWN(44).
           MOVE 'Gloucester'   TO TOWN(45).
           MOVE 'Cheltenham'   TO TOWN(46).
           MOVE 'Durham '      TO TOWN(47).
           MOVE 'Carlisle'     TO TOWN(48).
           MOVE 'York   '      TO TOWN(49).
           MOVE 'Exeter '      TO TOWN(50).


           MOVE 5 TO ACCOUNT-TYPES-COUNT.

           MOVE 'ISA     ' TO WS-ACCOUNT-TYPE(1).
           MOVE 'SAVING  ' TO WS-ACCOUNT-TYPE(2).
           MOVE 'CURRENT ' TO WS-ACCOUNT-TYPE(3).
           MOVE 'LOAN    ' TO WS-ACCOUNT-TYPE(4).
           MOVE 'MORTGAGE' TO WS-ACCOUNT-TYPE(5).


           MOVE 5 TO ACCOUNT-INT-RATES-COUNT.
           MOVE 2.10       TO WS-ACCOUNT-INT-RATE(1).
           MOVE 1.75       TO WS-ACCOUNT-INT-RATE(2).
           MOVE 000000     TO WS-ACCOUNT-INT-RATE(3).
           MOVE 17.90      TO WS-ACCOUNT-INT-RATE(4).
           MOVE 5.25       TO WS-ACCOUNT-INT-RATE(5).


           MOVE 5 TO ACCOUNT-OVERDRAFT-COUNT.

           MOVE 0          TO WS-ACCOUNT-OVERDRAFT-LIM(1).
           MOVE 0          TO WS-ACCOUNT-OVERDRAFT-LIM(2).
           MOVE 00000100   TO WS-ACCOUNT-OVERDRAFT-LIM(3).
           MOVE 0          TO WS-ACCOUNT-OVERDRAFT-LIM(4).
           MOVE 0          TO WS-ACCOUNT-OVERDRAFT-LIM(5).


       IA999.
           EXIT.

      *
      * Delete the rows on ACCJSON file 
      *
       OPEN-ACCOUNT-JSON SECTION.
       OAJ010.
      *
      * Replaces DELETE-DB2-ROWS: the reset endpoint deletes the
      * ACCOUNT and account CONTROL rows for the sortcode itself.
      *
           OPEN OUTPUT ACCOUNT-JSON-FILE.
           IF ACCJSON-STATUS NOT = '00'
              DISPLAY 'Error opening ACCJSON file, status='
                 ACCJSON-STATUS
              MOVE 12 TO RETURN-CODE
              PERFORM PROGRAM-DONE
           END-IF.
           MOVE 'Y' TO ACCJSON-FIRST.
           MOVE '[' TO ACCOUNT-JSON-RECORD.
           PERFORM WRITE-ACCJSON-RECORD.
       OAJ999.
           EXIT.


       WRITE-ACCOUNT-JSON SECTION.
       WAJ010.
      *
      * Replaces the INSERT INTO ACCOUNT: one array element per
      * record, field names as the data service account object.
      *
           MOVE GEN-ACCOUNT-INTEREST-RATE TO ACCJSON-EDIT-RATE.
           MOVE GEN-ACCOUNT-OVERDRAFT-LIMIT TO ACCJSON-EDIT-OVERDRAFT.
           MOVE GEN-ACCOUNT-AVAILABLE-BALANCE
              TO ACCJSON-EDIT-AVAIL-BAL.
           MOVE GEN-ACCOUNT-ACTUAL-BALANCE TO ACCJSON-EDIT-ACTUAL-BAL.
           MOVE SPACES TO ACCOUNT-JSON-RECORD.
           MOVE 1 TO ACCJSON-PTR.
           IF ACCJSON-FIRST = 'N'
              STRING ',' DELIMITED BY SIZE
                INTO ACCOUNT-JSON-RECORD WITH POINTER ACCJSON-PTR
              END-STRING
           END-IF.
           MOVE 'N' TO ACCJSON-FIRST.
           STRING '{"eyeCatcher":"' DELIMITED BY SIZE
                  GEN-ACCOUNT-EYECATCHER DELIMITED BY SIZE
                  '","customerNumber":"' DELIMITED BY SIZE
                  GEN-ACCOUNT-CUST-NO DELIMITED BY SIZE
                  '","sortcode":"' DELIMITED BY SIZE
                  GEN-ACCOUNT-SORT-CODE DELIMITED BY SIZE
                  '","accountNumber":"' DELIMITED BY SIZE
                  GEN-ACCOUNT-NUMBER DELIMITED BY SIZE
                  '","accountType":"' DELIMITED BY SIZE
                  GEN-ACCOUNT-TYPE DELIMITED BY SIZE
                  '","interestRate":"' DELIMITED BY SIZE
                  FUNCTION TRIM(ACCJSON-EDIT-RATE) DELIMITED BY SIZE
                  '","opened":"' DELIMITED BY SIZE
                  GEN-ACCOUNT-OPENED-DAY DELIMITED BY SIZE
                  GEN-ACCOUNT-OPENED-MONTH DELIMITED BY SIZE
                  GEN-ACCOUNT-OPENED-YEAR DELIMITED BY SIZE
                  '","overdraftLimit":' DELIMITED BY SIZE
                  FUNCTION TRIM(ACCJSON-EDIT-OVERDRAFT)
                     DELIMITED BY SIZE
                  ',"lastStatementDate":"' DELIMITED BY SIZE
                  GEN-ACCOUNT-LAST-STMT-DAY DELIMITED BY SIZE
                  GEN-ACCOUNT-LAST-STMT-MONTH DELIMITED BY SIZE
                  GEN-ACCOUNT-LAST-STMT-YEAR DELIMITED BY SIZE
                  '","nextStatementDate":"' DELIMITED BY SIZE
                  GEN-ACCOUNT-NEXT-STMT-DAY DELIMITED BY SIZE
                  GEN-ACCOUNT-NEXT-STMT-MONTH DELIMITED BY SIZE
                  GEN-ACCOUNT-NEXT-STMT-YEAR DELIMITED BY SIZE
                  '","availableBalance":"' DELIMITED BY SIZE
                  FUNCTION TRIM(ACCJSON-EDIT-AVAIL-BAL)
                     DELIMITED BY SIZE
                  '","actualBalance":"' DELIMITED BY SIZE
                  FUNCTION TRIM(ACCJSON-EDIT-ACTUAL-BAL)
                     DELIMITED BY SIZE
                  '"}' DELIMITED BY SIZE
             INTO ACCOUNT-JSON-RECORD WITH POINTER ACCJSON-PTR
           END-STRING.
           PERFORM WRITE-ACCJSON-RECORD.
       WAJ999.
           EXIT.


       CLOSE-ACCOUNT-JSON SECTION.
       CAJ010.
           MOVE ']' TO ACCOUNT-JSON-RECORD.
           PERFORM WRITE-ACCJSON-RECORD.
           CLOSE ACCOUNT-JSON-FILE.
           DISPLAY 'ACCJSON written for SORTCODE=' SORTCODE
              ' accounts=' NUMBER-OF-ACCOUNTS
              ' last account=' LAST-ACCOUNT-NUMBER.
       CAJ999.
           EXIT.


       WRITE-ACCJSON-RECORD SECTION.
       WAR010.
           WRITE ACCOUNT-JSON-RECORD.
           IF ACCJSON-STATUS NOT = '00'
              DISPLAY 'Error writing ACCJSON file, status='
                 ACCJSON-STATUS ' for ACC no=' GEN-ACCOUNT-NUMBER
              MOVE 12 TO RETURN-CODE
              PERFORM PROGRAM-DONE
           END-IF.
       WAR999.
           EXIT.

      *
      * Get todays date and store it as an INTEGER
      *
       GET-TODAYS-DATE SECTION.
       GTD010.
      D    DISPLAY 'IN GET-TODAYS-DATE SECTION'.

           MOVE FUNCTION CURRENT-DATE TO WS-CURRENT-DATE-DATA.

           MOVE WS-CURRENT-DATE-DATA (1:8) TO WS-CURRENT-DATE-9.

           COMPUTE WS-TODAY-INT =
              FUNCTION INTEGER-OF-DATE (WS-CURRENT-DATE-9).

       GTD999.
           EXIT.

       CALC-DAY-OF-WEEK SECTION.
       CDW010.
      *
      *    Calculate the day of the week. If WS-DAY-OF-WEEK-VAL
      *    contains 1 this is Sunday and so on.
      *


           STRING 'MONDAY   TUESDAY  WEDNESDAYTHURSDAY FRIDAY   '
                  DELIMITED BY SIZE
                  'SATURDAY SUNDAY   '
                  DELIMITED BY SIZE
                  INTO WS-WEEKDAYS
           END-STRING.

      *    COMPUTE WS-DAY-OF-WEEK-VAL =
      *       (FUNCTION
      *        MOD(FUNCTION
      *        INTEGER-OF-DATE(WS-CURRENT-DATE-9) 7))+1.

           DIVIDE WS-CURRENT-DATE-9 BY 7 GIVING WS-DT
                                         REMAINDER
                                         WS-DT-REM.
           IF WS-DT-REM = 0
              MOVE 7 TO WS-DT-REM
           END-IF.

           MOVE WS-WEEKDAY(WS-DT-REM) TO WS-DAY-TODAY.

      D    DISPLAY 'WS-DT-REM' WS-DT-REM.
      D    DISPLAY 'DAY OF THE WEEK IS ' WS-DAY-TODAY.

       CDW999.
           EXIT.

       TIMESTAMP SECTION.
           CALL 'CEEGMT' USING BY REFERENCE gmt-lilian
                                           BY REFERENCE gmt-seconds
                                           BY REFERENCE fc

      ***
      *** Move gmt to an integer so that we don't round up
      *** in the division
      ***
           MOVE gmt-seconds TO period-next-f.
           COMPUTE period-next   = period-next-f.

      ***
      *** Convert back into text
      ***
           MOVE 14               TO datm-length.
           MOVE 'YYYYMMDDHHMISS' TO datm-format.
           CALL 'CEEDATM' USING BY REFERENCE period-next
                                BY REFERENCE datm-picture
                                BY REFERENCE datm-conv
                                BY REFERENCE fc.
      D    DISPLAY TIMESTAMP-FUNCTION ' AT ' DATM-CONV(1:14).
           EXIT.
