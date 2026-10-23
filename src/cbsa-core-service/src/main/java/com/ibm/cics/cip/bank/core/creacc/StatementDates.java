package com.ibm.cics.cip.bank.core.creacc;

import java.time.LocalDate;

/**
 * Statement date rule R-A4, ported from CREACC {@code CALCULATE-DATES} (CD010): the next statement is due
 * 30 days after the account is opened, except when it is opened in February, where it is 28 days (29 in a
 * Gregorian leap year: divisible by 4 and not by 100, or divisible by 400).
 */
public final class StatementDates {

    static final int DAYS_TO_NEXT_STATEMENT = 30;
    static final int FEBRUARY_DAYS = 28;

    private StatementDates() {
    }

    /** CD010 EVALUATE WS-ORIG-DATE-MM: next statement date for an account opened (and last stated) on {@code opened}. */
    public static LocalDate nextStatement(LocalDate opened) {
        if (opened.getMonthValue() != 2) {
            return opened.plusDays(DAYS_TO_NEXT_STATEMENT);
        }
        return opened.plusDays(FEBRUARY_DAYS + (isGregorianLeapYear(opened.getYear()) ? 1 : 0));
    }

    /** The DIVIDE ... REMAINDER LEAP-YEAR cascade of CD010 (4 / 100 / 400). */
    static boolean isGregorianLeapYear(int year) {
        if (year % 4 != 0) {
            return false;
        }
        if (year % 100 != 0) {
            return true;
        }
        return year % 400 == 0;
    }
}
