package com.ibm.cics.cip.bank.core.inqcust;

import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.time.LocalDate;

/**
 * Customer JSON of {@code docs/migration/api-contract.md} (legacy {@code CustomerJSON}): 10-char
 * zero-padded {@code id}, ISO dates and {@code customerCreditScore} as a string.
 */
public record CustomerJson(
        String id,
        String sortCode,
        String customerName,
        String customerAddress,
        LocalDate dateOfBirth,
        String customerCreditScore,
        LocalDate customerCreditScoreReviewDate) {

    public static CustomerJson from(Customer customer) {
        return new CustomerJson(
                CbsaFormats.customerNumber(customer.customerNumber()),
                customer.sortCode(),
                customer.name(),
                customer.address(),
                customer.dateOfBirth(),
                Integer.toString(customer.creditScore()),
                customer.creditScoreReviewDate());
    }
}
