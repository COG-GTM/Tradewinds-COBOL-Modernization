package com.ibm.cics.cip.bank.core.inqcust;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** {@code {"numberOfCustomers": n, "customers": [...]}}; {@code customers} omitted for countOnly. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomerListJson(long numberOfCustomers, List<CustomerJson> customers) {

    static CustomerListJson countOnly(long count) {
        return new CustomerListJson(count, null);
    }

    static CustomerListJson of(List<CustomerJson> customers) {
        return new CustomerListJson(customers.size(), customers);
    }
}
