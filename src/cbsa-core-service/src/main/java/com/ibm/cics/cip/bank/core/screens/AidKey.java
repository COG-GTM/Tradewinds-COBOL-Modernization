package com.ibm.cics.cip.bank.core.screens;

/** CICS attention identifiers (EIBAID / DFHAID) recognised by the BNKMENU and BNK1* programs. */
public enum AidKey {
    ENTER, CLEAR, PA1, PA2, PA3,
    PF1, PF2, PF3, PF4, PF5, PF6, PF7, PF8, PF9, PF10, PF11, PF12,
    PF13, PF14, PF15, PF16, PF17, PF18, PF19, PF20, PF21, PF22, PF23, PF24;

    public boolean isPa() {
        return this == PA1 || this == PA2 || this == PA3;
    }

    /** Parses names such as {@code ENTER}, {@code PF3}, {@code pf12}, {@code CLEAR}. */
    public static AidKey parse(String value) {
        if (value == null || value.isBlank()) {
            return ENTER;
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
