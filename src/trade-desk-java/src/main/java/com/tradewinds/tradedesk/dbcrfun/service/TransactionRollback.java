package com.tradewinds.tradedesk.dbcrfun.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/** Marks the current transaction rollback-only; replaces EXEC CICS SYNCPOINT ROLLBACK. */
@Component
public class TransactionRollback {

    public void markRollbackOnly() {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
    }
}
