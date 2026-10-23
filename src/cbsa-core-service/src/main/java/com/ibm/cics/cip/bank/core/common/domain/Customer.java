package com.ibm.cics.cip.bank.core.common.domain;

import java.time.LocalDate;

/**
 * Row of table CUSTOMER (formerly the VSAM KSDS customer file, copybook CUSTOMER.cpy).
 * Key = (sortCode, customerNumber). Strings are trimmed on read.
 */
public record Customer(
        String eyecatcher,
        String sortCode,
        long customerNumber,
        String name,
        String address,
        LocalDate dateOfBirth,
        int creditScore,
        LocalDate creditScoreReviewDate) {

    public static final String EYECATCHER = "CUST";

    public Customer withNameAndAddress(String newName, String newAddress) {
        return new Customer(eyecatcher, sortCode, customerNumber, newName, newAddress, dateOfBirth, creditScore,
                creditScoreReviewDate);
    }

    public Customer withCreditScore(int score, LocalDate reviewDate) {
        return new Customer(eyecatcher, sortCode, customerNumber, name, address, dateOfBirth, score, reviewDate);
    }
}
