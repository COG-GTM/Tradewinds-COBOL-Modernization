package com.ibm.cics.cip.bank.core.common.counter;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import com.ibm.cics.cip.bank.core.common.repository.ControlRepository;
import com.ibm.cics.cip.bank.core.common.repository.CustomerRepository;
import com.ibm.cics.cip.bank.core.common.seed.SeedDataLoader;
import org.springframework.beans.factory.InitializingBean;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Named-counter equivalent (CICS NCS 'CBSACUST'/'CBSAACCT' + ENQ/DEQ, NEWCUSNO/NEWACCNO 'G'/'C').
 * Backed by CONTROL rows {@code <sortcode>-CUSTOMER-LAST/-COUNT} and {@code <sortcode>-ACCOUNT-LAST/-COUNT}
 * (the ACCOUNT names are the legacy CREACC/BANKDATA ones). Missing rows are created at startup, after the seed
 * load, from MAX(number)/COUNT(*), so allocation never inserts.
 *
 * <p>Allocation is an atomic {@code UPDATE ... SET n = n + 1} whose row lock lasts until the enclosing
 * transaction ends (this replaces the ENQ/DEQ). Call {@code next*} from inside the business method's own
 * {@code @Transactional} so that a failed insert rolls the counter back with it; that replaces the legacy
 * NEWCUSNO/NEWACCNO 'R' function, so there is deliberately no explicit decrement API.
 */
@Service
public class CounterService implements InitializingBean {

    public static final String CUSTOMER_LAST = "-CUSTOMER-LAST";
    public static final String CUSTOMER_COUNT = "-CUSTOMER-COUNT";
    public static final String ACCOUNT_LAST = "-ACCOUNT-LAST";
    public static final String ACCOUNT_COUNT = "-ACCOUNT-COUNT";

    private final ControlRepository control;
    private final CustomerRepository customers;
    private final AccountRepository accounts;
    private final String sortCode;

    public CounterService(ControlRepository control, CustomerRepository customers, AccountRepository accounts,
            BankIdentity bank, SeedDataLoader seedLoadedFirst) {
        this.control = control;
        this.customers = customers;
        this.accounts = accounts;
        this.sortCode = bank.sortCode();
    }

    @Override
    public void afterPropertiesSet() {
        control.createIfMissing(sortCode + CUSTOMER_LAST, () -> customers.maxCustomerNumber(sortCode));
        control.createIfMissing(sortCode + CUSTOMER_COUNT, () -> customers.count(sortCode));
        control.createIfMissing(sortCode + ACCOUNT_LAST, () -> accounts.maxAccountNumber(sortCode));
        control.createIfMissing(sortCode + ACCOUNT_COUNT, () -> accounts.count(sortCode));
    }

    /** NEWCUSNO 'G': allocate the next customer number and bump the customer count. */
    @Transactional
    public long nextCustomerNumber() {
        long next = control.addAndGet(sortCode + CUSTOMER_LAST, 1);
        control.addAndGet(sortCode + CUSTOMER_COUNT, 1);
        return next;
    }

    /** NEWACCNO 'G': allocate the next account number and bump the account count. */
    @Transactional
    public long nextAccountNumber() {
        long next = control.addAndGet(sortCode + ACCOUNT_LAST, 1);
        control.addAndGet(sortCode + ACCOUNT_COUNT, 1);
        return next;
    }

    /** NEWCUSNO 'C': last customer number allocated. */
    public long currentCustomerNumber() {
        return current(sortCode + CUSTOMER_LAST, () -> customers.maxCustomerNumber(sortCode));
    }

    /** NEWACCNO 'C': last account number allocated. */
    public long currentAccountNumber() {
        return current(sortCode + ACCOUNT_LAST, () -> accounts.maxAccountNumber(sortCode));
    }

    private long current(String lastName, LongSupplier fallback) {
        return control.find(lastName).map(r -> r.valueNum() == null ? 0L : r.valueNum())
                .orElseGet(fallback::getAsLong);
    }
}
