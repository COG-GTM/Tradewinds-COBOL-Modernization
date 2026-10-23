package com.ibm.cics.cip.bank.core.crecust;

/** Body of {@code POST /banking/customer} as sent by the React UI ({@code dateOfBirth} is yyyy-MM-dd). */
public record CreateCustomerRequest(String customerName, String customerAddress, String dateOfBirth,
        String sortCode) {
}
