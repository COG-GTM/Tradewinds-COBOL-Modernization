package com.ibm.cics.cip.bank.core.delacc;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code DELETE /banking/account/{id}} (DELACC). */
@RestController
@RequestMapping("/banking/account")
public class DeleteAccountController {

    private final DeleteAccountService service;

    public DeleteAccountController(DeleteAccountService service) {
        this.service = service;
    }

    @DeleteMapping("/{id}")
    public AccountJson deleteAccount(@PathVariable("id") long accountNumber) {
        return AccountJson.from(service.deleteAccount(accountNumber));
    }
}
