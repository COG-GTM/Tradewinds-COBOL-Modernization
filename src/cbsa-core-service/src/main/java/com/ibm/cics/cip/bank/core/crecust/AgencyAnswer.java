package com.ibm.cics.cip.bank.core.crecust;

/** What CRDTAGY{agency} PUTs back into its container: the score, plus the delay it waited (seconds). */
public record AgencyAnswer(int agency, String programName, int delaySeconds, int score) {
}
