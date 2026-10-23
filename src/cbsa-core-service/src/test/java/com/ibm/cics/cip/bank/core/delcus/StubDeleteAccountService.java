package com.ibm.cics.cip.bank.core.delcus;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.domain.ProctranType;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.proctran.ProctranWriter;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;

/**
 * Test-only stand-in for {@code delacc.DeleteAccountService} (T-08) until that ticket lands: same behaviour
 * as DELACC (READ-ACCOUNT-DB2 -> 404 fail code 1, DEL-ACCOUNT-DB2, WRITE-PROCTRAN-DB2 ODA / HWPT).
 * Records every call in order and can be told to fail for a given account.
 */
class StubDeleteAccountService implements AccountDeletion {

    private final AccountRepository accounts;
    private final ProctranWriter proctran;
    private final BankIdentity bank;
    private final List<Long> calls = new ArrayList<>();
    private final Map<Long, RuntimeException> failures = new HashMap<>();
    private final Map<Long, Runnable> hooks = new HashMap<>();

    StubDeleteAccountService(AccountRepository accounts, ProctranWriter proctran, BankIdentity bank) {
        this.accounts = accounts;
        this.proctran = proctran;
        this.bank = bank;
    }

    @Override
    public Account deleteAccount(long accountNumber) {
        calls.add(accountNumber);
        RuntimeException failure = failures.get(accountNumber);
        if (failure != null) {
            throw failure;
        }
        Runnable hook = hooks.remove(accountNumber);
        if (hook != null) {
            hook.run();
        }
        Account account = accounts.findByKey(bank.sortCode(), accountNumber)
                .orElseThrow(() -> CbsaException.notFound("Account number " + accountNumber + " not found.", "1"));
        accounts.delete(bank.sortCode(), accountNumber);
        try {
            proctran.deleteAccount(ProctranType.BRANCH_DELETE_ACCOUNT, account);
        } catch (DataAccessException e) {
            throw new CbsaAbendException("HWPT", "DELACC", "Unable to WRITE to PROCTRAN DB2 datastore");
        }
        return account;
    }

    List<Long> calls() {
        return calls;
    }

    void failOn(long accountNumber, RuntimeException failure) {
        failures.put(accountNumber, failure);
    }

    /** Runs {@code action} (once) when DELACC is called for {@code accountNumber}. */
    void onDelete(long accountNumber, Runnable action) {
        hooks.put(accountNumber, action);
    }

    void reset() {
        calls.clear();
        failures.clear();
        hooks.clear();
    }
}
