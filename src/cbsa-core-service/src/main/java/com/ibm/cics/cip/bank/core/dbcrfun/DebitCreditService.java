package com.ibm.cics.cip.bank.core.dbcrfun;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.proctran.ProctranWriter;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.math.BigDecimal;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port of DBCRFUN: debit (negative amount) or credit (positive amount) an account, from the teller (BNK1CRA / web
 * UI) or from the payment interface ({@code COMM-FACILTYPE = 496}).
 *
 * <p>Fail codes are those of {@code COMM-FAIL-CODE} (copybook PAYDBCR): {@code 1} account not found, {@code 2}
 * data-store error, {@code 3} insufficient funds (payment debit only), {@code 4} MORTGAGE/LOAN account cannot take
 * payments. Every failure is a {@link CbsaException} carrying that code; it rolls back the transaction like
 * {@code EXEC CICS SYNCPOINT ROLLBACK}.
 */
@Service
public class DebitCreditService {

    /** COMM-FACILTYPE value used by the payment interface (PAYDBCR). */
    public static final int PAYMENT_FACILITY_TYPE = 496;

    public static final String FAIL_NOT_FOUND = "1";
    public static final String FAIL_DATASTORE = "2";
    public static final String FAIL_INSUFFICIENT_FUNDS = "3";
    public static final String FAIL_ACCOUNT_TYPE = "4";

    /** PIC S9(10)V99 limit of COMM-AMT, ACCOUNT-AVAILABLE-BALANCE and ACCOUNT-ACTUAL-BALANCE. */
    static final BigDecimal MAX_MONEY = new BigDecimal("9999999999.99");

    private static final Logger log = LoggerFactory.getLogger(DebitCreditService.class);
    private static final String PROGRAM = "DBCRFUN";

    private final AccountRepository accounts;
    private final AccountRowLock rowLock;
    private final ProctranWriter proctran;
    private final BankIdentity bank;

    public DebitCreditService(AccountRepository accounts, AccountRowLock rowLock, ProctranWriter proctran,
            BankIdentity bank) {
        this.accounts = accounts;
        this.rowLock = rowLock;
        this.proctran = proctran;
        this.bank = bank;
    }

    /**
     * PREMIERE / A010 + UPDATE-ACCOUNT-DB2 (UAD010) + WRITE-TO-PROCTRAN (WTPD010).
     *
     * @param accountNumber COMM-ACCNO
     * @param amount        COMM-AMT; negative = debit, positive (or zero) = credit; truncated to 2 decimals
     * @param payment       {@code COMM-FACILTYPE = 496}: payment interface (funds and account-type checks, PDR/PCR)
     * @param origin14      COMM-ORIGIN(1:14) (applid(8) + userid(1:6)), the PROCTRAN description of a payment
     * @return the account with its new balances
     */
    @Transactional
    public Account debitCredit(long accountNumber, BigDecimal amount, boolean payment, String origin14) {
        if (amount == null) {
            throw CbsaException.badRequest("Amount is required");
        }
        BigDecimal amt = CbsaFormats.money(amount);
        String sortCode = bank.sortCode();

        Account account = readAccount(sortCode, accountNumber)
                .orElseThrow(() -> CbsaException.notFound("Account number " + accountNumber + " not found.",
                        FAIL_NOT_FOUND));

        // UAD010: the account-type test precedes the funds test, for debits and credits alike.
        if (payment && isMortgageOrLoan(account.accountType())) {
            throw new CbsaException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Account number " + accountNumber + " is a " + account.accountType().trim()
                            + " account and cannot take payments.", FAIL_ACCOUNT_TYPE);
        }
        // WS-DIFFERENCE = available balance + COMM-AMT; the overdraft limit is not considered (legacy behaviour).
        if (payment && amt.signum() < 0 && account.availableBalance().add(amt).signum() < 0) {
            throw new CbsaException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Insufficient funds in account number " + accountNumber + ".", FAIL_INSUFFICIENT_FUNDS);
        }

        BigDecimal newAvailable = account.availableBalance().add(amt);
        BigDecimal newActual = account.actualBalance().add(amt);
        if (newAvailable.abs().compareTo(MAX_MONEY) > 0 || newActual.abs().compareTo(MAX_MONEY) > 0) {
            throw CbsaException.serverError("Balance of account number " + accountNumber
                    + " would exceed PIC S9(10)V99.", FAIL_DATASTORE);
        }

        try {
            if (accounts.updateBalances(sortCode, accountNumber, newAvailable, newActual) != 1) {
                throw CbsaException.serverError("Unable to update account number " + accountNumber + ".",
                        FAIL_DATASTORE);
            }
            proctran.debitCredit(sortCode, accountNumber, amt, payment, origin14);
        } catch (DataAccessException e) {
            throw dataStoreFailure(e, accountNumber);
        }
        return account.withBalances(newAvailable, newActual);
    }

    /** UAD010 {@code SELECT ... FROM ACCOUNT}, under a row lock held to the end of the transaction. */
    private Optional<Account> readAccount(String sortCode, long accountNumber) {
        try {
            if (!rowLock.lock(sortCode, accountNumber)) {
                return Optional.empty();
            }
            return accounts.findByKey(sortCode, accountNumber);
        } catch (DataAccessException e) {
            throw dataStoreFailure(e, accountNumber);
        }
    }

    static boolean isMortgageOrLoan(String accountType) {
        String t = accountType == null ? "" : accountType.trim();
        return "MORTGAGE".equals(t) || "LOAN".equals(t);
    }

    /**
     * CHECK-FOR-STORM-DRAIN-DB2 + ABEND-HANDLING: a DB2 deadlock (AD2Z) abends the task again; every other SQL
     * failure is fail code 2 (connection loss is logged as a storm-drain condition).
     */
    private CbsaException dataStoreFailure(DataAccessException e, long accountNumber) {
        if (e instanceof PessimisticLockingFailureException) {
            log.error("DB2 DEADLOCK DETECTED IN DBCRFUN FOR ACCOUNT {}", accountNumber, e);
            return new CbsaAbendException("AD2Z", PROGRAM, "Deadlock updating account number " + accountNumber);
        }
        if (e instanceof DataAccessResourceFailureException) {
            log.error("DBCRFUN: Check-For-Storm-Drain-DB2: Storm Drain condition (DB2 Connection lost) has been met",
                    e);
        } else {
            log.error("DBCRFUN: data-store error for account {}", accountNumber, e);
        }
        return CbsaException.serverError("Unable to update account number " + accountNumber + ".", FAIL_DATASTORE);
    }
}
