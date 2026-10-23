package com.ibm.cics.cip.bank.core.screens;

import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.dd;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.deedit;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.mm;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.money;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.numval;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.rate;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.yyyy;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.zeros;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.screens.port.ScreenPorts;

/**
 * BNK1CAC: create account (map BNK1CA, mapset BNK1CAM, transaction OCAC). PROCESS-MAP -> EDIT-DATA ->
 * CRE-ACC-DATA (LINK CREACC).
 */
@Component
public class CreateAccountScreen extends AbstractScreen {

    public static final String MSG_ENTER_CUSTNO = "Please enter a 10 digit Customer Number";
    public static final String MSG_NUMERIC_CUSTNO = "Please enter a numeric Customer number";
    public static final String MSG_ACCOUNT_TYPE = "Account Type should be ISA,CURRENT,LOAN,SAVING or MORTGAGE";
    public static final String MSG_NUMERIC_RATE = "Please supply a numeric interest rate";
    public static final String MSG_ONE_POINT = "Use one decimal point for interest rate only";
    public static final String MSG_TWO_DP = "Only up to two decimal places are supported";
    public static final String MSG_NEGATIVE_RATE = "Please supply a zero or positive interest rate";
    public static final String MSG_RATE_TOO_HIGH = "Please supply an interest rate less than 9999.99%";
    public static final String MSG_OVERDRAFT = "Overdraft Limit must be numeric positive int";
    public static final String MSG_CREATED = "The Account has been successfully created";

    static final List<String> TYPES = List.of("ISA", "CURRENT", "LOAN", "SAVING", "MORTGAGE");

    private final ScreenPorts ports;

    public CreateAccountScreen(ScreenPorts ports) {
        this.ports = ports;
    }

    @Override
    public BmsMap map() {
        return BmsMap.BNK1CA;
    }

    @Override
    protected boolean cursor() {
        return true;
    }

    @Override
    protected String[] firstDisplaySpaces() {
        return new String[] {"MESSAGE"};
    }

    /** Validated EDIT-DATA result. */
    record Request(long customerNumber, String type, BigDecimal rate, int overdraft) {
    }

    @Override
    protected void processMap(ScreenInput in, ScreenOutput.Builder out) {
        Request req = editData(in, out);
        if (req != null) {
            creAccData(req, out);
        }
    }

    /** EDIT-DATA; returns null (message set) when invalid. */
    Request editData(ScreenInput in, ScreenOutput.Builder out) {
        String custIn = in.field("CUSTNO");
        if (custIn.isEmpty() || custIn.equals("__________")) {
            out.message(MSG_ENTER_CUSTNO);
            return null;
        }
        String custno = deedit(custIn, 10);
        out.set("CUSTNO", custno);

        String typeIn = in.field("ACCTYP");
        if (typeIn.isEmpty() || typeIn.equals("________")) {
            out.message(MSG_ACCOUNT_TYPE);
            return null;
        }
        String type = normaliseType(typeIn);
        if (type == null) {
            out.message(MSG_ACCOUNT_TYPE);
            return null;
        }
        out.set("ACCTYP", type);

        String rateIn = in.field("INTRT");
        String rateError = checkRate(rateIn);
        if (rateError != null) {
            out.message(rateError);
            return null;
        }
        BigDecimal rate = numval(rateIn);
        if (rate == null) {
            out.message(MSG_NUMERIC_RATE);
            return null;
        }
        if (rate.signum() < 0) {
            out.message(MSG_NEGATIVE_RATE);
            return null;
        }
        if (rate.compareTo(new BigDecimal("9999.99")) > 0) {
            out.message(MSG_RATE_TOO_HIGH);
            return null;
        }

        String od = ScreenOutput.rtrim(in.field("OVERDR").replace('_', ' '));
        for (char c : od.toCharArray()) {
            if (c < '0' || c > '9') {
                out.message(MSG_OVERDRAFT);
                return null;
            }
        }
        int overdraft = od.isEmpty() ? 0 : Integer.parseInt(deedit(od, 8));
        return new Request(Long.parseLong(custno), type, rate, overdraft);
    }

    /** ACCTYPI EVALUATE: all-upper or all-lower (with '_' fill) forms of the five supported types. */
    static String normaliseType(String raw) {
        String t = ScreenOutput.rtrim(raw.replace('_', ' '));
        for (String type : TYPES) {
            if (t.equals(type) || t.equals(type.toLowerCase(java.util.Locale.ROOT))) {
                return type;
            }
        }
        return null;
    }

    /** INTRT checks (INSPECT TALLYING) before NUMVAL; null when acceptable. */
    static String checkRate(String raw) {
        if (raw.isEmpty()) {
            return MSG_NUMERIC_RATE;
        }
        if (!ScreenFormat.isNumeric(raw)) {
            for (char c : raw.toCharArray()) {
                if ("0123456789.-+ ".indexOf(c) < 0) {
                    return MSG_NUMERIC_RATE;
                }
            }
            long points = raw.chars().filter(c -> c == '.').count();
            if (points > 1) {
                return MSG_ONE_POINT;
            }
            if (points == 1) {
                String after = raw.substring(raw.indexOf('.') + 1);
                if (after.length() > 2 && after.chars().filter(c -> "0123456789-+".indexOf(c) >= 0).count() > 2) {
                    return MSG_TWO_DP;
                }
            }
        }
        return null;
    }

    /** CRE-ACC-DATA: LINK CREACC and map SUBPGM-FAIL-CODE to the message. */
    void creAccData(Request req, ScreenOutput.Builder out) {
        try {
            Account a = ports.accounts().createAccount(req.customerNumber(), req.type(), req.rate(),
                    req.overdraft(), BigDecimal.ZERO, BigDecimal.ZERO);
            out.message(MSG_CREATED)
                    .set("SRTCD", a.sortCode())
                    .set("ACCNO", zeros(a.accountNumber(), 8))
                    .set("OPENDD", dd(a.opened())).set("OPENMM", mm(a.opened())).set("OPENYY", yyyy(a.opened()))
                    .set("NSTMTDD", dd(a.nextStatement())).set("NSTMTMM", mm(a.nextStatement()))
                    .set("NSTMTYY", yyyy(a.nextStatement()))
                    .set("LSTMDD", dd(a.lastStatement())).set("LSTMMM", mm(a.lastStatement()))
                    .set("LSTMYY", yyyy(a.lastStatement()))
                    .set("AVAIL", money(a.availableBalance()))
                    .set("ACTBAL", money(a.actualBalance()));
            out.set("CUSTNO", zeros(a.customerNumber(), 10)).set("ACCTYP", a.accountType())
                    .set("INTRT", rate(a.interestRate())).set("OVERDR", zeros(a.overdraftLimit(), 8));
        } catch (CbsaAbendException e) {
            throw e;
        } catch (CbsaException e) {
            out.message(failMessage(failCode(e)));
            out.set("CUSTNO", zeros(req.customerNumber(), 10)).set("ACCTYP", req.type())
                    .set("INTRT", rate(req.rate())).set("OVERDR", zeros(req.overdraft(), 8));
        }
    }

    static String failMessage(String failCode) {
        return switch (failCode) {
            case "1" -> "The supplied customer number does not exist.";
            case "2" -> "The customer data cannot be accessed,  unable to create account.";
            case "3" -> "Account record creation failed. (unable to ENQ ACCOUNT NC).";
            case "4" -> "Account record creation failed, (unable to increment ACCOUNT NC).";
            case "5" -> "Account record creation failed, (unable to restore ACCOUNT NC).";
            case "6" -> "Account record creation failed, (unable to WRITE to ACCOUNT file).";
            case "7" -> "Account record creation failed, (unable to INSERT into ACCOUNT).";
            case "8" -> "Account record creation failed, (too many accounts).";
            case "9" -> "Account record creation failed,  unable to count accounts.";
            case "A" -> "Account record creation failed,  account type unsupported.";
            default -> "The account was not created.";
        };
    }
}
