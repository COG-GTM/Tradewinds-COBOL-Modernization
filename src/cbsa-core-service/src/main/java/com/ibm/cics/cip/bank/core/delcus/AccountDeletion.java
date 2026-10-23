package com.ibm.cics.cip.bank.core.delcus;

import com.ibm.cics.cip.bank.core.common.domain.Account;

/**
 * The DELACC call made by DELCUS {@code DELETE-ACCOUNTS} ({@code EXEC CICS LINK PROGRAM('DELACC')}) for
 * each account of the customer. Implemented in production by {@link DelaccAccountDeletion}, which delegates to
 * {@code delacc.DeleteAccountService#deleteAccount(long)} (ticket T-08).
 */
@FunctionalInterface
public interface AccountDeletion {

    /**
     * Deletes one account and writes its PROCTRAN {@code ODA} row.
     *
     * @return the deleted account
     * @throws com.ibm.cics.cip.bank.core.common.error.CbsaException if DELACC fails (not found, abend)
     */
    Account deleteAccount(long accountNumber);
}
