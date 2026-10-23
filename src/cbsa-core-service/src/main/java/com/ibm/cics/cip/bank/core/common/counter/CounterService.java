package com.ibm.cics.cip.bank.core.common.counter;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import com.ibm.cics.cip.bank.core.common.repository.ControlRepository;
import com.ibm.cics.cip.bank.core.common.repository.CustomerRepository;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Named-counter equivalent (CICS NCS 'CBSACUST'/'CBSAACCT' + ENQ/DEQ, NEWCUSNO/NEWACCNO 'G'/'R'/'C').
 * Backed by CONTROL rows {@code <sortcode>-CUSTOMER-LAST/-COUNT} and {@code <sortcode>-ACCOUNT-LAST/-COUNT}
 * (the ACCOUNT names are the legacy CREACC/BANKDATA ones). Missing rows fall back to MAX(number).
 * Callers should invoke these inside their own @Transactional business method so that a failed
 * insert rolls the counter back too (the legacy 'R' function).
 */
@Service
public class CounterService {

    public static final String CUSTOMER_LAST = "-CUSTOMER-LAST";
    public static final String CUSTOMER_COUNT = "-CUSTOMER-COUNT";
    public static final String ACCOUNT_LAST = "-ACCOUNT-LAST";
    public static final String ACCOUNT_COUNT = "-ACCOUNT-COUNT";

    private final ControlRepository control;
    private final CustomerRepository customers;
    private final AccountRepository accounts;
    private final String sortCode;

    public CounterService(ControlRepository control, CustomerRepository customers, AccountRepository accounts,
            BankIdentity bank) {
        this.control = control;
        this.customers = customers;
        this.accounts = accounts;
        this.sortCode = bank.sortCode();
    }

    /** NEWCUSNO 'G': allocate the next customer number and bump the customer count. */
    @Transactional
    public synchronized long nextCustomerNumber() {
        return next(sortCode + CUSTOMER_LAST, sortCode + CUSTOMER_COUNT,
                () -> customers.maxCustomerNumber(sortCode), () -> customers.count(sortCode));
    }

    /** NEWACCNO 'G': allocate the next account number and bump the account count. */
    @Transactional
    public synchronized long nextAccountNumber() {
        return next(sortCode + ACCOUNT_LAST, sortCode + ACCOUNT_COUNT,
                () -> accounts.maxAccountNumber(sortCode), () -> accounts.count(sortCode));
    }

    /** NEWCUSNO 'C': last customer number allocated. */
    public long currentCustomerNumber() {
        return current(sortCode + CUSTOMER_LAST, () -> customers.maxCustomerNumber(sortCode));
    }

    /** NEWACCNO 'C': last account number allocated. */
    public long currentAccountNumber() {
        return current(sortCode + ACCOUNT_LAST, () -> accounts.maxAccountNumber(sortCode));
    }

    /** NEWCUSNO 'R': give back the last customer number (legacy rollback on failed write). */
    @Transactional
    public synchronized void rollbackCustomerNumber() {
        rollback(sortCode + CUSTOMER_LAST, sortCode + CUSTOMER_COUNT);
    }

    /** NEWACCNO 'R': give back the last account number. */
    @Transactional
    public synchronized void rollbackAccountNumber() {
        rollback(sortCode + ACCOUNT_LAST, sortCode + ACCOUNT_COUNT);
    }

    private long next(String lastName, String countName, LongSupplier lastFallback, LongSupplier countFallback) {
        long last = current(lastName, lastFallback) + 1;
        long count = control.find(countName).map(r -> r.valueNum() == null ? 0L : r.valueNum())
                .orElseGet(countFallback::getAsLong) + 1;
        control.upsertNum(lastName, last);
        control.upsertNum(countName, count);
        return last;
    }

    private long current(String lastName, LongSupplier fallback) {
        return control.find(lastName).map(r -> r.valueNum() == null ? 0L : r.valueNum())
                .orElseGet(fallback::getAsLong);
    }

    private void rollback(String lastName, String countName) {
        control.find(lastName).ifPresent(r -> control.upsertNum(lastName, Math.max(0, r.valueNum() - 1)));
        control.find(countName).ifPresent(r -> control.upsertNum(countName, Math.max(0, r.valueNum() - 1)));
    }
}
