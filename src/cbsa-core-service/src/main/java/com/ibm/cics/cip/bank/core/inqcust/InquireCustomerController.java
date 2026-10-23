package com.ibm.cics.cip.bank.core.inqcust;

import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read endpoints of the legacy Liberty {@code CustomerResource} backed by {@link InquireCustomerService}. */
@RestController
@RequestMapping("/banking/customer")
public class InquireCustomerController {

    /** Legacy CustomerResource: missing or zero limit means 250000. */
    static final int DEFAULT_LIMIT = 250_000;

    private final InquireCustomerService service;

    public InquireCustomerController(InquireCustomerService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public CustomerJson getCustomer(@PathVariable("id") long id) {
        if (id < 0) {
            throw CbsaException.badRequest("Customer number cannot be negative");
        }
        return service.inquire(id)
                .map(CustomerJson::from)
                .orElseThrow(() -> CbsaException.notFound("Customer number " + id + " not found.",
                        InquireCustomerService.FAIL_NOT_FOUND));
    }

    @GetMapping("/name")
    public CustomerListJson getCustomersByName(
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "countOnly", required = false) Boolean countOnly) {
        if (name == null) {
            throw CbsaException.badRequest("Customer name must be supplied");
        }
        if (Boolean.TRUE.equals(countOnly)) {
            return CustomerListJson.countOnly(service.countByName(name));
        }
        List<CustomerJson> customers = service.searchByName(name, limit(limit), offset(offset)).stream()
                .map(CustomerJson::from)
                .toList();
        if (customers.isEmpty() && service.countByName(name) == 0) {
            throw CbsaException.notFound("No customers found with name containing '" + name + "'.");
        }
        return CustomerListJson.of(customers);
    }

    @GetMapping
    public CustomerListJson getCustomers(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "countOnly", required = false) Boolean countOnly) {
        if (Boolean.TRUE.equals(countOnly)) {
            return CustomerListJson.countOnly(service.count());
        }
        return CustomerListJson.of(service.list(limit(limit), offset(offset)).stream()
                .map(CustomerJson::from)
                .toList());
    }

    private static int limit(Integer limit) {
        if (limit != null && limit < 0) {
            throw CbsaException.badRequest("limit cannot be negative");
        }
        return limit == null || limit == 0 ? DEFAULT_LIMIT : limit;
    }

    private static int offset(Integer offset) {
        if (offset != null && offset < 0) {
            throw CbsaException.badRequest("offset cannot be negative");
        }
        return offset == null ? 0 : offset;
    }
}
