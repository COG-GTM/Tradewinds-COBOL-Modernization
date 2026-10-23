package com.ibm.cics.cip.bank.core.updcust;

import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.time.LocalDate;

/** Customer JSON per docs/migration/api-contract.md (id zero-padded, credit score as a string). */
public record UpdatedCustomerJson(
        String id,
        String sortCode,
        String customerName,
        String customerAddress,
        LocalDate dateOfBirth,
        String customerCreditScore,
        LocalDate customerCreditScoreReviewDate) {

    public static UpdatedCustomerJson of(Customer c) {
        return new UpdatedCustomerJson(
                CbsaFormats.customerNumber(c.customerNumber()),
                c.sortCode(),
                c.name(),
                c.address(),
                c.dateOfBirth(),
                String.valueOf(c.creditScore()),
                c.creditScoreReviewDate());
    }
}
