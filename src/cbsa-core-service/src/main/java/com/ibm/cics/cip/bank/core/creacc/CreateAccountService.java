package com.ibm.cics.cip.bank.core.creacc;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.counter.CounterService;
import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.domain.ProctranType;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.proctran.ProctranWriter;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import com.ibm.cics.cip.bank.core.common.repository.CustomerRepository;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port of CREACC (create account). Validation order follows {@code PREMIERE} (P010): customer exists
 * (INQCUST, fail {@code 1}), account count via INQACCCU ({@code CUSTOMER-ACCOUNT-COUNT}, fail {@code 9} on error,
 * fail {@code 8} when the customer already has more than 9 accounts), then {@code ACCOUNT-TYPE-CHECK} (fail
 * {@code A}). The account number is allocated by {@link CounterService#nextAccountNumber()} (replaces
 * {@code ENQ-NAMED-COUNTER} / {@code FIND-NEXT-ACCOUNT} / {@code DEQ-NAMED-COUNTER}) inside the same transaction as
 * {@code WRITE-ACCOUNT-DB2} and {@code WRITE-PROCTRAN-DB2}, so any failure rolls the counter back.
 */
@Service
public class CreateAccountService {

    public static final String PROGRAM = "CREACC";
    /** NUMBER-OF-ACCOUNTS IN INQACCCU-COMMAREA > 9 rejects the request. */
    static final int MAX_EXISTING_ACCOUNTS = 9;
    /** ACCOUNT-TYPE-CHECK: COMM-ACC-TYPE(1:n) must equal one of these. */
    static final List<String> ACCOUNT_TYPES = List.of("ISA", "MORTGAGE", "SAVING", "CURRENT", "LOAN");
    /** COMM-ACC-TYPE PIC X(8). */
    static final int ACCOUNT_TYPE_WIDTH = 8;

    private final CustomerRepository customers;
    private final AccountRepository accounts;
    private final CounterService counters;
    private final ProctranWriter proctran;
    private final BankIdentity bank;
    private final Clock clock;

    public CreateAccountService(CustomerRepository customers, AccountRepository accounts, CounterService counters,
            ProctranWriter proctran, BankIdentity bank, Clock clock) {
        this.customers = customers;
        this.accounts = accounts;
        this.counters = counters;
        this.proctran = proctran;
        this.bank = bank;
        this.clock = clock;
    }

    /**
     * CREACC {@code PREMIERE}: opens an account for {@code customerNumber} and returns the stored row.
     *
     * @throws CbsaException 404 fail {@code 1} customer not found; 400 fail {@code 8} too many accounts;
     *         400 fail {@code A} invalid account type; 500 fail {@code 9} account count failed; 500 fail
     *         {@code 7} ACCOUNT insert failed
     * @throws CbsaAbendException {@code HNCS} account counter failed, {@code HWPT} PROCTRAN insert failed
     */
    @Transactional
    public Account createAccount(long customerNumber, String accountType, BigDecimal interestRate,
            int overdraftLimit, BigDecimal availableBalance, BigDecimal actualBalance) {
        String sortCode = bank.sortCode();

        if (!customerExists(sortCode, customerNumber)) {
            throw CbsaException.notFound("Customer number " + customerNumber + " cannot be found.", "1");
        }

        if (customerAccountCount(sortCode, customerNumber) > MAX_EXISTING_ACCOUNTS) {
            throw CbsaException.badRequest(
                    "Customer number " + customerNumber + " cannot have more than ten accounts.", "8");
        }

        String type = CbsaFormats.fixed(accountType, ACCOUNT_TYPE_WIDTH);
        if (!isValidAccountType(type)) {
            throw CbsaException.badRequest("Account type " + (accountType == null ? "" : accountType.strip())
                    + " is not valid", "A");
        }

        long accountNumber = findNextAccount();

        LocalDate today = LocalDate.now(clock);
        Account account = new Account(Account.EYECATCHER, customerNumber, sortCode, accountNumber, type.stripTrailing(),
                rate(interestRate), today, overdraftLimit, today, StatementDates.nextStatement(today),
                CbsaFormats.money(orZero(availableBalance)), CbsaFormats.money(orZero(actualBalance)));

        writeAccount(account);
        writeProctran(account);
        return account;
    }

    /** ACCOUNT-TYPE-CHECK (ATC010): case-sensitive prefix match on the 8-character COMM-ACC-TYPE. */
    static boolean isValidAccountType(String accountType) {
        if (accountType == null) {
            return false;
        }
        for (String t : ACCOUNT_TYPES) {
            if (accountType.startsWith(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * P010 LINK INQCUST: INQCUST-INQ-SUCCESS = 'Y' only when the customer record exists. The customer row stays
     * locked until commit so concurrent creations for one customer cannot both pass the ten-account check.
     */
    private boolean customerExists(String sortCode, long customerNumber) {
        try {
            return customers.findByKeyForUpdate(sortCode, customerNumber).isPresent();
        } catch (DataAccessException e) {
            throw CbsaException.notFound("Customer number " + customerNumber + " cannot be found.", "1");
        }
    }

    /** CUSTOMER-ACCOUNT-COUNT (CAC010): LINK INQACCCU; any failure is fail code 9. */
    private long customerAccountCount(String sortCode, long customerNumber) {
        try {
            return accounts.countByCustomer(sortCode, customerNumber);
        } catch (DataAccessException e) {
            throw CbsaException.serverError("Error counting accounts for customer number " + customerNumber, "9");
        }
    }

    /** ENQ-NAMED-COUNTER + FIND-NEXT-ACCOUNT (FNA010): ACCOUNT-LAST + 1 and ACCOUNT-COUNT + 1, else abend HNCS. */
    private long findNextAccount() {
        try {
            return counters.nextAccountNumber();
        } catch (RuntimeException e) {
            throw new CbsaAbendException("HNCS", PROGRAM,
                    "FNAND010 - ACCOUNT NCS CBSAACCT" + bank.sortCode() + " Cannot be accessed: " + e.getMessage());
        }
    }

    /** WRITE-ACCOUNT-DB2 (WAD010): INSERT INTO ACCOUNT, SQLCODE not 0 is fail code 7. */
    private void writeAccount(Account account) {
        try {
            accounts.insert(account);
        } catch (DataAccessException e) {
            throw CbsaException.serverError("Failed to create account for customer number "
                    + account.customerNumber(), "7");
        }
    }

    /** WRITE-PROCTRAN-DB2 (WPD010): OCA row with amount 0; failure abends HWPT. */
    private void writeProctran(Account account) {
        try {
            proctran.createAccount(ProctranType.BRANCH_CREATE_ACCOUNT, account, BigDecimal.ZERO);
        } catch (DataAccessException e) {
            throw new CbsaAbendException("HWPT", PROGRAM, "Unable to write to PROCTRAN row datastore");
        }
    }

    /** COMM-INT-RT PIC 9(4)V99: MOVE truncates to 2 decimals. */
    private static BigDecimal rate(BigDecimal interestRate) {
        return orZero(interestRate).setScale(2, RoundingMode.DOWN);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
