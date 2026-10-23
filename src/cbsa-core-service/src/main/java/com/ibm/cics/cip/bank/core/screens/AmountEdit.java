package com.ibm.cics.cip.bank.core.screens;

import java.math.BigDecimal;

/**
 * VALIDATE-AMOUNT section shared (with small differences) by BNK1CRA and BNK1TFN: rejects missing,
 * non-numeric, embedded-space, multi-point and more-than-two-decimal amounts before NUMVAL.
 */
final class AmountEdit {

    static final String MUST_BE_NUMERIC = "The Amount entered must be numeric.";
    static final String NUMERIC = "Please supply a numeric amount.";
    static final String ONE_POINT = "Use one decimal point for amount only.";
    static final String TWO_DP = "Only up to two decimal places are supported.";

    /** Either a parsed amount or the MESSAGEO text. */
    record Result(BigDecimal amount, String error) {
    }

    private AmountEdit() {
    }

    static Result validate(String raw, String embeddedSpaceMessage) {
        if (raw.isEmpty()) {
            return new Result(null, MUST_BE_NUMERIC);
        }
        if (ScreenFormat.isNumeric(raw)) {
            return new Result(new BigDecimal(raw), null);
        }
        String t = raw.strip();
        if (t.isEmpty()) {
            return new Result(null, MUST_BE_NUMERIC);
        }
        if (t.indexOf(' ') >= 0) {
            return new Result(null, embeddedSpaceMessage);
        }
        for (char c : t.toCharArray()) {
            if ((c < '0' || c > '9') && c != '.') {
                return new Result(null, NUMERIC);
            }
        }
        long points = t.chars().filter(c -> c == '.').count();
        if (points > 1) {
            return new Result(null, ONE_POINT);
        }
        if (points == 1 && t.length() - t.indexOf('.') - 1 > 2) {
            return new Result(null, TWO_DP);
        }
        BigDecimal v = ScreenFormat.numval(t);
        return v == null ? new Result(null, NUMERIC) : new Result(v, null);
    }
}
