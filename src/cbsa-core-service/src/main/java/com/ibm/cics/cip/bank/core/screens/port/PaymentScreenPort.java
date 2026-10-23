package com.ibm.cics.cip.bank.core.screens.port;

import java.math.BigDecimal;

import com.ibm.cics.cip.bank.core.common.domain.Account;

/** DBCRFUN / XFRFUN as LINKed by BNK1CRA and BNK1TFN (wave-1 DebitCreditService, TransferService). */
public interface PaymentScreenPort {

    /** DBCRFUN; {@code payment=false} for teller (screen) transactions. */
    Account debitCredit(long accountNumber, BigDecimal amount, boolean payment, String origin14);

    /** XFRFUN. */
    Transfer transfer(long fromAccount, long toAccount, BigDecimal amount);

    /** Both accounts after a successful transfer. */
    record Transfer(Account from, Account to) {
    }
}
