package com.tradewinds.tradedesk.dbcrfun;

import jakarta.validation.constraints.Size;

import com.tradewinds.tradedesk.config.CobolText;

/**
 * COMM-ORIGIN group of the PAYDBCR COMMAREA.
 *
 * @param applid       COMM-APPLID PIC X(8)
 * @param userid       COMM-USERID PIC X(8)
 * @param facilityName COMM-FACILITY-NAME PIC X(8)
 * @param networkId    COMM-NETWRK-ID PIC X(8)
 * @param facilType    COMM-FACILTYPE PIC S9(8) COMP; 496 means a PAYMENT request, anything else a teller
 */
public record DbcrfunOrigin(
        @Size(max = 8) String applid,
        @Size(max = 8) String userid,
        @Size(max = 8) String facilityName,
        @Size(max = 8) String networkId,
        int facilType) {

    /**
     * {@code COMM-ORIGIN(1:n)} for n &lt;= 32: the first n bytes of the
     * space-padded APPLID, USERID, FACILITY-NAME and NETWRK-ID fields.
     */
    public String commareaPrefix(int length) {
        String bytes = CobolText.moveToPicX(applid, 8)
                + CobolText.moveToPicX(userid, 8)
                + CobolText.moveToPicX(facilityName, 8)
                + CobolText.moveToPicX(networkId, 8);
        return bytes.substring(0, Math.min(length, bytes.length()));
    }
}
