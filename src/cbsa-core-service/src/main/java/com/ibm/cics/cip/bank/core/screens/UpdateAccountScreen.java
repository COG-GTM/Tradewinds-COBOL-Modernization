package com.ibm.cics.cip.bank.core.screens;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.screens.port.ScreenPorts;

/**
 * BNK1UAC: update account (map BNK1UA, mapset BNK1UAM, transaction OUAC). ENTER: EDIT-DATA -> INQ-ACC-DATA
 * (LINK INQACC); PF5: VALIDATE-DATA -> UPD-ACC-DATA (LINK UPDACC).
 */
@Component
public class UpdateAccountScreen extends AbstractScreen {

    public static final String MSG_ACCNO = "Please enter an account number.";
    public static final String MSG_NOT_FOUND = "This account number could not be found";
    public static final String MSG_AMEND = "Please amend fields and hit <pf5> to apply changes";
    public static final String MSG_TYPE = "Account Type must be CURRENT, SAVING, LOAN, MORTGAGE or ISA. Then press PF5.";
    public static final String MSG_RATE_MISSING = "Please supply a numeric interest rate then press PF5.";
    public static final String MSG_RATE_NUMERIC = "Please supply a numeric interest rate";
    public static final String MSG_ZERO_RATE =
            "Interest rate cannot be 0 with this account type. Correct and press PF5.";
    public static final String MSG_OVERDRAFT = "Overdraft must be numeric. Correct and press PF5.";
    public static final String MSG_LAST_NUMERIC = "Last statement date must be numeric";
    public static final String MSG_NEXT_NUMERIC = "Next statement date must be numeric";
    public static final String MSG_LAST_DATE = "Incorrect date for LAST STATEMENT.";
    public static final String MSG_NEXT_DATE = "Incorrect date for NEXT STATEMENT.";
    public static final String MSG_UPDATE_FAILED = "Update unsuccessful, try again later.";
    public static final String MSG_UPDATED = "Account update successfully applied.";

    static final List<String> TYPES = List.of("CURRENT", "SAVING", "LOAN", "MORTGAGE", "ISA");

    private final ScreenPorts ports;

    public UpdateAccountScreen(ScreenPorts ports) {
        this.ports = ports;
    }

    @Override
    public BmsMap map() {
        return BmsMap.BNK1UA;
    }

    @Override
    protected boolean cursor() {
        return true;
    }

    @Override
    protected Set<AidKey> processKeys() {
        return Set.of(AidKey.ENTER, AidKey.PF5);
    }

    @Override
    protected void processMap(ScreenInput in, ScreenOutput.Builder out) {
        if (in.aid() == AidKey.ENTER) {
            String accno = in.field("ACCNO");
            if (accno.length() != 8 || !ScreenFormat.isNumeric(accno)) {
                out.message(MSG_ACCNO);
                return;
            }
            inqAccData(Long.parseLong(accno), out);
        } else if (validateData(in, out)) {
            updAccData(in, out);
        }
    }

    /** INQ-ACC-DATA. */
    void inqAccData(long accno, ScreenOutput.Builder out) {
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
            return;
        }
        out.message(MSG_AMEND);
        AccountFields.show(found.get(), out);
    }

    /**
     * VALIDATE-DATA. As in the COBOL, the account-type and statement-date range checks do not GO TO VD999, so a
     * later failing check overwrites their message.
     */
    boolean validateData(ScreenInput in, ScreenOutput.Builder out) {
        boolean valid = true;
        String type = in.field("ACTYPE");
        if (!TYPES.contains(ScreenOutput.rtrim(type)) || type.length() > 8) {
            out.message(MSG_TYPE);
            valid = false;
        }
        String rate = in.field("INTRT");
        if (rate.isEmpty()) {
            out.message(MSG_RATE_MISSING);
            return false;
        }
        String rateError = CreateAccountScreen.checkRate(rate);
        if (rateError != null) {
            out.message(rateError.equals(CreateAccountScreen.MSG_NUMERIC_RATE) ? MSG_RATE_NUMERIC : rateError);
            return false;
        }
        BigDecimal r = ScreenFormat.numval(rate);
        if (r == null) {
            out.message(MSG_RATE_NUMERIC);
            return false;
        }
        if (r.signum() < 0) {
            out.message(CreateAccountScreen.MSG_NEGATIVE_RATE);
            return false;
        }
        if (r.compareTo(new BigDecimal("9999.99")) > 0) {
            out.message(CreateAccountScreen.MSG_RATE_TOO_HIGH);
            return false;
        }
        String t = ScreenOutput.rtrim(type);
        if ((t.equals("LOAN") || t.equals("MORTGAGE")) && rate.equals("0000.00")) {
            out.message(MSG_ZERO_RATE);
            return false;
        }
        String od = in.field("OVERDR");
        if (od.isEmpty() || !ScreenFormat.isNumeric(od)) {
            out.message(MSG_OVERDRAFT);
            return false;
        }
        if (!numeric(in, "LSTMTDD", "LSTMTMM", "LSTMTYY")) {
            out.message(MSG_LAST_NUMERIC);
            return false;
        }
        if (!numeric(in, "NSTMTDD", "NSTMTMM", "NSTMTYY")) {
            out.message(MSG_NEXT_NUMERIC);
            return false;
        }
        int ld = Integer.parseInt(in.field("LSTMTDD"));
        int lm = Integer.parseInt(in.field("LSTMTMM"));
        if (ld > 31 || ld == 0 || lm > 12 || lm == 0 || badDayForMonth(ld, lm)) {
            out.message(MSG_LAST_DATE);
            valid = false;
        }
        if (badDayForMonth(Integer.parseInt(in.field("NSTMTDD")), Integer.parseInt(in.field("NSTMTMM")))) {
            out.message(MSG_NEXT_DATE);
            valid = false;
        }
        return valid;
    }

    private static boolean numeric(ScreenInput in, String... fields) {
        for (String f : fields) {
            if (in.missing(f) || !ScreenFormat.isNumeric(in.field(f))) {
                return false;
            }
        }
        return true;
    }

    /** 31st of a 30-day month or day > 29 in February. */
    static boolean badDayForMonth(int day, int month) {
        return (day == 31 && (month == 4 || month == 6 || month == 9 || month == 11)) || (day > 29 && month == 2);
    }

    /** UPD-ACC-DATA: ACCNOI (or ACCNO2I when ACCNOI = 99999999) -> LINK UPDACC. */
    void updAccData(ScreenInput in, ScreenOutput.Builder out) {
        String accText = in.field("ACCNO");
        if (accText.equals("99999999") || !ScreenFormat.isNumeric(accText) || accText.isEmpty()) {
            accText = in.field("ACCNO2");
        }
        long accno = ScreenFormat.isNumeric(accText) && !accText.isEmpty() ? Long.parseLong(accText) : 0L;
        try {
            Account a = ports.accounts().updateAccount(accno, ScreenOutput.rtrim(in.field("ACTYPE")),
                    ScreenFormat.numval(in.field("INTRT")).setScale(2, java.math.RoundingMode.DOWN),
                    Integer.parseInt(in.field("OVERDR")));
            out.message(MSG_UPDATED);
            AccountFields.show(a, out);
        } catch (CbsaAbendException e) {
            throw e;
        } catch (CbsaException e) {
            out.message(MSG_UPDATE_FAILED);
        }
    }
}
