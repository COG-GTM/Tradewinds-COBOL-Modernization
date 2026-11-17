package com.tradewinds.tradedesk.dbcrfun.entity;

import java.io.Serializable;
import java.util.Objects;

public class ProcTranId implements Serializable {

    private String sortcode;
    private String number;
    private String date;
    private String time;
    private String ref;

    public ProcTranId() {
    }

    public ProcTranId(String sortcode, String number, String date, String time, String ref) {
        this.sortcode = sortcode;
        this.number = number;
        this.date = date;
        this.time = time;
        this.ref = ref;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProcTranId other)) {
            return false;
        }
        return Objects.equals(sortcode, other.sortcode)
                && Objects.equals(number, other.number)
                && Objects.equals(date, other.date)
                && Objects.equals(time, other.time)
                && Objects.equals(ref, other.ref);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sortcode, number, date, time, ref);
    }
}
