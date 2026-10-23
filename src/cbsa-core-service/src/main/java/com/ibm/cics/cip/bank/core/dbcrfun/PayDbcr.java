package com.ibm.cics.cip.bank.core.dbcrfun;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * JSON form of the PAYDBCR COMMAREA used by the z/OS Connect {@code makepayment} API
 * ({@code {"PAYDBCR": {...}}}). Field names match {@code Z-OS-Connect-Payment-Interface} {@code DbcrJson}/{@code
 * OriginJson}; {@code CommFaciltype} accepts a number or a numeric string ("0496").
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PayDbcr(
        @JsonProperty("CommAccno") String commAccno,
        @JsonProperty("CommAmt") BigDecimal commAmt,
        @JsonProperty("mSortC") Integer mSortC,
        @JsonProperty("CommAvBal") BigDecimal commAvBal,
        @JsonProperty("CommActBal") BigDecimal commActBal,
        @JsonProperty("CommOrigin") Origin commOrigin,
        @JsonProperty("CommSuccess") String commSuccess,
        @JsonProperty("CommFailCode") String commFailCode) {

    /** COMM-ORIGIN. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Origin(
            @JsonProperty("CommApplid") String commApplid,
            @JsonProperty("CommUserid") String commUserid,
            @JsonProperty("CommFacilityName") String commFacilityName,
            @JsonProperty("CommNetwrkId") String commNetwrkId,
            @JsonProperty("CommFaciltype") Integer commFaciltype,
            @JsonProperty("Fill0") String fill0) {
    }

    /** Request/response envelope. */
    public record Envelope(@JsonProperty("PAYDBCR") PayDbcr paydbcr) {
    }

    public PayDbcr withResult(int sortCode, BigDecimal availableBalance, BigDecimal actualBalance, String success,
            String failCode) {
        return new PayDbcr(commAccno, commAmt, sortCode, orZero(availableBalance), orZero(actualBalance), commOrigin,
                success, failCode);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
