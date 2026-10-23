package com.ibm.cics.cip.bank.core.xfrfun;

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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port of XFRFUN: transfer funds between two accounts of this bank.
 *
 * <p>Legacy behaviour kept on purpose (baseline/README.md defects 6-8): there is no insufficient-funds or
 * overdraft check, a transfer to the same account is an abend ({@code SAME}), and a missing TO account rolls
 * back the already-applied FROM update.
 */
@Service
public class TransferService {

    static final String PROGRAM = "XFRFUN";

    /** Largest value of COMM-AMT {@code PIC S9(10)V99}. */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    /** Largest value of COMM-FACCNO / COMM-TACCNO {@code PIC 9(8)}. */
    static final long MAX_ACCOUNT_NUMBER = 99_999_999L;

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final AccountRepository accounts;
    private final ProctranWriter proctranWriter;
    private final BankIdentity bank;

    public TransferService(AccountRepository accounts, ProctranWriter proctranWriter, BankIdentity bank) {
        this.accounts = accounts;
        this.proctranWriter = proctranWriter;
        this.bank = bank;
    }

    /**
     * {@code PREMIERE} / {@code A010}: validates the amount, then {@code UPDATE-ACCOUNT-DB2}, then
     * {@code WRITE-TO-PROCTRAN}. One unit of work: every failure after the first UPDATE rolls back (the legacy
     * {@code EXEC CICS SYNCPOINT ROLLBACK} / abend backout).
     *
     * <p>REPEATABLE READ makes a concurrent change to either row between the SELECT and the UPDATE fail the
     * transfer (rolled back, fail code 3) instead of silently overwriting it, matching the row-lock protection
     * DB2 gives the legacy SELECT INTO / UPDATE pair.
     *
     * @param fromAccount COMM-FACCNO
     * @param toAccount COMM-TACCNO
     * @param amount COMM-AMT (truncated to 2 decimals as a MOVE into {@code PIC S9(10)V99} does)
     * @throws CbsaException 400 fail code 4 when amount is missing or &lt;= 0; 404 fail code 1 / 2 when the
     *         FROM / TO account does not exist
     * @throws TransferAbendException 400 abend {@code SAME}; 500 fail code 3 abend {@code FROM} / {@code TO  }
     * @throws CbsaAbendException 500 abend {@code WPCD} when the PROCTRAN insert fails
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public TransferResult transfer(long fromAccount, long toAccount, BigDecimal amount) {
        checkAccountNumber(fromAccount, "From");
        checkAccountNumber(toAccount, "Target");
        BigDecimal amt = amount == null ? BigDecimal.ZERO : CbsaFormats.money(amount);
        // A010: IF COMM-AMT <= ZERO -> COMM-FAIL-CODE '4'
        if (amt.signum() <= 0) {
            throw CbsaException.badRequest("Amount must be greater than zero.", "4");
        }
        if (amt.compareTo(MAX_AMOUNT) > 0) {
            throw CbsaException.badRequest("Amount exceeds the maximum of " + MAX_AMOUNT + ".");
        }
        String sortCode = bank.sortCode();
        // UAD010: same account (COMM-FSCODE = COMM-TSCODE = SORTCODE) -> ABEND 'SAME'
        if (fromAccount == toAccount) {
            throw new TransferAbendException(HttpStatus.BAD_REQUEST, "SAME", null,
                    "Cannot transfer to the same account");
        }
        Account from;
        Account to;
        // UAD010: update the lower account number first to avoid deadlocks.
        if (fromAccount < toAccount) {
            from = updateFrom(sortCode, fromAccount, amt);
            to = updateTo(sortCode, toAccount, amt);
        } else {
            to = updateTo(sortCode, toAccount, amt);
            from = updateFrom(sortCode, fromAccount, amt);
        }
        writeToProctran(sortCode, fromAccount, toAccount, amt);
        return new TransferResult(from, to, amt);
    }

    /** {@code UPDATE-ACCOUNT-DB2-FROM} / {@code UADF010}: SELECT, subtract COMM-AMT from both balances, UPDATE. */
    private Account updateFrom(String sortCode, long accountNumber, BigDecimal amount) {
        Account current = select(sortCode, accountNumber, "FROM")
                .orElseThrow(() -> CbsaException.notFound("Account number " + accountNumber + " not found.", "1"));
        Account updated = current.withBalances(current.availableBalance().subtract(amount),
                current.actualBalance().subtract(amount));
        update(updated, "FROM");
        return updated;
    }

    /** {@code UPDATE-ACCOUNT-DB2-TO} / {@code UADT010}: SELECT, add COMM-AMT to both balances, UPDATE. */
    private Account updateTo(String sortCode, long accountNumber, BigDecimal amount) {
        Account current = select(sortCode, accountNumber, "TO  ")
                .orElseThrow(() -> CbsaException.notFound(
                        "Target account number " + accountNumber + " not found.", "2"));
        Account updated = current.withBalances(current.availableBalance().add(amount),
                current.actualBalance().add(amount));
        update(updated, "TO  ");
        return updated;
    }

    private Optional<Account> select(String sortCode, long accountNumber, String abendCode) {
        try {
            return accounts.findByKey(sortCode, accountNumber);
        } catch (DataAccessException e) {
            throw updateFailed(abendCode, accountNumber, e);
        }
    }

    private void update(Account updated, String abendCode) {
        int rows;
        try {
            rows = accounts.updateBalances(updated.sortCode(), updated.accountNumber(), updated.availableBalance(),
                    updated.actualBalance());
        } catch (DataAccessException e) {
            throw updateFailed(abendCode, updated.accountNumber(), e);
        }
        if (rows != 1) {
            throw updateFailed(abendCode, updated.accountNumber(), null);
        }
    }

    /**
     * Any SQLCODE other than 0 / +100 sets COMM-FAIL-CODE '3' ({@code CHECK-FOR-STORM-DRAIN-DB2}) and UAD010
     * abends with {@code FROM} or {@code TO  } (texts UAD010(3), (4), (6)).
     */
    private TransferAbendException updateFailed(String abendCode, long accountNumber, DataAccessException cause) {
        String which = "FROM".equals(abendCode) ? "FROM" : "TO";
        log.error("XFRFUN: error updating {} account {}", which, accountNumber, cause);
        TransferAbendException e = new TransferAbendException(HttpStatus.INTERNAL_SERVER_ERROR, abendCode, "3",
                "UAD010 - Error updating " + which + " account " + CbsaFormats.accountNumber(accountNumber));
        if (cause != null) {
            e.initCause(cause);
        }
        return e;
    }

    /** {@code WRITE-TO-PROCTRAN} / {@code WRITE-TO-PROCTRAN-DB2}: one TFR row on the FROM account. */
    private void writeToProctran(String sortCode, long fromAccount, long toAccount, BigDecimal amount) {
        try {
            proctranWriter.transfer(sortCode, fromAccount, sortCode, toAccount, amount);
        } catch (DataAccessException e) {
            log.error("XFRFUN: PROCTRAN insert failed", e);
            throw new CbsaAbendException("WPCD", PROGRAM,
                    "WTPD010 - Unable to WRITE to PROCTRAN DB2 datastore");
        }
    }

    private static void checkAccountNumber(long accountNumber, String label) {
        if (accountNumber < 0 || accountNumber > MAX_ACCOUNT_NUMBER) {
            throw CbsaException.badRequest(label + " account number " + accountNumber
                    + " is not valid (0-" + MAX_ACCOUNT_NUMBER + ").");
        }
    }
}
