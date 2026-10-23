package com.ibm.cics.cip.bank.core.screens;

import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.deedit;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.zeros;

import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.screens.port.ScreenPorts;

/**
 * BNK1DAC: display / delete account (map BNK1DA, mapset BNK1DAM, transaction ODAC). ENTER: EDIT-DATA ->
 * GET-ACC-DATA (LINK INQACC); PF5: VALIDATE-DATA -> DEL-ACC-DATA (LINK DELACC).
 */
@Component
public class DisplayAccountScreen extends AbstractScreen {

    public static final String MSG_ACCNO = "Please enter an account number.";
    public static final String MSG_NOT_FOUND = "Sorry, but that account number was not found.";
    public static final String MSG_DELETE_PROMPT = "If you wish to delete the Account press <PF5>.";

    private final ScreenPorts ports;

    public DisplayAccountScreen(ScreenPorts ports) {
        this.ports = ports;
    }

    @Override
    public BmsMap map() {
        return BmsMap.BNK1DA;
    }

    @Override
    protected boolean cursor() {
        return false;
    }

    @Override
    protected Set<AidKey> processKeys() {
        return Set.of(AidKey.ENTER, AidKey.PF5);
    }

    @Override
    protected void processMap(ScreenInput in, ScreenOutput.Builder out) {
        if (in.aid() == AidKey.ENTER) {
            if (in.missing("ACCNO") || !ScreenFormat.isNumeric(deedit(in.field("ACCNO"), 8))) {
                out.message(MSG_ACCNO);
                return;
            }
            String accno = deedit(in.field("ACCNO"), 8);
            out.set("ACCNO", accno);
            getAccData(Long.parseLong(accno), out);
        } else if (validateData(in, out)) {
            delAccData(in, out);
        }
    }

    /** GET-ACC-DATA. */
    void getAccData(long accno, ScreenOutput.Builder out) {
        Optional<Account> found;
        try {
            found = ports.accounts().inquireAccount(accno);
        } catch (CbsaAbendException e) {
            throw e;
        } catch (CbsaException e) {
            found = Optional.empty();
        }
        if (found.isEmpty()) {
            out.message(MSG_NOT_FOUND);
            AccountFields.clear(out);
            out.state("SCODE", "").state("ACCNO", "00000000");
            return;
        }
        Account a = found.get();
        AccountFields.show(a, out);
        out.state("SCODE", a.sortCode()).state("ACCNO", zeros(a.accountNumber(), 8));
        out.message(MSG_DELETE_PROMPT);
    }

    /** VALIDATE-DATA: a successful lookup must have stored COMM-SCODE / COMM-ACCNO. */
    boolean validateData(ScreenInput in, ScreenOutput.Builder out) {
        if (zeroOrBlank(in.state("SCODE")) || zeroOrBlank(in.state("ACCNO"))) {
            out.message(MSG_ACCNO);
            return false;
        }
        return true;
    }

    private static boolean zeroOrBlank(String v) {
        return v.isBlank() || v.chars().allMatch(c -> c == '0');
    }

    /** DEL-ACC-DATA: NUMVAL(ACCNO2I) -> LINK DELACC. */
    void delAccData(ScreenInput in, ScreenOutput.Builder out) {
        String text = in.missing("ACCNO2") ? in.state("ACCNO") : in.field("ACCNO2");
        java.math.BigDecimal n = ScreenFormat.numval(text);
        long accno = n == null ? 0L : n.longValue();
        try {
            ports.accounts().deleteAccount(accno);
        } catch (CbsaAbendException e) {
            throw e;
        } catch (CbsaException e) {
            out.message(switch (failCode(e)) {
                case "1" -> MSG_NOT_FOUND;
                case "2" -> "Sorry, but a datastore error occurred.";
                default -> "Sorry, but a delete error occurred.";
            });
            out.set("SORTC", in.state("SCODE"));
            return;
        }
        AccountFields.clear(out);
        out.message("Account " + zeros(accno, 8) + " was successfully deleted.");
    }
}
