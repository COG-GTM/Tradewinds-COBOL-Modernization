package com.tradewinds.tradedesk.dbcrfun;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/** PROCTRAN table (src/base/cobol_copy/PROCDB2.cpy, etc/install/base/db2jcl/INSTDB2.jcl). */
@Entity
@Table(name = "PROCTRAN")
@IdClass(ProcTranId.class)
public class ProcTran implements Persistable<ProcTranId> {

    @Column(name = "PROCTRAN_EYECATCHER", length = 4)
    private String eyecatcher;

    @Id
    @Column(name = "PROCTRAN_SORTCODE", length = 6, nullable = false)
    private String sortcode;

    @Id
    @Column(name = "PROCTRAN_NUMBER", length = 8, nullable = false)
    private String number;

    /** DD.MM.YYYY, as written from HV-PROCTRAN-DATE PIC X(10). */
    @Id
    @Column(name = "PROCTRAN_DATE", length = 10)
    private String date;

    /** HHMMSS. */
    @Id
    @Column(name = "PROCTRAN_TIME", length = 6)
    private String time;

    @Id
    @Column(name = "PROCTRAN_REF", length = 12)
    private String ref;

    @Column(name = "PROCTRAN_TYPE", length = 3)
    private String type;

    @Column(name = "PROCTRAN_DESC", length = 40)
    private String desc;

    @Column(name = "PROCTRAN_AMOUNT", precision = 12, scale = 2)
    private BigDecimal amount;

    @Transient
    private boolean newRow = true;

    @Override
    public ProcTranId getId() {
        return new ProcTranId(sortcode, number, date, time, ref);
    }

    @Override
    public boolean isNew() {
        return newRow;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        newRow = false;
    }

    public String getEyecatcher() {
        return eyecatcher;
    }

    public void setEyecatcher(String eyecatcher) {
        this.eyecatcher = eyecatcher;
    }

    public String getSortcode() {
        return sortcode;
    }

    public void setSortcode(String sortcode) {
        this.sortcode = sortcode;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
