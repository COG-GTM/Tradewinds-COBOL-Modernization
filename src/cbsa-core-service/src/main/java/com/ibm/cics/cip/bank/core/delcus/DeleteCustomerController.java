package com.ibm.cics.cip.bank.core.delcus;

import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code DELETE /banking/customer/{id}} (legacy {@code CustomerResource#deleteCustomerExternal}, DELCUS). */
@RestController
@RequestMapping("/banking/customer")
public class DeleteCustomerController {

    private final DeleteCustomerService service;

    public DeleteCustomerController(DeleteCustomerService service) {
        this.service = service;
    }

    @DeleteMapping("/{id}")
    public DeletedCustomerJson deleteCustomer(@PathVariable("id") long id) {
        if (id < 0) {
            throw CbsaException.notFound("Customer number cannot be negative");
        }
        return DeletedCustomerJson.of(service.deleteCustomer(id));
    }
}
