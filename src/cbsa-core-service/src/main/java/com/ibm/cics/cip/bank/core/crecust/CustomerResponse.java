package com.ibm.cics.cip.bank.core.crecust;

import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;

/** Customer JSON of docs/migration/api-contract.md (zero-padded id, credit score as a string). */
public record CustomerResponse(String id, String sortCode, String customerName, String customerAddress,
        String dateOfBirth, String customerCreditScore, String customerCreditScoreReviewDate) {

    public static CustomerResponse from(Customer c) {
        return new CustomerResponse(
                CbsaFormats.customerNumber(c.customerNumber()),
                c.sortCode(),
                c.name(),
                c.address(),
                c.dateOfBirth() == null ? null : c.dateOfBirth().toString(),
                Integer.toString(c.creditScore()),
                c.creditScoreReviewDate() == null ? null : c.creditScoreReviewDate().toString());
    }
}
