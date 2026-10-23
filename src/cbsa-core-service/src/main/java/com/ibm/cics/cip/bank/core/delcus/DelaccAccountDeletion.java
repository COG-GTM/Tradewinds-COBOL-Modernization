package com.ibm.cics.cip.bank.core.delcus;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.delacc.DeleteAccountService;
import org.springframework.stereotype.Component;

/**
 * Production {@link AccountDeletion}: delegates to DELACC ({@link DeleteAccountService}). The service joins the
 * caller's transaction (default {@code REQUIRED} propagation), so a DELCUS rollback also undoes account deletes.
 */
@Component
public class DelaccAccountDeletion implements AccountDeletion {

    private final DeleteAccountService deleteAccountService;

    public DelaccAccountDeletion(DeleteAccountService deleteAccountService) {
        this.deleteAccountService = deleteAccountService;
    }

    @Override
    public Account deleteAccount(long accountNumber) {
        return deleteAccountService.deleteAccount(accountNumber);
    }
}
