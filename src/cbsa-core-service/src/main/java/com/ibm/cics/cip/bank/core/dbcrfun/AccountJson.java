package com.ibm.cics.cip.bank.core.dbcrfun;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Account JSON of docs/migration/api-contract.md. */
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
                a.accountType() == null ? null : a.accountType().trim(),
                CbsaFormats.money(a.interestRate()),
                a.overdraftLimit(),
                CbsaFormats.money(a.availableBalance()),
                CbsaFormats.money(a.actualBalance()),
                a.opened(),
                a.lastStatement(),
                a.nextStatement());
    }
}
