package com.tradewinds.tradedesk.dbcrfun;

import java.math.BigDecimal;

/**
 * Output half of the PAYDBCR COMMAREA.
 *
 * @param success  COMM-SUCCESS PIC X, 'Y' or 'N'
 * @param failCode COMM-FAIL-CODE PIC X
 * @param sortc    COMM-SORTC, always the site sortcode
 * @param avBal    COMM-AV-BAL; null when DBCRFUN did not set it (see PARITY.md)
 * @param actBal   COMM-ACT-BAL; null when DBCRFUN did not set it (see PARITY.md)
 */
public record DbcrfunResponse(String success, String failCode, String sortc, BigDecimal avBal, BigDecimal actBal) {
}
