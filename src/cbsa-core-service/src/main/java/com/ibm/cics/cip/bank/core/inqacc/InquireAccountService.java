package com.ibm.cics.cip.bank.core.inqacc;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import com.ibm.cics.cip.bank.core.common.repository.CustomerRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port of the account enquiry programs:
 * <ul>
 *   <li><b>INQACC</b> ({@link #inquire(long)}): {@code PREMIERE} → {@code READ-ACCOUNT-DB2} / {@code FETCH-DATA}
 *       or, for account 99999999, {@code READ-ACCOUNT-LAST} / {@code GET-LAST-ACCOUNT-DB2}.</li>
 *   <li><b>INQACCCU</b> ({@link #accountsForCustomer(long)}): {@code PREMIERE} → {@code CUSTOMER-CHECK}
 *       (LINK INQCUST) → {@code READ-ACCOUNT-DB2} / {@code FETCH-DATA} (cursor, max 20 rows).</li>
 * </ul>
 * Read-only: neither program writes PROCTRAN or changes any table.
 */
@Service
public class InquireAccountService {

    /** INQACC-ACCNO value meaning "the highest-numbered account" (READ-ACCOUNT-LAST). */
    public static final long LAST_ACCOUNT = 99_999_999L;

    /** Largest PIC 9(8) account number. */
    public static final long MAX_ACCOUNT_NUMBER = 99_999_999L;

    /** INQACCCU CUSTOMER-CHECK rejects 9999999999 (INQCUST's "last customer" value) without a lookup. */
    public static final long LAST_CUSTOMER = 9_999_999_999L;

    /** INQACCCU COMMAREA: {@code ACCOUNT-DETAILS OCCURS 1 TO 20}; FETCH-DATA stops after 20 rows. */
    public static final int MAX_ACCOUNTS_PER_CUSTOMER = 20;

    /** INQACCCU COMM-FAIL-CODE '1': customer not found. */
    public static final String FAIL_CUSTOMER_NOT_FOUND = "1";

    /**
     * INQACCCU COMM-FAIL-CODE '2': DB2 cursor OPEN failed (RAD010). The COBOL also uses '3' (FETCH) and
     * '4' (CLOSE), but {@link AccountRepository#findByCustomer} runs open/fetch/close in one JDBC call that
     * cannot tell the phases apart, so every read failure reports '2'.
     */
    public static final String FAIL_CURSOR = "2";

    private final AccountRepository accounts;
    private final CustomerRepository customers;
    private final BankIdentity bank;

    public InquireAccountService(AccountRepository accounts, CustomerRepository customers, BankIdentity bank) {
        this.accounts = accounts;
        this.customers = customers;
        this.bank = bank;
    }

    /**
     * INQACC {@code PREMIERE}: the account with this number, or the last account for {@value #LAST_ACCOUNT}.
     * Empty when the account does not exist or its ACCOUNT_TYPE is blank ({@code INQACC-SUCCESS = 'N'}).
     *
     * @throws CbsaAbendException {@code HRAC} if the DB2 read fails (RAD010/FD010), {@code HNCS} if
     *         {@code GET-LAST-ACCOUNT-DB2} finds no account or fails
     */
    @Transactional(readOnly = true)
    public Optional<Account> inquire(long accountNumber) {
        Optional<Account> found = accountNumber == LAST_ACCOUNT ? readAccountLast() : readAccountDb2(accountNumber);
        return found.filter(a -> a.accountType() != null && !a.accountType().isBlank());
    }

    /**
     * INQACCCU {@code PREMIERE}: the customer's accounts ordered by account number (at most
     * {@value #MAX_ACCOUNTS_PER_CUSTOMER}, as the COMMAREA holds).
     *
     * @throws CbsaException 404 failCode 1 when {@code CUSTOMER-CHECK} sets CUSTOMER-FOUND = 'N';
     *         500 failCode 2 when the account cursor cannot be read
     */
    @Transactional(readOnly = true)
    public List<Account> accountsForCustomer(long customerNumber) {
        customerCheck(customerNumber);
        try {
            List<Account> rows = accounts.findByCustomer(bank.sortCode(), customerNumber);
            return rows.size() > MAX_ACCOUNTS_PER_CUSTOMER ? List.copyOf(rows.subList(0, MAX_ACCOUNTS_PER_CUSTOMER))
                    : rows;
        } catch (DataAccessException e) {
            throw cursorFailure(customerNumber, e);
        }
    }

    /** INQACCCU NUMBER-OF-ACCOUNTS without the details (REST {@code countOnly=true}); same checks and cap. */
    @Transactional(readOnly = true)
    public int countAccountsForCustomer(long customerNumber) {
        customerCheck(customerNumber);
        try {
            return (int) Math.min(accounts.countByCustomer(bank.sortCode(), customerNumber),
                    MAX_ACCOUNTS_PER_CUSTOMER);
        } catch (DataAccessException e) {
            throw cursorFailure(customerNumber, e);
        }
    }

    /** Paged list of all accounts of this bank ordered by account number (legacy {@code GET /banking/account}). */
    @Transactional(readOnly = true)
    public List<Account> listAccounts(int limit, int offset) {
        if (limit < 0 || offset < 0) {
            throw CbsaException.badRequest("limit and offset must not be negative");
        }
        return accounts.findAll(bank.sortCode(), limit, offset);
    }

    /** Number of accounts of this bank (legacy {@code getAccountsCountOnly}). */
    @Transactional(readOnly = true)
    public long countAccounts() {
        return accounts.count(bank.sortCode());
    }

    /** INQACC {@code READ-ACCOUNT-DB2} (OPEN / FETCH-DATA / CLOSE ACC-CURSOR); SQLCODE +100 = not found. */
    private Optional<Account> readAccountDb2(long accountNumber) {
        if (accountNumber < 0 || accountNumber > MAX_ACCOUNT_NUMBER) {
            return Optional.empty();
        }
        try {
            return accounts.findByKey(bank.sortCode(), accountNumber);
        } catch (DataAccessException e) {
            throw new CbsaAbendException("HRAC", "INQACC",
                    "RAD010 -Failure when attempting to read DB2 ACCOUNT for account " + accountNumber + ": "
                            + e.getMostSpecificCause().getMessage());
        }
    }

    /**
     * INQACC {@code READ-ACCOUNT-LAST} / {@code GET-LAST-ACCOUNT-DB2}:
     * {@code SELECT ... ORDER BY ACCOUNT_NUMBER DESC FETCH FIRST 1 ROWS ONLY}. Any non-zero SQLCODE,
     * including +100 on an empty table, abends HNCS.
     */
    private Optional<Account> readAccountLast() {
        try {
            Optional<Account> account = accounts.count(bank.sortCode()) == 0 ? Optional.empty()
                    : accounts.findByKey(bank.sortCode(), accounts.maxAccountNumber(bank.sortCode()));
            if (account.isEmpty()) {
                throw new CbsaAbendException("HNCS", "INQACC",
                        "GLAD010 -ACCOUNT NCS CANNOT be accessed and DB2 SELECT failed. SQLCODE=+100");
            }
            return account;
        } catch (DataAccessException e) {
            throw new CbsaAbendException("HNCS", "INQACC",
                    "GLAD010 -ACCOUNT NCS CANNOT be accessed and DB2 SELECT failed: "
                            + e.getMostSpecificCause().getMessage());
        }
    }

    /**
     * INQACCCU {@code CUSTOMER-CHECK}: customer 0 and 9999999999 are never found (INQCUST would treat them as
     * random/last); otherwise the INQCUST read of the customer record decides.
     */
    private void customerCheck(long customerNumber) {
        boolean found = customerNumber > 0 && customerNumber < LAST_CUSTOMER
                && customers.exists(bank.sortCode(), customerNumber);
        if (!found) {
            throw CbsaException.notFound("Customer number " + customerNumber + " cannot be found.",
                    FAIL_CUSTOMER_NOT_FOUND);
        }
    }

    private static CbsaException cursorFailure(long customerNumber, DataAccessException e) {
        CbsaException ex = CbsaException.serverError(
                "Accounts cannot be accessed for customer " + customerNumber + ".", FAIL_CURSOR);
        ex.initCause(e);
        return ex;
    }
}
