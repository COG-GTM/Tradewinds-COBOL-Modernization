package com.tradewinds.tradedesk.dbcrfun.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Table ACCOUNT (etc/install/base/db2jcl/CRETB01.jcl). */
@Entity
@Table(name = "ACCOUNT")
@IdClass(AccountId.class)
public class Account {

    @Column(name = "ACCOUNT_EYECATCHER", length = 4)
    private String eyecatcher;

    @Column(name = "ACCOUNT_CUSTOMER_NUMBER", length = 10)
    private String customerNumber;

    @Id
    @Column(name = "ACCOUNT_SORTCODE", length = 6, nullable = false)
    private String sortcode;

    @Id
    @Column(name = "ACCOUNT_NUMBER", length = 8, nullable = false)
    private String number;

    @Column(name = "ACCOUNT_TYPE", length = 8)
    private String accountType;

    @Column(name = "ACCOUNT_INTEREST_RATE", precision = 6, scale = 2)
    private BigDecimal interestRate;

    @Column(name = "ACCOUNT_OPENED")
    private LocalDate opened;

    @Column(name = "ACCOUNT_OVERDRAFT_LIMIT")
    private Integer overdraftLimit;

    @Column(name = "ACCOUNT_LAST_STATEMENT")
    private LocalDate lastStatement;

    @Column(name = "ACCOUNT_NEXT_STATEMENT")
    private LocalDate nextStatement;

    @Column(name = "ACCOUNT_AVAILABLE_BALANCE", precision = 12, scale = 2)
    private BigDecimal availableBalance;

    @Column(name = "ACCOUNT_ACTUAL_BALANCE", precision = 12, scale = 2)
    private BigDecimal actualBalance;

    public String getEyecatcher() {
        return eyecatcher;
    }

    public void setEyecatcher(String eyecatcher) {
        this.eyecatcher = eyecatcher;
    }

    public String getCustomerNumber() {
        return customerNumber;
    }

    public void setCustomerNumber(String customerNumber) {
        this.customerNumber = customerNumber;
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

    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }

    public BigDecimal getInterestRate() {
        return interestRate;
    }

    public void setInterestRate(BigDecimal interestRate) {
        this.interestRate = interestRate;
    }

    public LocalDate getOpened() {
        return opened;
    }

    public void setOpened(LocalDate opened) {
        this.opened = opened;
    }

    public Integer getOverdraftLimit() {
        return overdraftLimit;
    }

    public void setOverdraftLimit(Integer overdraftLimit) {
        this.overdraftLimit = overdraftLimit;
    }

    public LocalDate getLastStatement() {
        return lastStatement;
    }

    public void setLastStatement(LocalDate lastStatement) {
        this.lastStatement = lastStatement;
    }

    public LocalDate getNextStatement() {
        return nextStatement;
    }

    public void setNextStatement(LocalDate nextStatement) {
        this.nextStatement = nextStatement;
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    public void setAvailableBalance(BigDecimal availableBalance) {
        this.availableBalance = availableBalance;
    }

    public BigDecimal getActualBalance() {
        return actualBalance;
    }

    public void setActualBalance(BigDecimal actualBalance) {
        this.actualBalance = actualBalance;
    }
}
