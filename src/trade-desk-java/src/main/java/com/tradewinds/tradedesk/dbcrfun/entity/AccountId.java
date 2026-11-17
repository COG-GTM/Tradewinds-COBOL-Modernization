package com.tradewinds.tradedesk.dbcrfun.entity;

import java.io.Serializable;
import java.util.Objects;

public class AccountId implements Serializable {

    private String sortcode;
    private String number;

    public AccountId() {
    }

    public AccountId(String sortcode, String number) {
        this.sortcode = sortcode;
        this.number = number;
    }

    public String getSortcode() {
        return sortcode;
    }

    public String getNumber() {
        return number;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AccountId other)) {
            return false;
        }
        return Objects.equals(sortcode, other.sortcode) && Objects.equals(number, other.number);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sortcode, number);
    }
}
