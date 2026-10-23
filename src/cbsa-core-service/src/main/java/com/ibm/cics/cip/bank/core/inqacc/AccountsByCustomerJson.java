package com.ibm.cics.cip.bank.core.inqacc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Body of {@code GET /banking/account/retrieveByCustomerNumber/{id}}: the INQACCCU COMMAREA
 * (CUSTOMER-FOUND, NUMBER-OF-ACCOUNTS, ACCOUNT-DETAILS) plus the 10-char {@code customerNumber} the legacy
 * Liberty resource returned. {@code accounts} is omitted when {@code countOnly=true}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"customerNumber", "customerFound", "numberOfAccounts", "accounts"})
public record AccountsByCustomerJson(
        String customerNumber,
        boolean customerFound,
        int numberOfAccounts,
        List<AccountJson> accounts) {
}
