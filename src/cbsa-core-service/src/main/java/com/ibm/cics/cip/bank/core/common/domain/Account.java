package com.ibm.cics.cip.bank.core.common.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Row of DB2 table ACCOUNT (copybook ACCOUNT.cpy / ACCDB2.cpy). Key = (sortCode, accountNumber). */
public record Account(
        String eyecatcher,
        long customerNumber,
        String sortCode,
        long accountNumber,
        String accountType,
        BigDecimal interestRate,
        LocalDate opened,
        int overdraftLimit,
        LocalDate lastStatement,
        LocalDate nextStatement,
        BigDecimal availableBalance,
        BigDecimal actualBalance) {

    public static final String EYECATCHER = "ACCT";

    public Account withBalances(BigDecimal available, BigDecimal actual) {
        return new Account(eyecatcher, customerNumber, sortCode, accountNumber, accountType, interestRate, opened,
                overdraftLimit, lastStatement, nextStatement, available, actual);
    }

    public Account withTypeRateOverdraft(String type, BigDecimal rate, int overdraft) {
        return new Account(eyecatcher, customerNumber, sortCode, accountNumber, type, rate, opened, overdraft,
                lastStatement, nextStatement, availableBalance, actualBalance);
    }
}
