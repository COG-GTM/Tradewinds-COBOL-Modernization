package com.ibm.cics.cip.bank.core.updacc;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Account JSON of docs/migration/api-contract.md (zero-padded string keys, ISO dates, 2-dp money). */
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

    public static AccountJson from(Account a) {
        return new AccountJson(
                CbsaFormats.accountNumber(a.accountNumber()),
                a.sortCode(),
                CbsaFormats.customerNumber(a.customerNumber()),
                a.accountType() == null ? null : a.accountType().trim(),
                a.interestRate(),
                a.overdraftLimit(),
                CbsaFormats.money(a.availableBalance()),
                CbsaFormats.money(a.actualBalance()),
                a.opened(),
                a.lastStatement(),
                a.nextStatement());
    }
}
