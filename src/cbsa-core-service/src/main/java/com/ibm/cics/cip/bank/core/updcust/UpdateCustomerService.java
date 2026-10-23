package com.ibm.cics.cip.bank.core.updcust;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.repository.CustomerRepository;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port of UPDCUST (src/base/cobol_src/UPDCUST.cbl): updates the name and/or address of a customer.
 * Date of birth, credit score and review date are never changed. UPDCUST writes no PROCTRAN row.
 *
 * <p>Fail codes (COMM-UPD-FAIL-CD): {@code T} invalid title, {@code 1} customer not found,
 * {@code 2} datastore read error, {@code 3} rewrite error, {@code 4} name and address both blank.
 */
@Service
public class UpdateCustomerService {

    /** PREMIERE / A010 EVALUATE WS-UNSTR-TITLE (exact, case-sensitive; blank is accepted). */
    static final Set<String> VALID_TITLES =
            Set.of("Professor", "Mr", "Mrs", "Miss", "Ms", "Dr", "Drs", "Lord", "Sir", "Lady", "");

    /** WS-UNSTR-TITLE PIC X(9). */
    static final int TITLE_LENGTH = 9;
    /** COMM-NAME PIC X(60). */
    static final int NAME_LENGTH = 60;
    /** COMM-ADDR PIC X(160). */
    static final int ADDRESS_LENGTH = 160;

    public static final String FAIL_TITLE = "T";
    public static final String FAIL_NOT_FOUND = "1";
    public static final String FAIL_READ = "2";
    public static final String FAIL_REWRITE = "3";
    public static final String FAIL_BOTH_BLANK = "4";

    private final CustomerRepository customers;
    private final BankIdentity bank;
    private final JdbcClient jdbc;

    public UpdateCustomerService(CustomerRepository customers, BankIdentity bank, JdbcClient jdbc) {
        this.customers = customers;
        this.bank = bank;
        this.jdbc = jdbc;
    }

    /**
     * UPDCUST PREMIERE + UPDATE-CUSTOMER-VSAM.
     *
     * @param customerNumber COMM-CUSTNO
     * @param name           COMM-NAME (null or blank / leading space = keep the current name)
     * @param address        COMM-ADDR (null or blank / leading space = keep the current address)
     * @return the customer record as rewritten (COMM-EYE .. COMM-CS-REVIEW-DATE on success)
     * @throws CbsaException with the legacy fail code when COMM-UPD-SUCCESS would be 'N'
     */
    @Transactional
    public Customer updateCustomer(long customerNumber, String name, String address) {
        String commName = cobolField(name, NAME_LENGTH);
        String commAddr = cobolField(address, ADDRESS_LENGTH);

        // A010: UNSTRING COMM-NAME DELIMITED BY SPACE INTO WS-UNSTR-TITLE, then EVALUATE.
        String title = title(commName);
        if (!VALID_TITLES.contains(title)) {
            throw CbsaException.badRequest("Customer title " + firstToken(commName) + " is not valid", FAIL_TITLE);
        }
        return updateCustomerVsam(customerNumber, commName, commAddr);
    }

    /** UPDATE-CUSTOMER-VSAM / UCV010 .. UCV999. */
    private Customer updateCustomerVsam(long customerNumber, String commName, String commAddr) {
        String sortCode = bank.sortCode();

        // EXEC CICS READ FILE('CUSTOMER') RIDFLD(DESIRED-CUST-KEY) UPDATE
        Customer current;
        try {
            current = readForUpdate(sortCode, customerNumber).orElse(null);
        } catch (DataAccessException e) {
            throw CbsaException.serverError("Sorry but a datastore error occurred. Customer NOT updated.", FAIL_READ);
        }
        if (current == null) {
            throw CbsaException.notFound("Customer " + customerNumber + " not found.", FAIL_NOT_FOUND);
        }

        boolean nameBlank = isBlank(commName);
        boolean addrBlank = isBlank(commAddr);
        if (nameBlank && addrBlank) {
            throw CbsaException.badRequest(
                    "Customer name and address cannot both be blank. Customer NOT updated.", FAIL_BOTH_BLANK);
        }

        String newName = nameBlank ? current.name() : commName.stripTrailing();
        String newAddress = addrBlank ? current.address() : commAddr.stripTrailing();
        Customer updated = current.withNameAndAddress(newName, newAddress);

        // EXEC CICS REWRITE FILE('CUSTOMER') FROM(WS-CUST-DATA)
        int rows;
        try {
            rows = customers.update(updated);
        } catch (DataAccessException e) {
            rows = 0;
        }
        if (rows != 1) {
            throw CbsaException.serverError("Sorry but an update error occurred. Customer NOT updated.", FAIL_REWRITE);
        }
        return updated;
    }

    /** READ ... UPDATE: row-lock the record for the rest of the transaction, then read it. */
    private Optional<Customer> readForUpdate(String sortCode, long customerNumber) {
        Optional<String> locked = jdbc.sql("SELECT CUSTOMER_NUMBER FROM CUSTOMER "
                        + "WHERE CUSTOMER_SORTCODE = ? AND CUSTOMER_NUMBER = ? FOR UPDATE")
                .params(sortCode, CbsaFormats.customerNumber(customerNumber))
                .query(String.class)
                .optional();
        return locked.isPresent() ? customers.findByKey(sortCode, customerNumber) : Optional.empty();
    }

    /** MOVE into PIC X(n): null becomes spaces (represented as ""), longer values are truncated. */
    static String cobolField(String value, int width) {
        if (value == null) {
            return "";
        }
        return value.length() > width ? value.substring(0, width) : value;
    }

    /** {@code field = SPACES OR field(1:1) = ' '}. */
    static boolean isBlank(String field) {
        return field.isEmpty() || field.charAt(0) == ' ';
    }

    /** First space-delimited token of COMM-NAME, truncated to WS-UNSTR-TITLE PIC X(9). */
    static String title(String commName) {
        String token = firstToken(commName);
        return token.length() > TITLE_LENGTH ? token.substring(0, TITLE_LENGTH) : token;
    }

    private static String firstToken(String commName) {
        int space = commName.indexOf(' ');
        return space < 0 ? commName : commName.substring(0, space);
    }
}
