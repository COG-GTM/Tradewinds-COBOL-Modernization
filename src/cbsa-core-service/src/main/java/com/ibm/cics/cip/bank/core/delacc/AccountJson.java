package com.ibm.cics.cip.bank.core.delacc;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Account JSON of docs/migration/api-contract.md (DELACC COMMAREA output). */
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
                a.sortCode() == null ? null : a.sortCode().trim(),
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
