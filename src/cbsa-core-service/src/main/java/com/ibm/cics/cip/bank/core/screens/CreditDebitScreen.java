package com.ibm.cics.cip.bank.core.screens;

import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.deedit;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.money;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.zeros;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.screens.port.ScreenPorts;

/**
 * BNK1CRA: teller credit/debit (map BNK1CD, mapset BNK1CDM, transaction OCRA). PROCESS-MAP -> EDIT-DATA /
 * VALIDATE-AMOUNT -> UPD-CRED-DATA (LINK DBCRFUN with the terminal origin, i.e. not a payment).
 */
@Component
public class CreditDebitScreen extends AbstractScreen {

    public static final String MSG_ACCNO = "Please enter an account number.";
    public static final String MSG_ACCNO_ZERO = "Please enter a non zero account number.";
    public static final String MSG_SIGN = "Please enter + or - preceding the amount";
    public static final String MSG_EMBEDDED = "Please supply a numeric amount without embedded";
    public static final String MSG_ZERO = "Please supply a non-zero amount.";
    public static final String MSG_APPLIED = "Amount successfully applied to the account.";

    private final ScreenPorts ports;
    private final BankIdentity bank;

    public CreditDebitScreen(ScreenPorts ports, BankIdentity bank) {
        this.ports = ports;
        this.bank = bank;
    }

    @Override
    public BmsMap map() {
        return BmsMap.BNK1CD;
    }

    @Override
    protected boolean cursor() {
        return false;
    }

    @Override
    protected void processMap(ScreenInput in, ScreenOutput.Builder out) {
        BigDecimal amount = editData(in, out);
        if (amount != null) {
            updCredData(Long.parseLong(deedit(in.field("ACCNO"), 8)), "-".equals(in.field("SIGN")) ? amount.negate()
                    : amount, out);
        }
    }

    /** EDIT-DATA + VALIDATE-AMOUNT; null (message set) when invalid. */
    BigDecimal editData(ScreenInput in, ScreenOutput.Builder out) {
        if (in.missing("ACCNO")) {
            out.message(MSG_ACCNO);
            return null;
        }
        String accno = deedit(in.field("ACCNO"), 8);
        if (Long.parseLong(accno) == 0L) {
            out.message(MSG_ACCNO_ZERO);
            return null;
        }
        String sign = in.field("SIGN");
        if (sign.length() == 1 && !sign.equals("+") && !sign.equals("-")) {
            out.message(MSG_SIGN);
            return null;
        }
        AmountEdit.Result r = AmountEdit.validate(in.field("AMT"), MSG_EMBEDDED);
        if (r.error() != null) {
            out.message(r.error());
            return null;
        }
        if (r.amount().signum() == 0) {
            out.message(MSG_ZERO);
            return null;
        }
        return r.amount();
    }

    /** UPD-CRED-DATA. */
    void updCredData(long accno, BigDecimal amount, ScreenOutput.Builder out) {
        try {
            Account a = ports.payments().debitCredit(accno, amount, false, "");
            out.message(MSG_APPLIED)
                    .set("ACCNO", zeros(a.accountNumber(), 8))
                    .set("SORTC", a.sortCode())
                    .set("ACTBAL", money(a.actualBalance()))
                    .set("AVBAL", money(a.availableBalance()));
        } catch (CbsaException e) {
            String fc = failCode(e);
            out.message(switch (fc) {
                case "1" -> "Sorry but the ACCOUNT no was not found for SORTCODE " + bank.sortCode()
                        + " . Amount not applied.";
                case "2" -> "Sorry but the AMOUNT could not be applied due to an unexpected error.";
                case "3" -> "Sorry insufficient funds available to process the request.";
                default -> "Sorry but the AMOUNT could not be applied due to an unexpected error. " + fc;
            });
            out.set("ACCNO", zeros(accno, 8)).set("SORTC", bank.sortCode())
                    .set("ACTBAL", money(BigDecimal.ZERO)).set("AVBAL", money(BigDecimal.ZERO));
        }
    }
}
