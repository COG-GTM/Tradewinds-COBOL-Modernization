package com.ibm.cics.cip.bank.core.xfrfun;

import java.math.BigDecimal;

/** Body of {@code PUT /banking/account/transfer/{id}}: COMM-TACCNO and COMM-AMT. */
public record TransferRequest(Long targetAccount, BigDecimal amount) {
}
