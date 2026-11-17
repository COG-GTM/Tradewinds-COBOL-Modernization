package com.tradewinds.tradedesk.dbcrfun;

import org.springframework.dao.DataAccessException;

/** The PROCTRAN INSERT failed (WTPD010, SQLCODE NOT = 0). */
class ProctranWriteException extends RuntimeException {

    ProctranWriteException(DataAccessException cause) {
        super("UNABLE TO WRITE TO PROCTRAN DB2 DATASTORE", cause);
    }

    @Override
    public synchronized DataAccessException getCause() {
        return (DataAccessException) super.getCause();
    }
}
