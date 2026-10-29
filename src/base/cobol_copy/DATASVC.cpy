      ******************************************************************
      *                                                                *
      *  Copyright IBM Corp. 2023                                      *
      *                                                                *
      ******************************************************************
      * COMMAREA for DATASVC: one HTTP call to the CBSA Java data
      * services (Liberty, /banking/data/...) that replace the former
      * Db2 EXEC SQL. Bodies are JSON, EBCDIC on this side (CICS
      * converts to/from UTF-8).
      *
      * Caller sets: DSV-METHOD, DSV-PATH (after /banking, e.g.
      *   '/data/account/987654/00000001'), DSV-REQ-LEN/DSV-REQ-BODY
      *   (0 for GET/DELETE).
      * DATASVC returns: DSV-HTTP-STATUS, DSV-RESP-LEN/DSV-RESP-BODY,
      *   DSV-RC ('0' HTTP exchange done, any status;
      *           '1' WEB OPEN failed; '2' WEB CONVERSE failed;
      *           '3' response truncated) and the CICS RESP/RESP2.
          03 DSV-EYE                  PIC X(4)  VALUE 'DSVC'.
          03 DSV-METHOD               PIC X(6).
             88 DSV-GET                VALUE 'GET'.
             88 DSV-POST               VALUE 'POST'.
             88 DSV-PUT                VALUE 'PUT'.
             88 DSV-DELETE             VALUE 'DELETE'.
          03 DSV-PATH                 PIC X(200).
          03 DSV-RC                   PIC X.
             88 DSV-OK                 VALUE '0'.
          03 DSV-HTTP-STATUS          PIC 9(3).
          03 DSV-CICS-RESP            PIC S9(8) COMP.
          03 DSV-CICS-RESP2           PIC S9(8) COMP.
          03 DSV-REQ-LEN              PIC S9(8) COMP.
          03 DSV-RESP-LEN             PIC S9(8) COMP.
          03 DSV-REQ-BODY             PIC X(4096).
          03 DSV-RESP-BODY            PIC X(16000).
