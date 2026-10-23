package com.ibm.cics.cip.bank.core.delcus;

import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.time.LocalDate;

/** Customer JSON of docs/migration/api-contract.md, returned by {@code DELETE /banking/customer/{id}}. */
public record DeletedCustomerJson(
        String id,
        String sortCode,
        String customerName,
        String customerAddress,
        LocalDate dateOfBirth,
        String customerCreditScore,
        LocalDate customerCreditScoreReviewDate) {

    public static DeletedCustomerJson of(Customer c) {
        return new DeletedCustomerJson(
                CbsaFormats.customerNumber(c.customerNumber()),
                c.sortCode(),
                c.name(),
                c.address(),
                c.dateOfBirth(),
                String.valueOf(c.creditScore()),
                c.creditScoreReviewDate());
    }
}
