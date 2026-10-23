package com.ibm.cics.cip.bank.core.creacc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * Body of {@code POST /banking/account} as sent by the React AccountCreationPage (legacy {@code AccountJSON}).
 * The UI sends every value as a string; Jackson coerces the numeric ones. {@code dateOpened} is accepted but
 * ignored: CREACC always opens the account today.
 *
 * <p>{@code availableBalance} / {@code actualBalance} are an additive optional extension (COMMAREA
 * {@code COMM-AVAIL-BAL} / {@code COMM-ACT-BAL}): CREACC writes them to the new ACCOUNT row as passed. The React
 * UI never sends them, so they default to zero as on the legacy web path.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateAccountRequest(
        String customerNumber,
        String accountType,
        BigDecimal interestRate,
        Integer overdraft,
        String sortCode,
        String dateOpened,
        BigDecimal availableBalance,
        BigDecimal actualBalance) {
}
