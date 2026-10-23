package com.ibm.cics.cip.bank.core.updacc;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PUT /banking/account/{id}}: replaces the Liberty JAX-RS {@code AccountsResource.updateAccountInternal}
 * that the React UI used to call. Request validation follows that resource (account type, interest rate, sort
 * code, in that order); the update itself is UPDACC ({@link UpdateAccountService}).
 */
@RestController
@RequestMapping("/banking/account")
public class UpdateAccountController {

    /** {@code AccountJSON.validateType}: the account types the bank supports. */
    static final Set<String> VALID_ACCOUNT_TYPES = Set.of("ISA", "MORTGAGE", "LOAN", "SAVING", "CURRENT");

    private final UpdateAccountService service;
    private final BankIdentity bank;

    public UpdateAccountController(UpdateAccountService service, BankIdentity bank) {
        this.service = service;
        this.bank = bank;
    }

    @PutMapping("/{id}")
    public AccountJson updateAccount(@PathVariable("id") long id, @RequestBody UpdateAccountRequest request) {
        String type = validateType(request.accountType());
        BigDecimal rate = validateInterestRate(request.interestRate());
        validateSortCode(request.sortCode());
        if (request.overdraft() == null) {
            throw CbsaException.badRequest("Overdraft limit must be supplied.");
        }
        Account updated = service.updateAccount(id, type, rate, request.overdraft());
        return AccountJson.from(updated);
    }

    private static String validateType(String accountType) {
        String trimmed = accountType == null ? "" : accountType.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (!VALID_ACCOUNT_TYPES.contains(upper)) {
            throw CbsaException.badRequest("Account type " + trimmed + " is not supported.");
        }
        return upper;
    }

    private static BigDecimal validateInterestRate(BigDecimal rate) {
        if (rate == null) {
            throw CbsaException.badRequest("Interest rate must be supplied.");
        }
        if (rate.signum() < 0) {
            throw CbsaException.badRequest("Interest rate cannot be less than zero.");
        }
        if (rate.compareTo(UpdateAccountService.MAX_INTEREST_RATE) > 0) {
            throw CbsaException.badRequest("Interest rate cannot be greater than 9999.99%.");
        }
        if (rate.stripTrailingZeros().scale() > 2) {
            throw CbsaException.badRequest(
                    "Interest rate cannot have more than 2 decimal places. " + rate.toPlainString());
        }
        return rate;
    }

    private void validateSortCode(String sortCode) {
        String candidate = sortCode == null ? "" : sortCode.trim();
        boolean ok;
        try {
            ok = !candidate.isEmpty() && Integer.parseInt(candidate) == Integer.parseInt(bank.sortCode());
        } catch (NumberFormatException e) {
            ok = false;
        }
        if (!ok) {
            throw CbsaException.badRequest(
                    "Sortcode " + candidate + " not valid for this bank (" + bank.sortCode() + ")");
        }
    }
}
