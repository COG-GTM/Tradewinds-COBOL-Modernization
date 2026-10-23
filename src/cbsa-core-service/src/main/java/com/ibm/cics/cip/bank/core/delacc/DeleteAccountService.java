package com.ibm.cics.cip.bank.core.delacc;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.domain.ProctranType;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.proctran.ProctranWriter;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port of COBOL program DELACC (delete an account and audit it in PROCTRAN).
 *
 * <p>{@code PREMIERE}: {@code READ-ACCOUNT-DB2}; if found {@code DEL-ACCOUNT-DB2}; if deleted
 * {@code WRITE-PROCTRAN} ({@code WRITE-PROCTRAN-DB2}). The deleted row is returned (the COMMAREA output).
 */
@Service
public class DeleteAccountService {

    public static final String PROGRAM = "DELACC";
    /** {@code DADB010}: DELETE FROM ACCOUNT SQLCODE not 0. */
    public static final String FAIL_CODE_DELETE = "3";
    /** {@code RAD010}: ACCOUNT row select failed. */
    public static final String ABEND_READ = "HRAC";
    /** {@code WPD010}: PROCTRAN insert failed. */
    public static final String ABEND_PROCTRAN = "HWPT";

    private final AccountRepository accounts;
    private final ProctranWriter proctranWriter;
    private final BankIdentity bank;

    public DeleteAccountService(AccountRepository accounts, ProctranWriter proctranWriter, BankIdentity bank) {
        this.accounts = accounts;
        this.proctranWriter = proctranWriter;
        this.bank = bank;
    }

    /**
     * {@code PREMIERE} / {@code A010}.
     *
     * @return the account as it was before deletion
     * @throws AccountNotFoundException fail code 1 (no rollback of an enclosing transaction)
     * @throws CbsaException HTTP 500, fail code 3 when the DELETE fails
     * @throws CbsaAbendException {@code HRAC} (select failed) or {@code HWPT} (PROCTRAN write failed; the
     *         account delete is rolled back)
     */
    @Transactional(noRollbackFor = AccountNotFoundException.class)
    public Account deleteAccount(long accountNumber) {
        String sortCode = bank.sortCode();
        Account account = readAccount(sortCode, accountNumber);
        deleteAccountRow(sortCode, account);
        writeProctran(account);
        return account;
    }

    /** {@code READ-ACCOUNT-DB2} / {@code RAD010}. */
    private Account readAccount(String sortCode, long accountNumber) {
        try {
            return accounts.findByKey(sortCode, accountNumber)
                    .orElseThrow(() -> new AccountNotFoundException(accountNumber));
        } catch (DataAccessException e) {
            throw new CbsaAbendException(ABEND_READ, PROGRAM, "RAD010 - Issue with ACCOUNT row select, for Account "
                    + accountNumber + " and SORTCODE " + sortCode + ": " + e.getMostSpecificCause().getMessage());
        }
    }

    /** {@code DEL-ACCOUNT-DB2} / {@code DADB010}: any SQLCODE other than 0 sets fail code 3. */
    private void deleteAccountRow(String sortCode, Account account) {
        int deleted;
        try {
            deleted = accounts.delete(sortCode, account.accountNumber());
        } catch (DataAccessException e) {
            deleted = 0;
        }
        if (deleted != 1) {
            throw CbsaException.serverError("Unable to delete account " + account.accountNumber(), FAIL_CODE_DELETE);
        }
    }

    /**
     * {@code WRITE-PROCTRAN-DB2} / {@code WPD010}: type ODA (PROC-TY-BRANCH-DELETE-ACCOUNT), desc
     * custno + type + last/next statement DDMMYYYY + 'DELETE', amount = ACCOUNT-ACT-BAL-STORE.
     */
    private void writeProctran(Account account) {
        try {
            proctranWriter.deleteAccount(ProctranType.BRANCH_DELETE_ACCOUNT, account);
        } catch (DataAccessException e) {
            throw new CbsaAbendException(ABEND_PROCTRAN, PROGRAM,
                    "WPD010 - Unable to WRITE to PROCTRAN row datastore for account " + account.accountNumber()
                            + ": " + e.getMostSpecificCause().getMessage());
        }
    }
}
