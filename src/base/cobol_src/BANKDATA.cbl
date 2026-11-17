       CBL SQL
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
      *              (DB2).                                            *
      *                                                                *
      *                                                                *
      * Input: parm='fffffff,ttttttt,ssssss,rrrrrr'                    *
      *        where fffffff is the key to start generating from       *
      *              ttttttt is the last key to generate               *
      *              sssssss is the step for generation                *
      *              rrrrrrr is the random event seed                  *
      *                                                                *
      * Output: The populated VSAM file CUSTOMER, the DB2 table        *
      *         ACCOUNT.                                               *
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

       DATA DIVISION.
      *****************************************************************
      *** File Section                                              ***
      *****************************************************************
       FILE SECTION.

       FD  CUSTOMER-FILE.
       01  CUSTOMER-RECORD-STRUCTURE.
       COPY CUSTOMER.



      *****************************************************************
      *** Working storage                                           ***
      *****************************************************************
       WORKING-STORAGE SECTION.



      * Get the ACCOUNT DB2 copybook
           EXEC SQL
              INCLUDE ACCDB2
           END-EXEC.

       77  WS-ACCOUNT-OPENED-DAY PIC 99.
       77  WS-ACCOUNT-OPENED-MONTH PIC 99.
       77  WS-ACCOUNT-OPENED-YEAR PIC 9999.

      * ACCOUNT Host variables for DB2
       01 HOST-ACCOUNT-ROW.
           03 HV-ACCOUNT-DATA.
              05 HV-ACCOUNT-EYECATCHER         PIC X(4).
              05 HV-ACCOUNT-CUST-NO            PIC X(10).
              05 HV-ACCOUNT-KEY.
                 07 HV-ACCOUNT-SORT-CODE       PIC X(6).
                 07 HV-ACCOUNT-NUMBER          PIC X(8).
              05 HV-ACCOUNT-TYPE               PIC X(8).
              05 HV-ACCOUNT-INTEREST-RATE      PIC S9(4)V99 COMP-3.
              05 HV-ACCOUNT-OPENED             PIC X(10).
              05 HV-ACCOUNT-OPENED-GROUP REDEFINES HV-ACCOUNT-OPENED.
                 07 HV-ACCOUNT-OPENED-DAY      PIC XX.
                 07 HV-ACCOUNT-OPENED-DELIM1   PIC X.
                 07 HV-ACCOUNT-OPENED-MONTH    PIC XX.
                 07 HV-ACCOUNT-OPENED-DELIM2   PIC X.
                 07 HV-ACCOUNT-OPENED-YEAR     PIC X(4).
              05 HV-ACCOUNT-OVERDRAFT-LIMIT    PIC S9(9) COMP.
              05 HV-ACCOUNT-LAST-STMT-DATE     PIC X(10).
              05 HV-ACCOUNT-LAST-STMT-GROUP
                 REDEFINES HV-ACCOUNT-LAST-STMT-DATE.
                  07 HV-ACCOUNT-LAST-STMT-DAY   PIC XX.
                  07 HV-ACCOUNT-LAST-STMT-DELIM1 PIC X.
                  07 HV-ACCOUNT-LAST-STMT-MONTH  PIC XX.
                  07 HV-ACCOUNT-LAST-STMT-DELIM2 PIC X.
                  07 HV-ACCOUNT-LAST-STMT-YEAR   PIC X(4).
              05 HV-ACCOUNT-NEXT-STMT-DATE     PIC X(10).
              05 HV-ACCOUNT-NEXT-STMT-GROUP
                 REDEFINES HV-ACCOUNT-NEXT-STMT-DATE.
                 07 HV-ACCOUNT-NEXT-STMT-DAY    PIC XX.
                 07 HV-ACCOUNT-NEXT-STMT-DELIM1 PIC X.
                 07 HV-ACCOUNT-NEXT-STMT-MONTH  PIC XX.
                 07 HV-ACCOUNT-NEXT-STMT-DELIM2 PIC X.
                 07 HV-ACCOUNT-NEXT-STMT-YEAR   PIC X(4).
              05 HV-ACCOUNT-AVAILABLE-BALANCE  PIC S9(10)V99 COMP-3.
              05 HV-ACCOUNT-ACTUAL-BALANCE     PIC S9(10)V99 COMP-3.

      * Pull in the SQL COMMAREA
       EXEC SQL
          INCLUDE SQLCA
       END-EXEC.


       01 DISP-LOT.
          03 DISP-SIGN      PIC X.
          03 DISP-SQLCD     PIC 9999.

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


       01 WS-SQLCODE-DISPLAY             PIC S9(8) DISPLAY
           SIGN LEADING SEPARATE.

       01 OPENED-DATE-ATTEMPTS           PIC S9(8) DISPLAY
           SIGN LEADING SEPARATE.

       01 OPENED-DATE-VALID              PIC X VALUE 'N'.



       01 COMMIT-COUNT PIC S9(8) BINARY.

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
           EXEC SQL
              INCLUDE CONTDB2
           END-EXEC.

      * CONTROL Host variables for DB2
       01 HOST-CONTROL-ROW.
           03 HV-CONTROL-NAME                  PIC X(32).
           03 HV-CONTROL-VALUE-NUM             PIC S9(9) COMP.
           03 HV-CONTROL-VALUE-STR             PIC X(40).


       01 WS-WEEKDAYS                    PIC X(63) VALUE ' '.
       01 WS-WEEKDAY-GROUP
           REDEFINES WS-WEEKDAYS.
           03 WS-WEEKDAY                 PIC X(9) OCCURS 7 TIMES.
       01 WS-DT                          PIC 9(8) VALUE 0.
       01 WS-DT-REM                      PIC S9(9) COMP VALUE 0.
       01 WS-DAY-OF-WEEK-VAL             PIC 9    VALUE 0.
       01 WS-DAY-TODAY                   PIC X(9) VALUE ' '.

       01 SQLCODE-DISPLAY                PIC S9(8) DISPLAY
           SIGN LEADING SEPARATE.

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
      * Delete the DB2 TABLE contents that match the SortCode
      *
      D    DISPLAY 'About to delete DB2 rows'.

           PERFORM DELETE-DB2-ROWS.
      D    DISPLAY 'Back from delete DB2 rows'.
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
                 EXEC SQL
                  COMMIT WORK
                 END-EXEC
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
      * We need to store 2 values in DB2
      * <<sortcode>>-ACCOUNT-LAST
      * <<sortcode>>-ACCOUNT-COUNT


           MOVE SPACES TO HV-CONTROL-NAME
           MOVE LAST-ACCOUNT-NUMBER TO HV-CONTROL-VALUE-NUM
           MOVE SPACES TO HV-CONTROL-VALUE-STR
           STRING SORTCODE DELIMITED BY SIZE
           '-' DELIMITED BY SIZE
           'ACCOUNT-LAST' DELIMITED BY SIZE
           INTO HV-CONTROL-NAME
           EXEC SQL
              INSERT INTO CONTROL
                      (CONTROL_NAME,
                       CONTROL_VALUE_NUM,
                       CONTROL_VALUE_STR
                      )
              VALUES (:HV-CONTROL-NAME,
                      :HV-CONTROL-VALUE-NUM,
                      :HV-CONTROL-VALUE-STR
                     )
           END-EXEC.

           IF SQLCODE IS NOT EQUAL TO ZERO
             MOVE SQLCODE TO WS-SQLCODE-DISPLAY
             DISPLAY 'Error inserting last account control record '
             ws-sqlcode-display
             '.'
             HV-CONTROL-NAME,
             ','
             HV-CONTROL-VALUE-NUM
           END-IF

           MOVE SPACES TO HV-CONTROL-NAME
           MOVE NUMBER-OF-ACCOUNTS TO HV-CONTROL-VALUE-NUM
           MOVE SPACES TO HV-CONTROL-VALUE-STR
           STRING SORTCODE DELIMITED BY SIZE
           '-' DELIMITED BY SIZE
           'ACCOUNT-COUNT' DELIMITED BY SIZE
           INTO HV-CONTROL-NAME
           EXEC SQL
              INSERT INTO CONTROL
                      (CONTROL_NAME,
                       CONTROL_VALUE_NUM,
                       CONTROL_VALUE_STR
                      )
              VALUES (:HV-CONTROL-NAME,
                      :HV-CONTROL-VALUE-NUM,
                      :HV-CONTROL-VALUE-STR
                     )
           END-EXEC.

           IF SQLCODE IS NOT EQUAL TO ZERO
             MOVE SQLCODE TO WS-SQLCODE-DISPLAY
             DISPLAY 'Error inserting account count control record '
             ws-sqlcode-display
             '.'
             HV-CONTROL-NAME,
             ','
             HV-CONTROL-VALUE-NUM
           END-IF



      *
      *** Close the files
      *
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
           EXEC SQL
             COMMIT WORK
           END-EXEC.

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
           MOVE 0 TO HV-ACCOUNT-OPENED-DAY
                     HV-ACCOUNT-OPENED-MONTH
                     HV-ACCOUNT-OPENED-YEAR
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
              HV-ACCOUNT-EYECATCHER OF HOST-ACCOUNT-ROW.
           MOVE CUSTOMER-NUMBER TO
              HV-ACCOUNT-CUST-NO OF HOST-ACCOUNT-ROW.
           MOVE SORTCODE TO
              HV-ACCOUNT-SORT-CODE OF HOST-ACCOUNT-ROW.
           MOVE WS-ACCOUNT-NUMBER TO
              HV-ACCOUNT-NUMBER OF HOST-ACCOUNT-ROW.

           MOVE WS-ACCOUNT-NUMBER TO LAST-ACCOUNT-NUMBER

      *    ADD 1 TO NUMBER-OF-ACCOUNTS GIVING NUMBER-OF-ACCOUNTS

      *    ADD 1 TO WS-ACCOUNT-NUMBER GIVING WS-ACCOUNT-NUMBER

           MOVE WS-ACCOUNT-TYPE(WS-CNT) TO
              HV-ACCOUNT-TYPE OF HOST-ACCOUNT-ROW.
           MOVE WS-ACCOUNT-INT-RATE(WS-CNT) TO
              HV-ACCOUNT-INTEREST-RATE OF HOST-ACCOUNT-ROW.
           MOVE '.' TO HV-ACCOUNT-OPENED-DELIM1.
           MOVE '.' TO HV-ACCOUNT-OPENED-DELIM2.
      *
      * Convert the overdraft into a field compatible with INTEGER on
      * the table
      *
           MOVE WS-ACCOUNT-OVERDRAFT-LIM(WS-CNT) TO
              WS-OVERDRAFT-CONVERSION.
           MOVE WS-OVERDRAFT-CONVERSION TO
              HV-ACCOUNT-OVERDRAFT-LIMIT OF HOST-ACCOUNT-ROW.
           MOVE '2021' TO
              HV-ACCOUNT-LAST-STMT-YEAR OF HOST-ACCOUNT-ROW.
           MOVE '.' TO HV-ACCOUNT-LAST-STMT-DELIM1.
           MOVE '07' TO
              HV-ACCOUNT-LAST-STMT-MONTH OF HOST-ACCOUNT-ROW.
           MOVE '.' TO HV-ACCOUNT-LAST-STMT-DELIM2.
           MOVE '01' TO
              HV-ACCOUNT-LAST-STMT-DAY OF HOST-ACCOUNT-ROW.
           MOVE '2021' TO
              HV-ACCOUNT-NEXT-STMT-YEAR OF HOST-ACCOUNT-ROW.
           MOVE '.' TO HV-ACCOUNT-NEXT-STMT-DELIM1.
           MOVE '08' TO
              HV-ACCOUNT-NEXT-STMT-MONTH OF HOST-ACCOUNT-ROW.
           MOVE '.' TO HV-ACCOUNT-NEXT-STMT-DELIM2.
           MOVE '01' TO
              HV-ACCOUNT-NEXT-STMT-DAY OF HOST-ACCOUNT-ROW.
           COMPUTE HV-ACCOUNT-AVAILABLE-BALANCE
                  = ((999999 - 1)
                                   * FUNCTION RANDOM) + 1.
           MOVE HV-ACCOUNT-AVAILABLE-BALANCE  TO
              HV-ACCOUNT-ACTUAL-BALANCE.


      *
      *    If it is a LOAN or a MORTGAGE then it should really have
      *    a negative balance (it doesn't make any sense for these
      *    accounts to have a positive balance).
      *
           IF HV-ACCOUNT-TYPE OF HOST-ACCOUNT-ROW = 'TENDER  ' OR
           HV-ACCOUNT-TYPE OF HOST-ACCOUNT-ROW = 'HOSPITAL'
                COMPUTE HV-ACCOUNT-ACTUAL-BALANCE =
                   0 - HV-ACCOUNT-ACTUAL-BALANCE OF HOST-ACCOUNT-ROW

                COMPUTE HV-ACCOUNT-AVAILABLE-BALANCE
                   OF HOST-ACCOUNT-ROW =
                   0 - HV-ACCOUNT-AVAILABLE-BALANCE OF HOST-ACCOUNT-ROW

           END-IF.

           EXEC SQL
              INSERT INTO ACCOUNT
                     (ACCOUNT_EYECATCHER,
                      ACCOUNT_CUSTOMER_NUMBER,
                      ACCOUNT_SORTCODE,
                      ACCOUNT_NUMBER,
                      ACCOUNT_TYPE,
                      ACCOUNT_INTEREST_RATE,
                      ACCOUNT_OPENED,
                      ACCOUNT_OVERDRAFT_LIMIT,
                      ACCOUNT_LAST_STATEMENT,
                      ACCOUNT_NEXT_STATEMENT,
                      ACCOUNT_AVAILABLE_BALANCE,
                      ACCOUNT_ACTUAL_BALANCE
                      )
              VALUES (:HV-ACCOUNT-EYECATCHER,
                      :HV-ACCOUNT-CUST-NO,
                      :HV-ACCOUNT-SORT-CODE,
                      :HV-ACCOUNT-NUMBER,
                      :HV-ACCOUNT-TYPE,
                      :HV-ACCOUNT-INTEREST-RATE,
                      :HV-ACCOUNT-OPENED,
                      :HV-ACCOUNT-OVERDRAFT-LIMIT,
                      :HV-ACCOUNT-LAST-STMT-DATE,
                      :HV-ACCOUNT-NEXT-STMT-DATE,
                      :HV-ACCOUNT-AVAILABLE-BALANCE,
                      :HV-ACCOUNT-ACTUAL-BALANCE
                     )
           END-EXEC.

      *
      * Check the SQLCODE
      *
           IF SQLCODE NOT = 0
              MOVE SQLCODE TO WS-SQLCODE-DISPLAY
              EVALUATE TRUE
              WHEN SQLERRD(3) EQUAL 13172878
                 MOVE 'TIMEOUT  (00C900E)' TO DISP-REASON-CODE
              WHEN SQLERRD(3) EQUAL 13172872
                 MOVE 'DEADLOCK (00C9088)' TO DISP-REASON-CODE
              WHEN OTHER
                  MOVE 'Unknown ReasonCode' TO DISP-REASON-CODE
              END-EVALUATE
              DISPLAY DISP-REASON-CODE


              DISPLAY 'Error inserting rows on ACCOUNT table.'
                  'For ACC no=' HV-ACCOUNT-NUMBER ' for SORTCODE='
                  HV-ACCOUNT-SORT-CODE
                  'SQLCODE=' WS-SQLCODE-DISPLAY
                     ',SQLSTATE=' SQLSTATE
                      ',SQLERRMC=' sqlerrmc(1:sqlerrmL)
                      ',sqlerrd(1)=' sqlerrd(1)
                      ',sqlerrd(2)=' sqlerrd(2)
                      ',sqlerrd(3)=' sqlerrd(3)
                      ',sqlerrd(4)=' sqlerrd(4)
                      ',sqlerrd(5)=' sqlerrd(5)
                      ',sqlerrd(6)=' sqlerrd(6)
              MOVE 12 TO RETURN-CODE
              PERFORM PROGRAM-DONE

           END-IF.
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
           MOVE WS-ACCOUNT-OPENED-DAY TO HV-ACCOUNT-OPENED-DAY.
           COMPUTE WS-ACCOUNT-OPENED-MONTH
                  =  ((12 - 1)
                                    * FUNCTION RANDOM) + 1.
           MOVE WS-ACCOUNT-OPENED-MONTH TO HV-ACCOUNT-OPENED-MONTH.
           COMPUTE WS-ACCOUNT-OPENED-YEAR
                  =  ((2014 -
                                    CUSTOMER-BIRTH-YEAR)
                                    * FUNCTION RANDOM) +
                                    CUSTOMER-BIRTH-YEAR.
           MOVE WS-ACCOUNT-OPENED-YEAR TO HV-ACCOUNT-OPENED-YEAR.
      D    DISPLAY 'DATE OF BIRTH IS '
      D             CUSTOMER-BIRTH-DAY
      D             '/'
      D             CUSTOMER-BIRTH-MONTH
      D             '/'
      D             CUSTOMER-BIRTH-YEAR

      D    DISPLAY 'ACCOUNT OPENED  '
      D             HV-ACCOUNT-OPENED-DAY
      D             '/'
      D             HV-ACCOUNT-OPENED-MONTH
      D             '/'
      D             HV-ACCOUNT-OPENED-YEAR

           ADD 1 TO OPENED-DATE-ATTEMPTS GIVING OPENED-DATE-ATTEMPTS.
           IF HV-ACCOUNT-OPENED-YEAR
              > CUSTOMER-BIRTH-YEAR
             MOVE 'Y' TO OPENED-DATE-VALID
             GO TO GOD999
           END-IF.
           IF OPENED-DATE-ATTEMPTS > 100
             MOVE 'Y' TO OPENED-DATE-VALID
             MOVE CUSTOMER-BIRTH-DAY   TO HV-ACCOUNT-OPENED-DAY
             MOVE CUSTOMER-BIRTH-MONTH TO HV-ACCOUNT-OPENED-MONTH
             MOVE CUSTOMER-BIRTH-YEAR  TO HV-ACCOUNT-OPENED-YEAR
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

           MOVE 'Marco'   TO FORENAME(01).
           MOVE 'Giulia'  TO FORENAME(02).
           MOVE 'Luca'    TO FORENAME(03).
           MOVE 'Francesca' TO FORENAME(04).
           MOVE 'Alessandro' TO FORENAME(05).
           MOVE 'Chiara'  TO FORENAME(06).
           MOVE 'Matteo'  TO FORENAME(07).
           MOVE 'Sara'     TO FORENAME(08).
           MOVE 'Lorenzo' TO FORENAME(09).
           MOVE 'Valentina' TO FORENAME(10).
           MOVE 'Andrea'  TO FORENAME(11).
           MOVE 'Elena'   TO FORENAME(12).
           MOVE 'Davide'  TO FORENAME(13).
           MOVE 'Martina' TO FORENAME(14).
           MOVE 'Simone'    TO FORENAME(15).
           MOVE 'Federica' TO FORENAME(16).
           MOVE 'Stefano' TO FORENAME(17).
           MOVE 'Silvia'  TO FORENAME(18).
           MOVE 'Paolo'   TO FORENAME(19).
           MOVE 'Laura'   TO FORENAME(20).
           MOVE 'Giovanni' TO FORENAME(21).
           MOVE 'Anna'    TO FORENAME(22).
           MOVE 'Roberto' TO FORENAME(23).
           MOVE 'Paola'   TO FORENAME(24).
           MOVE 'Riccardo' TO FORENAME(25).
           MOVE 'Elisa'   TO FORENAME(26).
           MOVE 'Fabio'   TO FORENAME(27).
           MOVE 'Alessia'   TO FORENAME(28).
           MOVE 'Emanuele' TO FORENAME(29).
           MOVE 'Beatrice' TO FORENAME(30).
           MOVE 'Nicola'   TO FORENAME(31).
           MOVE 'Ilaria'    TO FORENAME(32).
           MOVE 'Antonio' TO FORENAME(33).
           MOVE 'Monica'    TO FORENAME(34).
           MOVE 'Daniele'   TO FORENAME(35).
           MOVE 'Cristina' TO FORENAME(36).
           MOVE 'Michele' TO FORENAME(37).
           MOVE 'Serena'  TO FORENAME(38).
           MOVE 'Filippo' TO FORENAME(39).
           MOVE 'Roberta'  TO FORENAME(40).
           MOVE 'Tommaso' TO FORENAME(41).
           MOVE 'Claudia' TO FORENAME(42).
           MOVE 'Gabriele' TO FORENAME(43).
           MOVE 'Barbara' TO FORENAME(44).
           MOVE 'Pietro'  TO FORENAME(45).
           MOVE 'Giorgia'  TO FORENAME(46).
           MOVE 'Salvatore' TO FORENAME(47).
           MOVE 'Teresa'  TO FORENAME(48).
           MOVE 'Vincenzo' TO FORENAME(49).
           MOVE 'Lucia'   TO FORENAME(50).

           MOVE 30 TO INITIALS-CNT.

           MOVE 'ABCDEFGHIJLKMNOPQRSTUVWXYZ    ' TO INITIALS.

           MOVE 50 TO SURNAMES-CNT.

           MOVE 'Rossi'        TO SURNAME(01).
           MOVE 'Russo'        TO SURNAME(02).
           MOVE 'Ferrari'      TO SURNAME(03).
           MOVE 'Esposito'     TO SURNAME(04).
           MOVE 'Bianchi'      TO SURNAME(05).
           MOVE 'Romano'       TO SURNAME(06).
           MOVE 'Colombo'      TO SURNAME(07).
           MOVE 'Ricci'        TO SURNAME(08).
           MOVE 'Marino'       TO SURNAME(09).
           MOVE 'Greco'        TO SURNAME(10).
           MOVE 'Bruno'        TO SURNAME(11).
           MOVE 'Gallo'        TO SURNAME(12).
           MOVE 'Conti'        TO SURNAME(13).
           MOVE 'DeLuca'       TO SURNAME(14).
           MOVE 'Mancini'      TO SURNAME(15).
           MOVE 'Costa'        TO SURNAME(16).
           MOVE 'Giordano'     TO SURNAME(17).
           MOVE 'Rizzo'        TO SURNAME(18).
           MOVE 'Lombardi'     TO SURNAME(19).
           MOVE 'Moretti'      TO SURNAME(20).
           MOVE 'Barbieri'     TO SURNAME(21).
           MOVE 'Fontana'      TO SURNAME(22).
           MOVE 'Santoro'      TO SURNAME(23).
           MOVE 'Mariani'      TO SURNAME(24).
           MOVE 'Mariani'      TO SURNAME(24).
           MOVE 'Rinaldi'      TO SURNAME(25).
           MOVE 'Caruso'       TO SURNAME(26).
           MOVE 'Ferrara'      TO SURNAME(27).
           MOVE 'Galli'        TO SURNAME(28).
           MOVE 'Martini'      TO SURNAME(29).
           MOVE 'Leone'        TO SURNAME(30).
           MOVE 'Longo'        TO SURNAME(31).
           MOVE 'Gentile'      TO SURNAME(32).
           MOVE 'Martinelli'   TO SURNAME(33).
           MOVE 'Vitale'       TO SURNAME(34).
           MOVE 'Lombardo'     TO SURNAME(35).
           MOVE 'Serra'        TO SURNAME(36).
           MOVE 'Coppola'      TO SURNAME(37).
           MOVE 'DeSantis'     TO SURNAME(38).
           MOVE 'DAngelo'      TO SURNAME(39).
           MOVE 'Marchetti'    TO SURNAME(40).
           MOVE 'Parisi'       TO SURNAME(41).
           MOVE 'Villa'        TO SURNAME(42).
           MOVE 'Conte'        TO SURNAME(43).
           MOVE 'Ferraro'      TO SURNAME(44).
           MOVE 'Ferri'        TO SURNAME(45).
           MOVE 'Fabbri'       TO SURNAME(46).
           MOVE 'Bianco'       TO SURNAME(47).
           MOVE 'Marini'       TO SURNAME(48).
           MOVE 'Grasso'       TO SURNAME(49).
           MOVE 'Valentini'    TO SURNAME(50).

           MOVE 26 TO STREET-NAME-T-CNT.
           MOVE 19 TO STREET-NAME-R-CNT.

           MOVE 'Via'    to    STREET-NAME-TREE(1).
           MOVE 'Viale' to     STREET-NAME-TREE(2).
           MOVE 'Corso'   to   STREET-NAME-TREE(3).
           MOVE 'Piazza'  to   STREET-NAME-TREE(4).
           MOVE 'Via' to       STREET-NAME-TREE(5).
           MOVE 'Largo' to       STREET-NAME-TREE(6).
           MOVE 'Via'   to     STREET-NAME-TREE(7).
           MOVE 'Viale' to     STREET-NAME-TREE(8).
           MOVE 'Via'      to  STREET-NAME-TREE(9).
           MOVE 'Corso'  to    STREET-NAME-TREE(10).
           MOVE 'Via'   to     STREET-NAME-TREE(11).
           MOVE 'Piazza'   to  STREET-NAME-TREE(12).
           MOVE 'Via'   to     STREET-NAME-TREE(13).
           MOVE 'Viale'  to    STREET-NAME-TREE(14).
           MOVE 'Via' to       STREET-NAME-TREE(15).
           MOVE 'Vicolo' to      STREET-NAME-TREE(16).
           MOVE 'Via'      to  STREET-NAME-TREE(17).
           MOVE 'Corso' to     STREET-NAME-TREE(18).
           MOVE 'Via'      to  STREET-NAME-TREE(19).
           MOVE 'Viale' to     STREET-NAME-TREE(20).
           MOVE 'Via'   to     STREET-NAME-TREE(21).
           MOVE 'Piazza'   to  STREET-NAME-TREE(22).
           MOVE 'Via'    to    STREET-NAME-TREE(23).
           MOVE 'Viale'     to STREET-NAME-TREE(24).
           MOVE 'Via' to       STREET-NAME-TREE(25).
           MOVE 'Corso'     to STREET-NAME-TREE(26).


           MOVE 'Garibaldi' to    STREET-NAME-ROAD(1).
           MOVE 'Mazzini'   to STREET-NAME-ROAD(2).
           MOVE 'Cavour' to     STREET-NAME-ROAD(3).
           MOVE 'Dante'    to  STREET-NAME-ROAD(4).
           MOVE 'Verdi' to     STREET-NAME-ROAD(5).
           MOVE 'Roma'     to  STREET-NAME-ROAD(6).
           MOVE 'Marconi'  to  STREET-NAME-ROAD(7).
           MOVE 'Matteotti' to      STREET-NAME-ROAD(8).
           MOVE 'Gramsci' to      STREET-NAME-ROAD(9).
           MOVE 'Leopardi' to      STREET-NAME-ROAD(10).
           MOVE 'Manzoni' to     STREET-NAME-ROAD(11).
           MOVE 'Puccini' to   STREET-NAME-ROAD(12).
           MOVE 'Petrarca' to      STREET-NAME-ROAD(13).
           MOVE 'Volta'  to    STREET-NAME-ROAD(14).
           MOVE 'Galilei' to    STREET-NAME-ROAD(15).
           MOVE 'Fermi' to      STREET-NAME-ROAD(16).
           MOVE 'Carducci' to    STREET-NAME-ROAD(17).
           MOVE 'Pascoli' to     STREET-NAME-ROAD(18).
           MOVE 'Medici' to     STREET-NAME-ROAD(19).

           MOVE 50 TO TOWN-COUNT.

           MOVE 'Firenze'      TO TOWN(01).
           MOVE 'Roma'         TO TOWN(02).
           MOVE 'Milano'       TO TOWN(03).
           MOVE 'Napoli'       TO TOWN(04).
           MOVE 'Torino'       TO TOWN(05).
           MOVE 'Bologna'      TO TOWN(06).
           MOVE 'Genova'       TO TOWN(07).
           MOVE 'Venezia'      TO TOWN(08).
           MOVE 'Verona'       TO TOWN(09).
           MOVE 'Pisa'         TO TOWN(10).
           MOVE 'Siena'        TO TOWN(11).
           MOVE 'Lucca'        TO TOWN(12).
           MOVE 'Prato'        TO TOWN(13).
           MOVE 'Livorno'      TO TOWN(14).
           MOVE 'Arezzo'       TO TOWN(15).
           MOVE 'Perugia'      TO TOWN(16).
           MOVE 'Ancona'       TO TOWN(17).
           MOVE 'Pescara'      TO TOWN(18).
           MOVE 'Bari'         TO TOWN(19).
           MOVE 'Lecce'        TO TOWN(20).
           MOVE 'Palermo'      TO TOWN(21).
           MOVE 'Catania'      TO TOWN(22).
           MOVE 'Cagliari'     TO TOWN(23).
           MOVE 'Sassari'      TO TOWN(24).
           MOVE 'Trieste'      TO TOWN(25).
           MOVE 'Udine'        TO TOWN(26).
           MOVE 'Padova'       TO TOWN(27).
           MOVE 'Vicenza'      TO TOWN(28).
           MOVE 'Treviso'      TO TOWN(29).
           MOVE 'Trento'       TO TOWN(30).
           MOVE 'Bolzano'      TO TOWN(31).
           MOVE 'Brescia'      TO TOWN(32).
           MOVE 'Bergamo'      TO TOWN(33).
           MOVE 'Como'         TO TOWN(34).
           MOVE 'Monza'        TO TOWN(35).
           MOVE 'Pavia'        TO TOWN(36).
           MOVE 'Parma'        TO TOWN(37).
           MOVE 'Modena'       TO TOWN(38).
           MOVE 'Reggio'       TO TOWN(39).
           MOVE 'Ravenna'      TO TOWN(40).
           MOVE 'Rimini'       TO TOWN(41).
           MOVE 'Ferrara'      TO TOWN(42).
           MOVE 'Piacenza'     TO TOWN(43).
           MOVE 'Mantova'      TO TOWN(44).
           MOVE 'Cremona'      TO TOWN(45).
           MOVE 'Novara'       TO TOWN(46).
           MOVE 'Asti'         TO TOWN(47).
           MOVE 'Cuneo'        TO TOWN(48).
           MOVE 'Salerno'      TO TOWN(49).
           MOVE 'Messina'      TO TOWN(50).


           MOVE 5 TO ACCOUNT-TYPES-COUNT.

           MOVE 'OTC     ' TO WS-ACCOUNT-TYPE(1).
           MOVE 'EXPORT  ' TO WS-ACCOUNT-TYPE(2).
           MOVE 'RX      ' TO WS-ACCOUNT-TYPE(3).
           MOVE 'TENDER  ' TO WS-ACCOUNT-TYPE(4).
           MOVE 'HOSPITAL' TO WS-ACCOUNT-TYPE(5).


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
      * Delete the rows on the DB2 table
      *
       DELETE-DB2-ROWS SECTION.
       DBR010.
      *
      * Delete the ACCOUNT table data
      *
           MOVE SORTCODE TO HV-ACCOUNT-SORT-CODE.
           MOVE 'Deleting from ACCOUNT table'
             to TIMESTAMP-FUNCTION
           perform TIMESTAMP
           EXEC SQL
              DELETE FROM ACCOUNT
              WHERE ACCOUNT_SORTCODE = :HV-ACCOUNT-SORT-CODE
           END-EXEC.


      *
      * Check the SQL code if it was NOT OK (0) or NOTFND (+100)
      * then abend
      *
           MOVE 'Deleting from ACCOUNT table COMPLETE'
             to TIMESTAMP-FUNCTION
           perform TIMESTAMP
           IF SQLCODE NOT = +100 AND
           SQLCODE NOT = 0

              MOVE SQLCODE TO DISP-SQLCD
              IF SQLCODE < 0
                 MOVE '-' TO DISP-SIGN
              ELSE
                 MOVE '+' TO DISP-SIGN
              END-IF
              EVALUATE TRUE
              WHEN SQLERRD(3) EQUAL 13172878
                 MOVE 'TIMEOUT  (00C900E)' TO DISP-REASON-CODE
              WHEN SQLERRD(3) EQUAL 13172872
                 MOVE 'DEADLOCK (00C9088)' TO DISP-REASON-CODE
              WHEN OTHER
                  MOVE 'Unknown ReasonCode' TO DISP-REASON-CODE
              END-EVALUATE

              DISPLAY 'Error deleting rows from ACCOUNT table. For'
                      ' SORTCODE=' HV-ACCOUNT-SORT-CODE
                      ' SQLCODE=' DISP-LOT
                      ' SQLREASON=' DISP-REASON-CODE ' ( '
                      SQLERRD (3) ')'
                      ',SQLSTATE=' SQLSTATE
                      ',SQLERRMC=' sqlerrmc(1:sqlerrmL)
                      ',sqlerrd(1)=' sqlerrd(1)
                      ',sqlerrd(2)=' sqlerrd(2)
                      ',sqlerrd(3)=' sqlerrd(3)
                      ',sqlerrd(4)=' sqlerrd(4)
                      ',sqlerrd(5)=' sqlerrd(5)
                      ',sqlerrd(6)=' sqlerrd(6)

              MOVE 12 TO RETURN-CODE
              PERFORM PROGRAM-DONE

           END-IF.

      *
      * Delete the CONTROL table data
      *
           MOVE SPACES TO HV-CONTROL-NAME
           STRING SORTCODE DELIMITED BY SIZE
           '-' DELIMITED BY SIZE
           'ACCOUNT-LAST' DELIMITED BY SIZE
           INTO HV-CONTROL-NAME

           MOVE 'Deleting from CONTROL table'
             to TIMESTAMP-FUNCTION.

           PERFORM TIMESTAMP.

           EXEC SQL
              DELETE FROM CONTROL
              WHERE CONTROL_NAME = :HV-CONTROL-NAME
           END-EXEC.


      *
      *    Check the SQL code if it was NOT OK (0) or NOTFND (+100)
      *    then abend
      *
           MOVE 'Deleting from CONTROL table COMPLETE'
             to TIMESTAMP-FUNCTION.

           PERFORM TIMESTAMP.

           IF SQLCODE NOT = +100 AND
           SQLCODE NOT = 0

              MOVE SQLCODE TO DISP-SQLCD
              IF SQLCODE < 0
                 MOVE '-' TO DISP-SIGN
              ELSE
                 MOVE '+' TO DISP-SIGN
              END-IF
              EVALUATE TRUE
              WHEN SQLERRD(3) EQUAL 13172878
                 MOVE 'TIMEOUT  (00C900E)' TO DISP-REASON-CODE
              WHEN SQLERRD(3) EQUAL 13172872
                 MOVE 'DEADLOCK (00C9088)' TO DISP-REASON-CODE
              WHEN OTHER
                  MOVE 'Unknown ReasonCode' TO DISP-REASON-CODE
              END-EVALUATE

              DISPLAY 'Error deleting rows from CONTROL table. For'
                      ' CONTROL_NAME=' HV-CONTROL-NAME
                      ' SQLCODE=' DISP-LOT
                      ' SQLREASON=' DISP-REASON-CODE ' ( '
                      SQLERRD (3) ')'
                      ',SQLSTATE=' SQLSTATE
                      ',SQLERRMC=' sqlerrmc(1:sqlerrmL)
                      ',sqlerrd(1)=' sqlerrd(1)
                      ',sqlerrd(2)=' sqlerrd(2)
                      ',sqlerrd(3)=' sqlerrd(3)
                      ',sqlerrd(4)=' sqlerrd(4)
                      ',sqlerrd(5)=' sqlerrd(5)
                      ',sqlerrd(6)=' sqlerrd(6)

              MOVE 12 TO RETURN-CODE
              PERFORM PROGRAM-DONE

           END-IF.

      *
      * Delete the CONTROL table data
      *
           MOVE SPACES TO HV-CONTROL-NAME
           STRING SORTCODE DELIMITED BY SIZE
           '-' DELIMITED BY SIZE
           'ACCOUNT-COUNT' DELIMITED BY SIZE
           INTO HV-CONTROL-NAME

           MOVE 'Deleting from CONTROL table'
             to TIMESTAMP-FUNCTION.

           PERFORM TIMESTAMP.

           EXEC SQL
              DELETE FROM CONTROL
              WHERE CONTROL_NAME = :HV-CONTROL-NAME
           END-EXEC.


      *
      *    Check the SQL code if it was NOT OK (0) or NOTFND (+100)
      *    then abend
      *
           MOVE 'Deleting from CONTROL table COMPLETE'
             to TIMESTAMP-FUNCTION.

           PERFORM TIMESTAMP.

           IF SQLCODE NOT = +100 AND
           SQLCODE NOT = 0

              MOVE SQLCODE TO DISP-SQLCD
              IF SQLCODE < 0
                 MOVE '-' TO DISP-SIGN
              ELSE
                 MOVE '+' TO DISP-SIGN
              END-IF
              EVALUATE TRUE
              WHEN SQLERRD(3) EQUAL 13172878
                 MOVE 'TIMEOUT  (00C900E)' TO DISP-REASON-CODE
              WHEN SQLERRD(3) EQUAL 13172872
                 MOVE 'DEADLOCK (00C9088)' TO DISP-REASON-CODE
              WHEN OTHER
                  MOVE 'Unknown ReasonCode' TO DISP-REASON-CODE
              END-EVALUATE

              DISPLAY 'Error deleting rows from CONTROL table. For'
                      ' CONTROL_NAME=' HV-CONTROL-NAME
                      ' SQLCODE=' DISP-LOT
                      ' SQLREASON=' DISP-REASON-CODE ' ( '
                      SQLERRD (3) ')'
                      ',SQLSTATE=' SQLSTATE
                      ',SQLERRMC=' sqlerrmc(1:sqlerrmL)
                      ',sqlerrd(1)=' sqlerrd(1)
                      ',sqlerrd(2)=' sqlerrd(2)
                      ',sqlerrd(3)=' sqlerrd(3)
                      ',sqlerrd(4)=' sqlerrd(4)
                      ',sqlerrd(5)=' sqlerrd(5)
                      ',sqlerrd(6)=' sqlerrd(6)

              MOVE 12 TO RETURN-CODE
              PERFORM PROGRAM-DONE

           END-IF.


           EXEC SQL
              COMMIT WORK
           END-EXEC.


       DBR999.
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
