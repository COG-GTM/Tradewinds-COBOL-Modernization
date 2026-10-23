package com.ibm.cics.cip.bank.core.dbcrfun;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.math.BigDecimal;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mirror of the z/OS Connect {@code makepayment} API ({@code PUT /makepayment/dbcr}) over DBCRFUN. COMMAREA
 * semantics: always HTTP 200 with {@code CommSuccess} / {@code CommFailCode} in the body; only a malformed body is a
 * 400, and a task abend (AD2Z) a 500.
 */
@RestController
public class PaymentController {

    private static final Pattern ACCNO = Pattern.compile("\\d{1,8}");

    private final DebitCreditService service;
    private final BankIdentity bank;

    public PaymentController(DebitCreditService service, BankIdentity bank) {
        this.service = service;
        this.bank = bank;
    }

    @PutMapping("/makepayment/dbcr")
    public PayDbcr.Envelope makePayment(@RequestBody PayDbcr.Envelope request) {
        PayDbcr in = request == null ? null : request.paydbcr();
        if (in == null || in.commAmt() == null) {
            throw CbsaException.badRequest("PAYDBCR.CommAmt is required");
        }
        if (in.commAmt().abs().compareTo(DebitCreditService.MAX_MONEY) > 0) {
            throw CbsaException.badRequest("PAYDBCR.CommAmt exceeds PIC S9(10)V99");
        }
        if (in.commAmt().stripTrailingZeros().scale() > 2) {
            throw CbsaException.badRequest("PAYDBCR.CommAmt must have at most 2 decimal places");
        }
        // A010: COMM-SORTC is always overwritten with this bank's sort code.
        int sortCode = Integer.parseInt(bank.sortCode());
        String accno = in.commAccno() == null ? "" : in.commAccno().trim();
        if (!ACCNO.matcher(accno).matches()) {
            return new PayDbcr.Envelope(in.withResult(sortCode, in.commAvBal(), in.commActBal(), "N",
                    DebitCreditService.FAIL_NOT_FOUND));
        }
        PayDbcr.Origin origin = in.commOrigin();
        boolean payment = origin != null && origin.commFaciltype() != null
                && origin.commFaciltype() == DebitCreditService.PAYMENT_FACILITY_TYPE;
        try {
            Account account = service.debitCredit(Long.parseLong(accno), in.commAmt(), payment, origin14(origin));
            return new PayDbcr.Envelope(in.withResult(sortCode, CbsaFormats.money(account.availableBalance()),
                    CbsaFormats.money(account.actualBalance()), "Y", "0"));
        } catch (CbsaAbendException e) {
            throw e;
        } catch (CbsaException e) {
            String failCode = e.failCode() == null ? DebitCreditService.FAIL_DATASTORE : e.failCode();
            return new PayDbcr.Envelope(in.withResult(sortCode, in.commAvBal(), in.commActBal(), "N", failCode));
        }
    }

    /** COMM-ORIGIN(1:14): COMM-APPLID PIC X(8) followed by the first 6 bytes of COMM-USERID. */
    static String origin14(PayDbcr.Origin origin) {
        if (origin == null) {
            return CbsaFormats.fixed("", 14);
        }
        return (CbsaFormats.fixed(origin.commApplid(), 8) + CbsaFormats.fixed(origin.commUserid(), 8))
                .substring(0, 14);
    }
}
