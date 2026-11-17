package com.tradewinds.tradedesk.config;

/**
 * COBOL MOVE semantics for the fixed-width fields used by DBCRFUN.
 */
public final class CobolText {

    private CobolText() {
    }

    /**
     * MOVE to a PIC X(n) field: left-justified, space-filled on the right and
     * truncated on the right when the source is longer (e.g. MOVE '02' TO PIC X gives '0').
     */
    public static String moveToPicX(String value, int length) {
        String source = value == null ? "" : value;
        if (source.length() >= length) {
            return source.substring(0, length);
        }
        return source + " ".repeat(length - source.length());
    }

    /**
     * MOVE an unsigned integer string to a PIC 9(n) field: right-justified,
     * zero-filled on the left and truncated on the left (high-order digits).
     */
    public static String moveToPic9(String digits, int length) {
        String source = digits == null ? "" : digits;
        if (source.length() >= length) {
            return source.substring(source.length() - length);
        }
        return "0".repeat(length - source.length()) + source;
    }
}
