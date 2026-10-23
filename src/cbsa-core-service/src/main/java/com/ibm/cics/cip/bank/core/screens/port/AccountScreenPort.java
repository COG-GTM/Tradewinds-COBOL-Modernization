package com.ibm.cics.cip.bank.core.screens.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.ibm.cics.cip.bank.core.common.domain.Account;

/**
 * Account business services the screens LINK to (wave-1 InquireAccountService, CreateAccountService,
 * UpdateAccountService, DeleteAccountService). Fail codes travel in
 * {@link com.ibm.cics.cip.bank.core.common.error.CbsaException#failCode()}.
 */
public interface AccountScreenPort {

    /** INQACC. */
    Optional<Account> inquireAccount(long accountNumber);

    /** INQACCCU; throws CbsaException fail code 1 when the customer does not exist. */
    List<Account> accountsForCustomer(long customerNumber);

    /** CREACC. */
    Account createAccount(long customerNumber, String type, BigDecimal interestRate, int overdraftLimit,
            BigDecimal availableBalance, BigDecimal actualBalance);

    /** UPDACC. */
    Account updateAccount(long accountNumber, String type, BigDecimal interestRate, int overdraftLimit);

    /** DELACC. */
    Account deleteAccount(long accountNumber);
}
