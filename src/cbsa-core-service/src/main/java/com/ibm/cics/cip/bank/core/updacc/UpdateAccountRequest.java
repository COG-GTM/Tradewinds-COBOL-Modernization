package com.ibm.cics.cip.bank.core.updacc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/**
 * Body of {@code PUT /banking/account/{id}}: the full Account JSON the React UI sends
 * (AccountDetailsTable.js). Only {@code accountType}, {@code interestRate} and {@code overdraft} are applied
 * and {@code sortCode} is validated; the other fields are accepted in any shape and ignored, as UPDACC
 * ignores the balances and dates in its COMMAREA.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateAccountRequest(
        JsonNode id,
        JsonNode customerNumber,
        String sortCode,
        String accountType,
        BigDecimal interestRate,
        Integer overdraft,
        JsonNode dateOpened,
        JsonNode lastStatementDate,
        JsonNode nextStatementDate,
        JsonNode availableBalance,
        JsonNode actualBalance) {
}
