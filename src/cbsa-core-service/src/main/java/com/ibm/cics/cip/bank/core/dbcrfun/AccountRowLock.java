package com.ibm.cics.cip.bank.core.dbcrfun;

import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Takes a row lock on an ACCOUNT row for the rest of the caller's transaction ({@code SELECT ... FOR UPDATE}), so
 * the read-modify-write of UPDATE-ACCOUNT-DB2 cannot lose a concurrent update. Belongs in
 * {@code common.repository.AccountRepository} (requested as {@code findByKeyForUpdate}); kept here because T-09 may
 * not edit {@code common}.
 */
@Component
class AccountRowLock {

    private final JdbcClient jdbc;

    AccountRowLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if the row exists (and is now locked by the current transaction) */
    boolean lock(String sortCode, long accountNumber) {
        return jdbc.sql("SELECT ACCOUNT_NUMBER FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ? "
                        + "FOR UPDATE")
                .params(sortCode, CbsaFormats.accountNumber(accountNumber))
                .query(String.class)
                .optional()
                .isPresent();
    }
}
