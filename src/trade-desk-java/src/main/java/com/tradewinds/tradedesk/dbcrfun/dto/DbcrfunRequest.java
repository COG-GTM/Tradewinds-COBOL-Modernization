package com.tradewinds.tradedesk.dbcrfun.dto;

import java.math.BigDecimal;

/** Input fields of the PAYDBCR COMMAREA. */
public record DbcrfunRequest(
        String accno,
        BigDecimal amt,
        String sortc,
        Origin origin) {
}
