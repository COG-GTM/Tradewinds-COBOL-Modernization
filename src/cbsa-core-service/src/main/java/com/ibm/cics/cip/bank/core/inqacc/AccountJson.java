package com.ibm.cics.cip.bank.core.inqacc;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Account JSON of docs/migration/api-contract.md (legacy {@code AccountsResource} shape): 8-char zero-padded
 * {@code id}, 10-char zero-padded {@code customerNumber}, trimmed {@code accountType}, ISO dates and
 * scale-2 numbers. Built from the INQACC / INQACCCU output fields (ACCOUNT.cpy).
 */
@JsonPropertyOrder({"id", "sortCode", "customerNumber", "accountType", "interestRate", "overdraft",
        "availableBalance", "actualBalance", "dateOpened", "lastStatementDate", "nextStatementDate"})
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
                a.sortCode() == null ? null : a.sortCode().strip(),
                CbsaFormats.customerNumber(a.customerNumber()),
                a.accountType() == null ? null : a.accountType().strip(),
                CbsaFormats.money(a.interestRate()),
                a.overdraftLimit(),
                CbsaFormats.money(a.availableBalance()),
                CbsaFormats.money(a.actualBalance()),
                a.opened(),
                a.lastStatement(),
                a.nextStatement());
    }
}
