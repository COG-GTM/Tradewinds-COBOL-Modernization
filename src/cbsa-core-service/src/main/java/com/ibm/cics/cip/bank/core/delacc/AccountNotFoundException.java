package com.ibm.cics.cip.bank.core.delacc;

import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import org.springframework.http.HttpStatus;

/**
 * DELACC READ-ACCOUNT-DB2 SQLCODE +100: {@code DELACC-DEL-SUCCESS = 'N'}, {@code DELACC-DEL-FAIL-CD = '1'}.
 * Nothing has been written yet, so it does not roll back a caller's transaction (e.g. DELCUS).
 */
public class AccountNotFoundException extends CbsaException {

    public static final String FAIL_CODE = "1";

    public AccountNotFoundException(long accountNumber) {
        super(HttpStatus.NOT_FOUND, "Account " + accountNumber + " not found", FAIL_CODE);
    }
}
