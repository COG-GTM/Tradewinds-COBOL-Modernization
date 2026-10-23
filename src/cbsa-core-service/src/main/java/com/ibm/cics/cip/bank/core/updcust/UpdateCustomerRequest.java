package com.ibm.cics.cip.bank.core.updcust;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body of {@code PUT /banking/customer/{id}} as sent by the React UI
 * ({@code customerName, customerAddress, sortCode, dateOfBirth, creditScore}).
 * Only name and address are applied (UPDCUST); the other fields are accepted and ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateCustomerRequest(
        String customerName,
        String customerAddress,
        String sortCode,
        String dateOfBirth,
        @JsonAlias("customerCreditScore") String creditScore) {
}
