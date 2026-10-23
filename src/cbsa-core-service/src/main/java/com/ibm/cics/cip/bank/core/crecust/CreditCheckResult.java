package com.ibm.cics.cip.bank.core.crecust;

import java.time.LocalDate;
import java.util.List;

/**
 * Outcome of CRECUST {@code CREDIT-CHECK}: {@code COMM-CREDIT-SCORE}, {@code COMM-CS-REVIEW-DATE}, and on
 * failure the COMMAREA fail code (score 0 and review date = today, as the legacy STRING of WS-ORIG-DATE does).
 */
public record CreditCheckResult(int score, LocalDate reviewDate, boolean ok, String failCode,
        List<AgencyAnswer> answers) {

    /** No agency answered in time (legacy {@code WS-CREDIT-CHECK-ERROR = 'Y'}, fail code G). */
    public static final String FAIL_NO_ANSWER = "G";
    /** An agency task abended (legacy FETCH ANY {@code COMPSTATUS = ABEND}, fail code F). */
    public static final String FAIL_AGENCY_ABEND = "F";

    public CreditCheckResult {
        answers = answers == null ? List.of() : List.copyOf(answers);
    }

    static CreditCheckResult success(int score, LocalDate reviewDate, List<AgencyAnswer> answers) {
        return new CreditCheckResult(score, reviewDate, true, null, answers);
    }

    static CreditCheckResult failure(String failCode, LocalDate today, List<AgencyAnswer> answers) {
        return new CreditCheckResult(0, today, false, failCode, answers);
    }
}
