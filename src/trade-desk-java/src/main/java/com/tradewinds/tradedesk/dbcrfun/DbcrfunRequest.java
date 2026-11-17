package com.tradewinds.tradedesk.dbcrfun;

import java.math.BigDecimal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Input half of the PAYDBCR COMMAREA (src/base/cobol_copy/PAYDBCR.cpy).
 *
 * @param accNo  COMM-ACCNO PIC X(8); numeric account number, zero-filled to 8 digits
 * @param amt    COMM-AMT PIC S9(10)V99; negative = debit, zero or positive = credit
 * @param sortc  COMM-SORTC PIC 9(6); ignored, DBCRFUN overwrites it with the site sortcode
 * @param origin COMM-ORIGIN
 */
public record DbcrfunRequest(
        @NotNull @Pattern(regexp = "\\d{1,8}") String accNo,
        @NotNull @Digits(integer = 10, fraction = 2) BigDecimal amt,
        String sortc,
        @Valid DbcrfunOrigin origin) {
}
