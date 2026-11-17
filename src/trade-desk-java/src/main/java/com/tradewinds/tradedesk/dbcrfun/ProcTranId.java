package com.tradewinds.tradedesk.dbcrfun;

import java.io.Serializable;

/**
 * PROCTRAN has no primary key in Db2; JPA needs one, so the columns that
 * identify a single DBCRFUN write (account, date, time, task ref) are used.
 */
public record ProcTranId(String sortcode, String number, String date, String time, String ref)
        implements Serializable {

    public ProcTranId() {
        this(null, null, null, null, null);
    }
}
