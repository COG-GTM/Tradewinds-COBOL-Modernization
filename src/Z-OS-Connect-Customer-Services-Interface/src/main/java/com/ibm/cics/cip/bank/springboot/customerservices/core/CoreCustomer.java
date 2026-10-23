package com.ibm.cics.cip.bank.springboot.customerservices.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Customer JSON returned by cbsa-core-service /banking/customer. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoreCustomer(String id, String sortCode, String customerName,
		String customerAddress, String dateOfBirth, String customerCreditScore,
		String customerCreditScoreReviewDate)
{
}
