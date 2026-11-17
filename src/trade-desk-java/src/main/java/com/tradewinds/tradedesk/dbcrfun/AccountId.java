package com.tradewinds.tradedesk.dbcrfun;

import java.io.Serializable;

public record AccountId(String sortcode, String number) implements Serializable {

    public AccountId() {
        this(null, null);
    }
}
