package com.ibm.cics.cip.bank.core.creacc;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /banking/account}. Applies the web-layer checks of the legacy
 * {@code AccountsResource.validateNewAccount} (rule R-A9: interest rate 0..9999.99 with at most 2 decimals,
 * overdraft >= 0, customer number 1..9999999998, sort code of this bank), then delegates to
 * {@link CreateAccountService} (CREACC) with zero balances, as the legacy web path did.
 */
@RestController
@RequestMapping("/banking/account")
public class CreateAccountController {

    static final BigDecimal MAX_INTEREST_RATE = new BigDecimal("9999.99");
    static final long LAST_CUSTOMER_SENTINEL = 9_999_999_999L;

    private final CreateAccountService service;
    private final BankIdentity bank;

    public CreateAccountController(CreateAccountService service, BankIdentity bank) {
        this.service = service;
        this.bank = bank;
    }

    @PostMapping
    public ResponseEntity<AccountJson> create(@RequestBody CreateAccountRequest request) {
        long customerNumber = validate(request);
        Account created = service.createAccount(customerNumber, request.accountType().strip(),
                request.interestRate(), request.overdraft(), BigDecimal.ZERO, BigDecimal.ZERO);
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountJson.of(created));
    }

    private long validate(CreateAccountRequest r) {
        if (r.accountType() == null) {
            throw CbsaException.badRequest("Account type is null");
        }
        BigDecimal rate = r.interestRate();
        if (rate == null) {
            throw CbsaException.badRequest("Interest rate is null");
        }
        if (rate.signum() < 0) {
            throw CbsaException.badRequest("Interest rate cannot be less than zero.");
        }
        if (rate.compareTo(MAX_INTEREST_RATE) > 0) {
            throw CbsaException.badRequest("Interest rate cannot be greater than 9999.99%.");
        }
        if (rate.scale() > 2) {
            throw CbsaException.badRequest(
                    "Interest rate cannot have more than 2 decimal places. " + rate.toPlainString());
        }
        if (r.overdraft() == null) {
            throw CbsaException.badRequest("Overdraft limit is null");
        }
        if (r.overdraft() < 0) {
            throw CbsaException.badRequest("Overdraft limit cannot be less than zero.");
        }
        long customerNumber = parseCustomerNumber(r.customerNumber());
        if (customerNumber < 1) {
            throw CbsaException.badRequest("Customer number cannot be less than one.");
        }
        if (customerNumber == LAST_CUSTOMER_SENTINEL) {
            throw CbsaException.badRequest("Customer number cannot be 9,999,999,999.");
        }
        if (!isOurSortCode(r.sortCode())) {
            throw CbsaException.badRequest("Sortcode " + (r.sortCode() == null ? "" : r.sortCode().strip())
                    + " not valid for this bank (" + bank.sortCode() + ")");
        }
        return customerNumber;
    }

    private static long parseCustomerNumber(String value) {
        if (value == null || value.isBlank()) {
            throw CbsaException.badRequest("Customer Number is null");
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            throw CbsaException.badRequest("Customer number " + value.strip() + " is not numeric.");
        }
    }

    private boolean isOurSortCode(String sortCode) {
        return bank.isOurSortCode(sortCode);
    }
}
