package com.ibm.cics.cip.bank.core.crecust;

import com.ibm.cics.cip.bank.core.common.counter.CounterService;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.domain.ProctranType;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.proctran.ProctranWriter;
import com.ibm.cics.cip.bank.core.common.proctran.TaskNumberSupplier;
import com.ibm.cics.cip.bank.core.common.repository.CustomerRepository;
import com.ibm.cics.cip.bank.core.common.repository.ProctranRepository;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional tail of CRECUST: {@code ENQ-NAMED-COUNTER}, {@code UPD-NCS} ({@code GET-LAST-CUSTOMER-VSAM}),
 * {@code WRITE-CUSTOMER-VSAM} (customer record + control record count/last), {@code WRITE-PROCTRAN-DB2} (OCC)
 * and {@code DEQ-NAMED-COUNTER}. Any failure rolls back the counter, the customer row and the PROCTRAN row.
 */
@Component
public class CustomerWriter {

    static final String PROGRAM = "CRECUST";

    private final CounterService counters;
    private final CustomerRepository customers;
    private final ProctranRepository proctranRepository;
    private final TaskNumberSupplier taskNumbers;

    public CustomerWriter(CounterService counters, CustomerRepository customers,
            ProctranRepository proctranRepository, TaskNumberSupplier taskNumbers) {
        this.counters = counters;
        this.customers = customers;
        this.proctranRepository = proctranRepository;
        this.taskNumbers = taskNumbers;
    }

    /**
     * @param taskClock the CRECUST task's clock for the PROCTRAN date/time (the legacy task has already waited the
     *                  3-second credit-check DELAY when WRITE-PROCTRAN-DB2 issues its ASKTIME)
     */
    @Transactional
    public Customer write(String sortCode, String name, String address, LocalDate dateOfBirth,
            CreditCheckResult credit, Clock taskClock) {
        long number;
        try {
            number = counters.nextCustomerNumber();
        } catch (DataAccessException | IllegalStateException e) {
            // GLCV010: control record READ UPDATE / REWRITE failed
            throw CbsaException.serverError("Unable to allocate a new customer number", "4");
        }
        Customer customer = new Customer(Customer.EYECATCHER, sortCode, number, name, address, dateOfBirth,
                credit.score(), credit.reviewDate());
        try {
            customers.insert(customer);
        } catch (DataAccessException e) {
            // WCV010: EXEC CICS WRITE FILE('CUSTOMER') not NORMAL
            throw CbsaException.serverError("Unable to write customer " + number, "1");
        }
        try {
            new ProctranWriter(proctranRepository, taskNumbers, taskClock)
                    .createCustomer(ProctranType.BRANCH_CREATE_CUSTOMER, customer);
        } catch (DataAccessException e) {
            throw new CbsaAbendException("HWPT", PROGRAM,
                    "WPD010  - Unable to write to PROCTRAN DB2 datastore for customer " + number);
        }
        return customer;
    }
}
