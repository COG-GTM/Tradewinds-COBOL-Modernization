package com.ibm.cics.cip.bank.core.common.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Row of DB2 table PROCTRAN (copybook PROCTRAN.cpy / PROCDB2.cpy): the audit log. */
public record ProcessedTransaction(
        String eyecatcher,
        String sortCode,
        String number,
        LocalDate date,
        String time,
        String reference,
        String type,
        String description,
        BigDecimal amount) {

    public static final String EYECATCHER = "PRTR";
}
