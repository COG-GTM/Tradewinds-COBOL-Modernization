package com.ibm.cics.cip.bank.core.common.bank;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /banking/sortCode and /banking/companyName (legacy SortCodeResource / CompanyNameResource). */
@RestController
@RequestMapping("/banking")
public class BankIdentityController {

    private final BankIdentity bankIdentity;

    public BankIdentityController(BankIdentity bankIdentity) {
        this.bankIdentity = bankIdentity;
    }

    @GetMapping("/sortCode")
    public Map<String, String> sortCode() {
        return Map.of("sortCode", bankIdentity.sortCode());
    }

    @GetMapping("/companyName")
    public Map<String, String> companyName() {
        return Map.of("companyName", bankIdentity.companyName());
    }
}
