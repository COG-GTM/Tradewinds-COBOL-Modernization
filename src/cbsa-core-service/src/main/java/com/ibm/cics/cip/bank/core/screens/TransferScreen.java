package com.ibm.cics.cip.bank.core.screens;

import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.deedit;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.money;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.zeros;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.screens.port.PaymentScreenPort;
import com.ibm.cics.cip.bank.core.screens.port.ScreenPorts;

/**
 * BNK1TFN: transfer funds (map BNK1TF, mapset BNK1TFM, transaction OTFN). PROCESS-MAP -> EDIT-DATA /
 * VALIDATE-AMOUNT -> GET-ACC-DATA (LINK XFRFUN).
 */
@Component
public class TransferScreen extends AbstractScreen {

    public static final String MSG_FROM = "Please enter a FROM account no";
    public static final String MSG_TO = "Please enter a TO account no";
    public static final String MSG_SAME = "The FROM & TO account should be different";
    public static final String MSG_ZERO_ACCOUNT = "Account no 00000000 is not valid";
    public static final String MSG_POSITIVE = "Please supply a positive amount.";
    public static final String MSG_EMBEDDED = "Please supply a numeric amount without embedded  spaces.";
    public static final String MSG_APPLIED = "Transfer successfully applied.";

    private final ScreenPorts ports;

    public TransferScreen(ScreenPorts ports) {
        this.ports = ports;
    }

    @Override
    public BmsMap map() {
        return BmsMap.BNK1TF;
    }

    @Override
    protected boolean cursor() {
        return false;
    }

    @Override
    protected void processMap(ScreenInput in, ScreenOutput.Builder out) {
        BigDecimal amount = editData(in, out);
        if (amount != null) {
            getAccData(Long.parseLong(deedit(in.field("FACCNO"), 8)), Long.parseLong(deedit(in.field("TACCNO"), 8)),
                    amount, out);
        }
    }

    /** EDIT-DATA + VALIDATE-AMOUNT; null (message set) when invalid. */
    BigDecimal editData(ScreenInput in, ScreenOutput.Builder out) {
        if (in.missing("FACCNO")) {
            out.message(MSG_FROM);
            return null;
        }
        if (in.missing("TACCNO")) {
            out.message(MSG_TO);
            return null;
        }
        String from = deedit(in.field("FACCNO"), 8);
        String to = deedit(in.field("TACCNO"), 8);
        if (from.equals(to)) {
            out.message(MSG_SAME);
            return null;
        }
        if (from.equals("00000000") || to.equals("00000000")) {
            out.message(MSG_ZERO_ACCOUNT);
            return null;
        }
        return validateAmount(in.field("AMT"), out);
    }

    /** VALIDATE-AMOUNT (BNK1TFN variant: the amount must be positive). */
    static BigDecimal validateAmount(String raw, ScreenOutput.Builder out) {
        if (!raw.isEmpty() && !raw.isBlank() && raw.indexOf('-') >= 0) {
            out.message(MSG_POSITIVE);
            return null;
        }
        AmountEdit.Result r = AmountEdit.validate(raw, MSG_EMBEDDED);
        if (r.error() != null) {
            out.message(r.error());
            return null;
        }
        if (r.amount().signum() <= 0) {
            out.message(MSG_POSITIVE);
            return null;
        }
        return r.amount();
    }

    /** GET-ACC-DATA: LINK XFRFUN. */
    void getAccData(long from, long to, BigDecimal amount, ScreenOutput.Builder out) {
        out.set("FACCNO2", zeros(from, 8)).set("TACCNO2", zeros(to, 8))
                .set("FSORTC", "000000").set("TSORTC", "000000");
        String zero = money(BigDecimal.ZERO);
        out.set("FACTBAL", zero).set("FAVBAL", zero).set("TACTBAL", zero).set("TAVBAL", zero);
        PaymentScreenPort.Transfer t;
        try {
            t = ports.payments().transfer(from, to, amount);
        } catch (CbsaException e) {
            out.message(switch (failCode(e)) {
                case "1" -> "Sorry the FROM ACCOUNT no was not found. Transfer not applied.";
                case "2" -> "Sorry the TO ACCOUNT no was not found. Transfer not applied.";
                case "3" -> "Sorry but the transfer could not be applied due to an unexpected error.";
                case "4" -> "Please supply an amount greater than zero.";
                default -> "Sorry but the transfer could not be applied due to an error.";
            });
            return;
        }
        out.message(MSG_APPLIED)
                .set("FACCNO2", zeros(t.from().accountNumber(), 8))
                .set("FSORTC", t.from().sortCode())
                .set("TACCNO2", zeros(t.to().accountNumber(), 8))
                .set("TSORTC", t.to().sortCode())
                .set("FACTBAL", money(t.from().actualBalance()))
                .set("FAVBAL", money(t.from().availableBalance()))
                .set("TACTBAL", money(t.to().actualBalance()))
                .set("TAVBAL", money(t.to().availableBalance()));
    }
}
