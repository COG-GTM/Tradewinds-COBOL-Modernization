package com.ibm.cics.cip.bank.core.xfrfun;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import java.math.BigDecimal;

/**
 * Outcome of a successful XFRFUN call: the FROM and TO accounts after the update (COMM-FAVBAL/FACTBAL,
 * COMM-TAVBAL/TACTBAL) and the amount moved (COMM-AMT).
 */
public record TransferResult(Account from, Account to, BigDecimal amount) {
}
