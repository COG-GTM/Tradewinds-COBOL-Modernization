package com.ibm.cics.cip.bank.core.screens;

import java.util.Set;

import com.ibm.cics.cip.bank.core.common.error.CbsaException;

/**
 * The PREMIERE section shared by every BNK1* program:
 *
 * <pre>
 * EVALUATE TRUE
 *    WHEN EIBCALEN = ZERO          -> first display (SEND MAP ERASE)
 *    WHEN EIBAID = DFHPA1/PA2/PA3  -> CONTINUE
 *    WHEN EIBAID = DFHPF3          -> RETURN TRANSID('OMEN') IMMEDIATE
 *    WHEN EIBAID = DFHAID / DFHPF12 -> SEND TEXT 'Session Ended', RETURN
 *    WHEN EIBAID = DFHCLEAR        -> SEND CONTROL ERASE FREEKB, RETURN
 *    WHEN processing key           -> PROCESS-MAP
 *    WHEN OTHER                    -> 'Invalid key pressed.' (DATAONLY ALARM)
 * END-EVALUATE
 * RETURN TRANSID(own) COMMAREA(...)
 * </pre>
 */
public abstract class AbstractScreen implements ScreenHandler {

    public static final String INVALID_KEY = "Invalid key pressed.";
    public static final String END_OF_SESSION = "Session Ended";

    /** Whether SEND MAP carries CURSOR in this program's SEND-MAP section. */
    protected abstract boolean cursor();

    /** AID keys that PERFORM PROCESS-MAP (always includes ENTER). */
    protected Set<AidKey> processKeys() {
        return Set.of(AidKey.ENTER);
    }

    /** Output fields MOVEd SPACES to before the first SEND MAP ERASE. */
    protected String[] firstDisplaySpaces() {
        return new String[0];
    }

    /** PROCESS-MAP: RECEIVE-MAP has already been applied to {@code out}. */
    protected abstract void processMap(ScreenInput in, ScreenOutput.Builder out);

    @Override
    public ScreenOutput handle(ScreenInput in) {
        ScreenOutput.Builder out = ScreenOutput.builder(map());
        AidKey aid = in.aid();
        if (in.firstTime()) {
            out.spaces(firstDisplaySpaces());
            return out.send(ScreenOutput.SendMode.ERASE, cursor()).returnToSelf().build();
        }
        if (aid.isPa()) {
            return out.state(in.commarea()).returnToSelf().build();
        }
        if (aid == AidKey.PF3) {
            return out.returnTransid("OMEN", true, 0).build();
        }
        if (aid == AidKey.PF12) {
            return out.sendText(END_OF_SESSION).build();
        }
        if (aid == AidKey.CLEAR) {
            return out.sendControlErase().build();
        }
        out.state(in.commarea());
        if (processKeys().contains(aid)) {
            out.receive(in);
            processMap(in, out);
            out.send(ScreenOutput.SendMode.DATAONLY_ALARM, cursor());
        } else {
            out.message(INVALID_KEY).send(ScreenOutput.SendMode.DATAONLY_ALARM, cursor());
        }
        return out.returnToSelf().build();
    }

    /** COMMAREA fail code reported by a LINKed business service, or blank. */
    protected static String failCode(CbsaException e) {
        return e.failCode() == null ? "" : e.failCode();
    }
}
