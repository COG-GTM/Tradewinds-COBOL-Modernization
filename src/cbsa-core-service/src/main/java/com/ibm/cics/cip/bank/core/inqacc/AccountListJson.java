package com.ibm.cics.cip.bank.core.inqacc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/** Body of {@code GET /banking/account?limit=&offset=&countOnly=}; {@code accounts} omitted when countOnly. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"numberOfAccounts", "accounts"})
public record AccountListJson(long numberOfAccounts, List<AccountJson> accounts) {
}
