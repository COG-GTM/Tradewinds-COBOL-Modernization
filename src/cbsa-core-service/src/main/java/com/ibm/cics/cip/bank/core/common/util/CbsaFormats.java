package com.ibm.cics.cip.bank.core.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Legacy fixed-width formats (PIC 9(n) DISPLAY keys, DDMMYYYY dates, HHMMSS times). */
public final class CbsaFormats {

    public static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("ddMMyyyy");
    public static final DateTimeFormatter DD_MM_YYYY_SLASH = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    public static final DateTimeFormatter DD_MM_YYYY_DOT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    public static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    private CbsaFormats() {
    }

    /** PIC 9(8) account / PROCTRAN number: zero-padded to 8. */
    public static String accountNumber(long number) {
        return String.format("%08d", number);
    }

    /** PIC 9(10) customer number: zero-padded to 10. */
    public static String customerNumber(long number) {
        return String.format("%010d", number);
    }

    /** PIC 9(6) sort code. */
    public static String sortCode(long sortCode) {
        return String.format("%06d", sortCode);
    }

    /** Parse a CHAR key column back to a number (blank -> 0). */
    public static long toLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value.trim());
    }

    /** Right-pad / truncate to a fixed CHAR(n) width, as a COBOL MOVE into PIC X(n) does. */
    public static String fixed(String value, int width) {
        String v = value == null ? "" : value;
        if (v.length() >= width) {
            return v.substring(0, width);
        }
        return v + " ".repeat(width - v.length());
    }

    /** Monetary amount with scale 2 (PIC S9(10)V99). */
    public static BigDecimal money(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.DOWN);
    }

    public static String ddmmyyyy(LocalDate date) {
        return date == null ? "00000000" : DDMMYYYY.format(date);
    }
}
