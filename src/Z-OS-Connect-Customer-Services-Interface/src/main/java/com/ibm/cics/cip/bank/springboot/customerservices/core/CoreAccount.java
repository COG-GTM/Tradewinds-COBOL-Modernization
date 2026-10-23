package com.ibm.cics.cip.bank.springboot.customerservices.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/** Account JSON returned by cbsa-core-service /banking/account. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoreAccount(String id, String sortCode, String customerNumber,
		String accountType, BigDecimal interestRate, BigDecimal overdraft,
		BigDecimal availableBalance, BigDecimal actualBalance,
		String dateOpened, String lastStatementDate, String nextStatementDate)
{
}
