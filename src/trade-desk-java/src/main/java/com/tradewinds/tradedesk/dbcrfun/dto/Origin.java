package com.tradewinds.tradedesk.dbcrfun.dto;

/** COMM-ORIGIN group of PAYDBCR.cpy. */
public record Origin(
        String applid,
        String userid,
        String facilityName,
        String netwrkId,
        int faciltype) {
}
