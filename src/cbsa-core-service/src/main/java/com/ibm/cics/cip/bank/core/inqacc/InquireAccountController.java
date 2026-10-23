package com.ibm.cics.cip.bank.core.inqacc;

import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST face of INQACC and INQACCCU (docs/migration/api-contract.md, "Account endpoints"). */
@RestController
@RequestMapping("/banking/account")
public class InquireAccountController {

    /** Legacy AccountsResource default when limit is absent or 0. */
    static final int DEFAULT_LIMIT = 250_000;

    private final InquireAccountService service;

    public InquireAccountController(InquireAccountService service) {
        this.service = service;
    }

    /** INQACC. 99999999 returns the last account. */
    @GetMapping("/{id}")
    public AccountJson getAccount(@PathVariable("id") long id) {
        return service.inquire(id)
                .map(AccountJson::of)
                .orElseThrow(() -> CbsaException.notFound("Account number " + id + " not found."));
    }

    /** INQACCCU. */
    @GetMapping("/retrieveByCustomerNumber/{customerNumber}")
    public AccountsByCustomerJson getAccountsByCustomer(@PathVariable("customerNumber") long customerNumber,
            @RequestParam(name = "countOnly", required = false) Boolean countOnly) {
        String padded = CbsaFormats.customerNumber(customerNumber);
        if (Boolean.TRUE.equals(countOnly)) {
            return new AccountsByCustomerJson(padded, true, service.countAccountsForCustomer(customerNumber), null);
        }
        List<AccountJson> accounts = service.accountsForCustomer(customerNumber).stream().map(AccountJson::of)
                .toList();
        return new AccountsByCustomerJson(padded, true, accounts.size(), accounts);
    }

    /** All accounts of this bank, paged (legacy {@code getAccountsExternal}). */
    @GetMapping
    public AccountListJson getAccounts(@RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "countOnly", required = false) Boolean countOnly) {
        if (Boolean.TRUE.equals(countOnly)) {
            return new AccountListJson(service.countAccounts(), null);
        }
        int realLimit = limit == null || limit == 0 ? DEFAULT_LIMIT : limit;
        int realOffset = offset == null ? 0 : offset;
        List<AccountJson> accounts = service.listAccounts(realLimit, realOffset).stream().map(AccountJson::of)
                .toList();
        return new AccountListJson(accounts.size(), accounts);
    }
}
