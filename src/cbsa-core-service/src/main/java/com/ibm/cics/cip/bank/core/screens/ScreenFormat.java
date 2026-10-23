package com.ibm.cics.cip.bank.core.screens;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** Numeric-edited picture formatting used by the BNK1* SEND-MAP paragraphs. */
public final class ScreenFormat {

    private ScreenFormat() {
    }

    /** Display of PIC S9(10)V99 with leading sign, e.g. {@code +0000623395.58}. */
    public static String money(BigDecimal amount) {
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.DOWN);
        String digits = v.abs().movePointRight(2).toBigInteger().toString();
        digits = "0".repeat(Math.max(0, 12 - digits.length())) + digits;
        digits = digits.substring(digits.length() - 12);
        return (v.signum() < 0 ? "-" : "+") + digits.substring(0, 10) + "." + digits.substring(10);
    }

    /** Display of PIC 9(4).99, e.g. {@code 0001.25}. */
    public static String rate(BigDecimal rate) {
        BigDecimal v = rate == null ? BigDecimal.ZERO : rate.abs().setScale(2, RoundingMode.DOWN);
        String digits = v.movePointRight(2).toBigInteger().toString();
        digits = "0".repeat(Math.max(0, 6 - digits.length())) + digits;
        digits = digits.substring(digits.length() - 6);
        return digits.substring(0, 4) + "." + digits.substring(4);
    }

    /** Display of PIC 9(width). */
    public static String zeros(long value, int width) {
        String s = Long.toString(Math.abs(value));
        if (s.length() >= width) {
            return s.substring(s.length() - width);
        }
        return "0".repeat(width - s.length()) + s;
    }

    public static String dd(LocalDate d) {
        return d == null ? "" : zeros(d.getDayOfMonth(), 2);
    }

    public static String mm(LocalDate d) {
        return d == null ? "" : zeros(d.getMonthValue(), 2);
    }

    public static String yyyy(LocalDate d) {
        return d == null ? "" : zeros(d.getYear(), 4);
    }

    /** {@code EXEC CICS BIF DEEDIT}: drop every character that is not a digit, right-justify with zeros. */
    public static String deedit(String value, int width) {
        StringBuilder digits = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        String s = digits.toString();
        if (s.length() > width) {
            return s.substring(s.length() - width);
        }
        return "0".repeat(width - s.length()) + s;
    }

    /** Pads/truncates to a fixed-width alphanumeric (PIC X(n)). */
    public static String pic(String value, int width) {
        String v = value == null ? "" : value;
        if (v.length() >= width) {
            return v.substring(0, width);
        }
        return v + " ".repeat(width - v.length());
    }

    public static boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (char c : value.toCharArray()) {
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
