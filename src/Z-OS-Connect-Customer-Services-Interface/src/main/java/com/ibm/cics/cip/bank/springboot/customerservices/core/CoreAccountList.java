package com.ibm.cics.cip.bank.springboot.customerservices.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Response of GET /banking/account/retrieveByCustomerNumber/{id}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoreAccountList(Boolean customerFound, Integer numberOfAccounts,
		List<CoreAccount> accounts)
{
}
