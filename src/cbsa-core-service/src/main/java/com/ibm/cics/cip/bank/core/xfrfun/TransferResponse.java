package com.ibm.cics.cip.bank.core.xfrfun;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** {@code 200 {"fromAccount": Account, "toAccount": Account, "amount": 10.00}} (docs/migration/api-contract.md). */
public record TransferResponse(AccountJson fromAccount, AccountJson toAccount, BigDecimal amount) {

    static TransferResponse of(TransferResult r) {
        return new TransferResponse(AccountJson.of(r.from()), AccountJson.of(r.to()), scale2(r.amount()));
    }

    /** Account JSON of the API contract: zero-padded string keys, money and rate with 2 decimals. */
    public record AccountJson(
            String id,
            String sortCode,
            String customerNumber,
            String accountType,
            BigDecimal interestRate,
            int overdraft,
            BigDecimal availableBalance,
            BigDecimal actualBalance,
            LocalDate dateOpened,
            LocalDate lastStatementDate,
            LocalDate nextStatementDate) {

        static AccountJson of(Account a) {
            return new AccountJson(
                    CbsaFormats.accountNumber(a.accountNumber()),
                    a.sortCode(),
                    CbsaFormats.customerNumber(a.customerNumber()),
                    a.accountType() == null ? null : a.accountType().trim(),
                    scale2(a.interestRate()),
                    a.overdraftLimit(),
                    scale2(a.availableBalance()),
                    scale2(a.actualBalance()),
                    a.opened(),
                    a.lastStatement(),
                    a.nextStatement());
        }
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.DOWN);
    }
}
