package com.ibm.cics.cip.bank.core.screens.port;

import java.time.LocalDate;
import java.util.Optional;

import com.ibm.cics.cip.bank.core.common.domain.Customer;

/**
 * Customer business services the screens LINK to. Implementations delegate to the wave-1 services
 * (InquireCustomerService, CreateCustomerService, UpdateCustomerService, DeleteCustomerService) and
 * report COMMAREA fail codes as {@link com.ibm.cics.cip.bank.core.common.error.CbsaException#failCode()}.
 */
public interface CustomerScreenPort {

    /** INQCUST. */
    Optional<Customer> inquireCustomer(long customerNumber);

    /** CRECUST. */
    Customer createCustomer(String name, String address, LocalDate dateOfBirth);

    /** UPDCUST. */
    Customer updateCustomer(long customerNumber, String name, String address);

    /** DELCUS (also deletes the customer's accounts). */
    Customer deleteCustomer(long customerNumber);
}
