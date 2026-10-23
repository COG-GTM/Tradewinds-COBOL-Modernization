package com.ibm.cics.cip.bank.core.creacc;

import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Row lock on the CUSTOMER record, held until the caller's transaction ends. It serialises concurrent account
 * creations for the same customer so the ten-account check and the insert cannot interleave.
 */
@Component
class CustomerLock {

    private final JdbcClient jdbc;

    CustomerLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** SELECT ... FOR UPDATE; returns false when the customer does not exist. */
    boolean lockIfExists(String sortCode, long customerNumber) {
        return jdbc.sql("SELECT CUSTOMER_NUMBER FROM CUSTOMER WHERE CUSTOMER_SORTCODE = ? AND CUSTOMER_NUMBER = ? "
                        + "FOR UPDATE")
                .param(sortCode).param(CbsaFormats.customerNumber(customerNumber))
                .query(String.class).optional().isPresent();
    }
}
