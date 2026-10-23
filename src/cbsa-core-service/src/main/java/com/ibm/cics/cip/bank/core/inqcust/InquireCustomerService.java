package com.ibm.cics.cip.bank.core.inqcust;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.config.CbsaRandom;
import com.ibm.cics.cip.bank.core.common.config.CbsaRandomFactory;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.repository.CustomerRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Port of {@code INQCUST.cbl}: read one customer from the customer file (VSAM, now table CUSTOMER),
 * plus the name search / list reads of the legacy Liberty {@code CustomerResource}.
 *
 * <p>Special customer numbers (rule R-C5): {@value #RANDOM_CUSTOMER} returns a random existing
 * customer, {@value #LAST_CUSTOMER} returns the customer with the highest number. INQCUST never
 * writes: no CUSTOMER, ACCOUNT, CONTROL or PROCTRAN changes.
 */
@Service
public class InquireCustomerService {

    /** INQCUST-CUSTNO 0000000000: GENERATE-RANDOM-CUSTOMER. */
    public static final long RANDOM_CUSTOMER = 0L;

    /** INQCUST-CUSTNO 9999999999: last (highest-numbered) customer. */
    public static final long LAST_CUSTOMER = 9_999_999_999L;

    /** {@code IF INQCUST-RETRY < 1000}: re-draws allowed after the first random miss. */
    static final int MAX_RANDOM_RETRIES = 1000;

    /**
     * {@link CbsaRandomFactory} stream for {@code FUNCTION RANDOM(EIBTASKN)}. The legacy harness seeds
     * RANDOM with {@code CBSA_RANDOM_SEED + EIBTASKN} and every baseline transcript runs with
     * {@code CBSA_TASKN=100}, so stream 100 (seed + 100) mirrors it.
     */
    public static final int RANDOM_STREAM = 100;

    static final String PROGRAM = "INQCUST";
    static final String ABEND_FILE_ERROR = "CVR1";
    static final String FAIL_NOT_FOUND = "1";
    static final String FAIL_NO_LAST_CUSTOMER = "9";

    private final CustomerRepository customers;
    private final BankIdentity bank;
    private final CbsaRandomFactory randomFactory;

    public InquireCustomerService(CustomerRepository customers, BankIdentity bank, CbsaRandomFactory randomFactory) {
        this.customers = customers;
        this.bank = bank;
        this.randomFactory = randomFactory;
    }

    /**
     * {@code PREMIERE}: inquire on one customer of this bank's sort code.
     *
     * @return the customer, or empty for INQCUST-INQ-FAIL-CD {@code 1} (not found; also for numbers
     *     outside PIC 9(10))
     * @throws CbsaException fail code {@code 9} when the last customer cannot be located (empty file)
     * @throws CbsaAbendException {@code CVR1} on a data-store failure or when the last customer
     *     vanishes twice
     */
    public Optional<Customer> inquire(long customerNumber) {
        String sortCode = bank.sortCode();
        try {
            if (customerNumber == RANDOM_CUSTOMER) {
                return generateRandomCustomer(sortCode);
            }
            if (customerNumber == LAST_CUSTOMER) {
                return readLastCustomer(sortCode);
            }
            if (customerNumber < 0 || customerNumber > LAST_CUSTOMER) {
                return Optional.empty();
            }
            return customers.findByKey(sortCode, customerNumber);
        } catch (DataAccessException e) {
            throw new CbsaAbendException(ABEND_FILE_ERROR, PROGRAM,
                    "INQCUST: customer file read failed for customer number " + customerNumber + ".");
        }
    }

    /** Legacy {@code getCustomersByName}: case-insensitive contains, ordered by customer number. */
    public List<Customer> searchByName(String name, int limit, int offset) {
        return customers.findByNameContaining(bank.sortCode(), name, limit, offset);
    }

    /** Legacy {@code getCustomersByNameCountOnly}. */
    public long countByName(String name) {
        return customers.countByNameContaining(bank.sortCode(), name);
    }

    /** Legacy {@code getCustomers(limit, offset)}: all customers of this sort code, ordered by number. */
    public List<Customer> list(int limit, int offset) {
        return customers.findAll(bank.sortCode(), limit, offset);
    }

    /** Legacy {@code getCustomersCountOnly}. */
    public long count() {
        return customers.count(bank.sortCode());
    }

    /**
     * {@code READ-CUSTOMER-NCS} / {@code GET-LAST-CUSTOMER-VSAM} then {@code READ-CUSTOMER-VSAM}: the
     * last customer may be deleted between the browse and the read, so the browse is repeated once
     * ({@code WS-V-RETRIED}); a second miss abends CVR1.
     */
    private Optional<Customer> readLastCustomer(String sortCode) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<Customer> last = customers.findByKey(sortCode, highestCustomerNumber(sortCode));
            if (last.isPresent()) {
                return last;
            }
        }
        throw new CbsaAbendException(ABEND_FILE_ERROR, PROGRAM,
                "INQCUST: last customer could not be read (NOTFND after retry).");
    }

    /**
     * {@code GENERATE-RANDOM-CUSTOMER}: {@code ((NCS-CUST-NO-VALUE - 1) * FUNCTION RANDOM(EIBTASKN)) + 1},
     * re-drawn via {@code GENERATE-RANDOM-CUSTOMER-AGAIN} ({@code FUNCTION RANDOM}, same sequence) while
     * the key is NOTFND, up to {@value #MAX_RANDOM_RETRIES} times; then fail code 1.
     */
    private Optional<Customer> generateRandomCustomer(String sortCode) {
        long highest = highestCustomerNumber(sortCode);
        CbsaRandom random = randomFactory.stream(RANDOM_STREAM);
        for (int retry = 0; retry <= MAX_RANDOM_RETRIES; retry++) {
            Optional<Customer> customer = customers.findByKey(sortCode, randomCustomerNumber(highest, random));
            if (customer.isPresent()) {
                return customer;
            }
        }
        return Optional.empty();
    }

    /** COMPUTE into PIC 9(10): fraction truncated. */
    static long randomCustomerNumber(long highest, CbsaRandom random) {
        return (long) ((highest - 1) * random.nextDouble()) + 1;
    }

    /** {@code GET-LAST-CUSTOMER-VSAM}: STARTBR HIGH-VALUES + READPREV; NOTFND sets fail code 9. */
    private long highestCustomerNumber(String sortCode) {
        long highest = customers.maxCustomerNumber(sortCode);
        if (highest <= 0) {
            throw CbsaException.notFound("No customers found for sort code " + sortCode + ".", FAIL_NO_LAST_CUSTOMER);
        }
        return highest;
    }
}
