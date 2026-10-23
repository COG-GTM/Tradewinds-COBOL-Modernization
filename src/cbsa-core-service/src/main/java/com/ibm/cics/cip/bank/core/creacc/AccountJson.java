package com.ibm.cics.cip.bank.core.creacc;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** Account JSON of docs/migration/api-contract.md (8-char id, 10-char customer number, trimmed type, ISO dates). */
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

    public static AccountJson of(Account a) {
        return new AccountJson(
                CbsaFormats.accountNumber(a.accountNumber()),
                a.sortCode(),
                CbsaFormats.customerNumber(a.customerNumber()),
                a.accountType() == null ? null : a.accountType().strip(),
                twoDp(a.interestRate()),
                a.overdraftLimit(),
                twoDp(a.availableBalance()),
                twoDp(a.actualBalance()),
                a.opened(),
                a.lastStatement(),
                a.nextStatement());
    }

    private static BigDecimal twoDp(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.DOWN);
    }
}
