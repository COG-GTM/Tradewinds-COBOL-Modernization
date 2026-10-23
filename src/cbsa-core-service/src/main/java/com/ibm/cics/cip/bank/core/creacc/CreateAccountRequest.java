package com.ibm.cics.cip.bank.core.creacc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * Body of {@code POST /banking/account} as sent by the React AccountCreationPage (legacy {@code AccountJSON}).
 * The UI sends every value as a string; Jackson coerces the numeric ones. {@code dateOpened} is accepted but
 * ignored: CREACC always opens the account today.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateAccountRequest(
        String customerNumber,
        String accountType,
        BigDecimal interestRate,
        Integer overdraft,
        String sortCode,
        String dateOpened) {
}
