package com.tradewinds.tradedesk.dbcrfun.dto;

import java.math.BigDecimal;

/** Output fields of the PAYDBCR COMMAREA. */
public record DbcrfunResponse(
        String success,
        String failCode,
        BigDecimal avBal,
        BigDecimal actBal) {

    public static DbcrfunResponse failure(String failCode) {
        return new DbcrfunResponse("N", failCode, null, null);
    }

    public static DbcrfunResponse failure(String failCode, BigDecimal avBal, BigDecimal actBal) {
        return new DbcrfunResponse("N", failCode, avBal, actBal);
    }

    public static DbcrfunResponse success(BigDecimal avBal, BigDecimal actBal) {
        return new DbcrfunResponse("Y", "0", avBal, actBal);
    }
}
