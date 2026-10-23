package com.ibm.cics.cip.bank.core.updcust;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PUT /banking/customer/{id}} (replaces the Liberty {@code CustomerResource.updateCustomerInternal}).
 * Request checks in the legacy order (name null, sort code null / not ours, address null) with the legacy
 * messages; the business rules then run in {@link UpdateCustomerService} (UPDCUST).
 */
@RestController
@RequestMapping("/banking/customer")
public class UpdateCustomerController {

    private final UpdateCustomerService service;
    private final BankIdentity bank;

    public UpdateCustomerController(UpdateCustomerService service, BankIdentity bank) {
        this.service = service;
        this.bank = bank;
    }

    @PutMapping("/{id}")
    public UpdatedCustomerJson updateCustomer(@PathVariable("id") long id, @RequestBody UpdateCustomerRequest body) {
        if (body.customerName() == null) {
            throw CbsaException.badRequest("Customer name is null");
        }
        if (body.sortCode() == null) {
            throw CbsaException.badRequest("Sort Code is null");
        }
        if (!bank.isOurSortCode(body.sortCode())) {
            throw CbsaException.badRequest("Sortcode " + displaySortCode(body.sortCode())
                    + " not valid for this bank (" + Integer.parseInt(bank.sortCode()) + ")");
        }
        if (body.customerAddress() == null) {
            throw CbsaException.badRequest("Customer address is null");
        }
        return UpdatedCustomerJson.of(service.updateCustomer(id, body.customerName(), body.customerAddress()));
    }

    /** Liberty printed {@code Integer.parseInt(sortCode)}; non-numeric values are echoed as sent. */
    private static String displaySortCode(String sortCode) {
        try {
            return String.valueOf(Integer.parseInt(sortCode.trim()));
        } catch (NumberFormatException e) {
            return sortCode;
        }
    }
}
