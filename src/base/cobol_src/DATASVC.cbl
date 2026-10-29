       PROCESS CICS,NODYNAM,NSYMBOL(NATIONAL),TRUNC(STD)
       CBL CICS('SP,EDF,DLI')
      ******************************************************************
      *                                                                *
      *  Copyright IBM Corp. 2023                                      *
      *                                                                *
      ******************************************************************
      ******************************************************************
      * DATASVC - client for the CBSA Java data services.
      *
      * ACCOUNT, PROCTRAN and CONTROL now live in PostgreSQL behind
      * the Liberty web tier. Programs that used EXEC SQL LINK here
      * with the DATASVC commarea; this program makes one HTTP call
      * through URIMAP CBSADSV (scheme/host/port, base path /banking)
      * and hands back the status code and JSON body.
      *
      * DATASVC never abends: the caller decides what an HTTP status
      * or transport failure means, using the contract in
      * dataservice/account/API.md and dataservice/movement/API.md.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. DATASVC.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       SOURCE-COMPUTER.  IBM-370.
       OBJECT-COMPUTER.  IBM-370.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-BASE-PATH                 PIC X(8)  VALUE '/banking'.
       01 WS-FULL-PATH                 PIC X(208).
       01 WS-PATH-LEN                  PIC S9(8) COMP.
       01 WS-SESSTOKEN                 PIC X(8).
       01 WS-METHOD-CVDA               PIC S9(8) COMP.
       01 WS-STATUS                    PIC S9(4) COMP.
       01 WS-MAXLEN                    PIC S9(8) COMP.
       01 WS-MEDIATYPE                 PIC X(56)
                                       VALUE 'application/json'.
       01 WS-RESP                      PIC S9(8) COMP.
       01 WS-RESP2                     PIC S9(8) COMP.

       LINKAGE SECTION.
       01 DFHCOMMAREA.
          COPY DATASVC.

       PROCEDURE DIVISION.
       PREMIERE SECTION.
       A010.
           MOVE '0' TO DSV-RC
           MOVE 0 TO DSV-HTTP-STATUS DSV-RESP-LEN
                     DSV-CICS-RESP DSV-CICS-RESP2

           MOVE SPACES TO WS-FULL-PATH
           STRING WS-BASE-PATH DELIMITED BY SIZE
                  DSV-PATH DELIMITED BY SPACE
             INTO WS-FULL-PATH
           END-STRING
           MOVE 0 TO WS-PATH-LEN
           INSPECT WS-FULL-PATH TALLYING WS-PATH-LEN
              FOR CHARACTERS BEFORE INITIAL SPACE

           EVALUATE TRUE
              WHEN DSV-POST
                 MOVE DFHVALUE(POST) TO WS-METHOD-CVDA
              WHEN DSV-PUT
                 MOVE DFHVALUE(PUT) TO WS-METHOD-CVDA
              WHEN DSV-DELETE
                 MOVE DFHVALUE(DELETE) TO WS-METHOD-CVDA
              WHEN OTHER
                 MOVE DFHVALUE(GET) TO WS-METHOD-CVDA
           END-EVALUATE

           EXEC CICS WEB OPEN
                URIMAP('CBSADSV')
                SESSTOKEN(WS-SESSTOKEN)
                RESP(WS-RESP)
                RESP2(WS-RESP2)
           END-EXEC

           IF WS-RESP NOT = DFHRESP(NORMAL)
              MOVE '1' TO DSV-RC
              MOVE WS-RESP TO DSV-CICS-RESP
              MOVE WS-RESP2 TO DSV-CICS-RESP2
              GO TO A999
           END-IF

           MOVE LENGTH OF DSV-RESP-BODY TO WS-MAXLEN

           IF DSV-REQ-LEN > 0
              EXEC CICS WEB CONVERSE
                   SESSTOKEN(WS-SESSTOKEN)
                   METHOD(WS-METHOD-CVDA)
                   PATH(WS-FULL-PATH)
                   PATHLENGTH(WS-PATH-LEN)
                   MEDIATYPE(WS-MEDIATYPE)
                   FROM(DSV-REQ-BODY)
                   FROMLENGTH(DSV-REQ-LEN)
                   CHARACTERSET('UTF-8')
                   CLIENTCONV(CLICONVERT)
                   INTO(DSV-RESP-BODY)
                   TOLENGTH(DSV-RESP-LEN)
                   MAXLENGTH(WS-MAXLEN)
                   STATUSCODE(WS-STATUS)
                   CLOSE
                   RESP(WS-RESP)
                   RESP2(WS-RESP2)
              END-EXEC
           ELSE
              EXEC CICS WEB CONVERSE
                   SESSTOKEN(WS-SESSTOKEN)
                   METHOD(WS-METHOD-CVDA)
                   PATH(WS-FULL-PATH)
                   PATHLENGTH(WS-PATH-LEN)
                   CLIENTCONV(CLICONVERT)
                   INTO(DSV-RESP-BODY)
                   TOLENGTH(DSV-RESP-LEN)
                   MAXLENGTH(WS-MAXLEN)
                   STATUSCODE(WS-STATUS)
                   CLOSE
                   RESP(WS-RESP)
                   RESP2(WS-RESP2)
              END-EXEC
           END-IF

           MOVE WS-RESP TO DSV-CICS-RESP
           MOVE WS-RESP2 TO DSV-CICS-RESP2
           EVALUATE TRUE
              WHEN WS-RESP = DFHRESP(NORMAL)
                 MOVE WS-STATUS TO DSV-HTTP-STATUS
              WHEN WS-RESP = DFHRESP(LENGERR)
                 MOVE WS-STATUS TO DSV-HTTP-STATUS
                 MOVE '3' TO DSV-RC
              WHEN OTHER
                 MOVE '2' TO DSV-RC
           END-EVALUATE.

       A999.
           EXEC CICS RETURN
           END-EXEC.
           GOBACK.
