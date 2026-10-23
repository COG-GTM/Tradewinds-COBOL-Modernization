package com.ibm.cics.cip.bank.core.screens;

/**
 * Outcome of a BNKMENU selection (rule R-S1).
 *
 * @param kind    what the menu does next
 * @param transid transaction started ({@code RETURN TRANSID(...) IMMEDIATE}) when {@code kind == START}
 * @param message text sent to the terminal (MESSAGEO or SEND TEXT), if any
 */
public record ScreenTransition(Kind kind, String transid, String message) {

    public enum Kind {
        /** EIBCALEN = 0: send the menu map with ERASE. */
        DISPLAY_MENU,
        /** Valid option: start the BNK1* transaction. */
        START,
        /** Invalid option or invalid key: redisplay the menu with a message and alarm. */
        REDISPLAY,
        /** PF3 / PF12: 'Session Ended' and RETURN without TRANSID. */
        END_SESSION,
        /** CLEAR: SEND CONTROL ERASE FREEKB and RETURN. */
        CLEAR,
        /** PA1/PA2/PA3: no action (CONTINUE). */
        IGNORE
    }

    public static ScreenTransition start(String transid) {
        return new ScreenTransition(Kind.START, transid, null);
    }

    public static ScreenTransition of(Kind kind, String message) {
        return new ScreenTransition(kind, null, message);
    }
}
