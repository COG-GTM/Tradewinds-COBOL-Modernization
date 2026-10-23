package com.ibm.cics.cip.bank.core.screens;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * BNKMENU: main menu (map BNK1ME, mapset BNK1MAI). Implements rule R-S1.
 *
 * <ul>
 * <li>{@code PREMIERE}: AID dispatch (EIBCALEN=0, PA keys, PF3/PF12, CLEAR, ENTER, other).</li>
 * <li>{@code PROCESS-MENU-MAP} / {@code EDIT-MENU-DATA}: ACTION must be 1-7 or A.</li>
 * <li>{@code INVOKE-OTHER-TXNS}: {@code RETURN TRANSID(x) IMMEDIATE} for the selected option.</li>
 * <li>{@code SEND-MAP}, {@code SEND-TERMINATION-MSG}.</li>
 * </ul>
 */
@Component
public class MenuRouter {

    public static final String INVALID_OPTION = "You must enter a valid value (1-7 or A).";
    public static final String INVALID_KEY = "Invalid key pressed.";
    public static final String END_OF_SESSION = "Session Ended";

    /** INVOKE-OTHER-TXNS: ACTIONI -> transaction id. */
    public static final Map<String, String> OPTIONS = Map.of(
            "1", "ODCS",
            "2", "ODAC",
            "3", "OCCS",
            "4", "OCAC",
            "5", "OUAC",
            "6", "OCRA",
            "7", "OTFN",
            "A", "OCCA");

    /** PREMIERE + EDIT-MENU-DATA for a turn with a COMMAREA (EIBCALEN not zero). */
    public ScreenTransition route(String option, AidKey key) {
        AidKey aid = key == null ? AidKey.ENTER : key;
        if (aid.isPa()) {
            return ScreenTransition.of(ScreenTransition.Kind.IGNORE, null);
        }
        if (aid == AidKey.PF3 || aid == AidKey.PF12) {
            return ScreenTransition.of(ScreenTransition.Kind.END_SESSION, END_OF_SESSION);
        }
        if (aid == AidKey.CLEAR) {
            return ScreenTransition.of(ScreenTransition.Kind.CLEAR, null);
        }
        if (aid != AidKey.ENTER) {
            return ScreenTransition.of(ScreenTransition.Kind.REDISPLAY, INVALID_KEY);
        }
        // EDIT-MENU-DATA: ACTIONI is a single, case-sensitive character
        String transid = option == null ? null : OPTIONS.get(option);
        if (transid == null) {
            return ScreenTransition.of(ScreenTransition.Kind.REDISPLAY, INVALID_OPTION);
        }
        return ScreenTransition.start(transid);
    }

    /** Full BNKMENU turn including the terminal I/O reproduced by the legacy harness. */
    public ScreenOutput handle(ScreenInput in) {
        ScreenOutput.Builder out = ScreenOutput.builder(BmsMap.BNK1ME);
        if (in.firstTime()) {
            return out.send(ScreenOutput.SendMode.ERASE, false).returnToSelf().build();
        }
        boolean enter = in.aid() == AidKey.ENTER;
        if (enter) {
            out.receive(in);
        }
        ScreenTransition t = route(enter ? in.field("ACTION") : null, in.aid());
        switch (t.kind()) {
            case START:
                return out.returnTransid(t.transid(), true, 0).build();
            case END_SESSION:
                return out.sendText(t.message()).build();
            case CLEAR:
                return out.sendControlErase().build();
            case REDISPLAY:
                if (!enter) {
                    out.spaces("ACTION");
                }
                out.message(t.message()).send(ScreenOutput.SendMode.DATAONLY_ALARM, false);
                break;
            default:
                break;
        }
        return out.returnToSelf().build();
    }
}
