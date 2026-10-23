package com.ibm.cics.cip.bank.core.abndproc;

/**
 * One ABNDFILE record: copybook {@code ABNDINFO.cpy}, the COMMAREA ABNDPROC receives and writes unchanged.
 * Key = {@code ABND-VSAM-KEY} ({@code utimeKey}, {@code taskNoKey}).
 */
public record AbendRecord(
        long utimeKey,
        int taskNoKey,
        String applid,
        String tranid,
        String date,
        String time,
        String code,
        String program,
        int respCode,
        int resp2Code,
        int sqlCode,
        String freeform) {

    static final long UTIME_MODULUS = 1_000_000_000_000_000L;
    static final int TASKNO_MODULUS = 10_000;
    static final int S9_8_MODULUS = 100_000_000;

    /**
     * The record as MOVEd into {@code WS-ABND-AREA}: S9(15) COMP-3 / 9(4) / S9(8) keep their low-order digits (9(4)
     * is unsigned), PIC X fields are truncated to their width and padded (trailing blanks not represented).
     */
    AbendRecord toCopybook() {
        return new AbendRecord(utimeKey % UTIME_MODULUS, (int) (Math.abs((long) taskNoKey) % TASKNO_MODULUS),
                x(applid, 8), x(tranid, 4), x(date, 10), x(time, 8), x(code, 4), x(program, 8),
                respCode % S9_8_MODULUS, resp2Code % S9_8_MODULUS, sqlCode % S9_8_MODULUS, x(freeform, 600));
    }

    private static String x(String v, int width) {
        String s = v == null ? "" : v;
        return (s.length() > width ? s.substring(0, width) : s).stripTrailing();
    }
}
