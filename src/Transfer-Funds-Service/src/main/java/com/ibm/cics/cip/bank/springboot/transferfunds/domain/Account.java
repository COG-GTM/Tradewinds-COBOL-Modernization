/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * JPA entity for the {@code ACCOUNT} datastore.
 *
 * <p>
 * Modernizes the Db2 {@code ACCOUNT} table accessed by XFRFUN (see the embedded
 * SQL in {@code XFRFUN.cbl} sections {@code UPDATE-ACCOUNT-DB2-FROM} and
 * {@code UPDATE-ACCOUNT-DB2-TO}) and the COBOL {@code ACCOUNT} copybook. The
 * COBOL host variables for monetary fields are {@code PIC S9(10)V99 COMP-3},
 * which map to a {@link BigDecimal} with scale 2.
 * </p>
 */
@Entity
@Table(name = "ACCOUNT")
public class Account
{

	/** COBOL {@code ACCOUNT-EYECATCHER-VALUE}. */
	public static final String EYECATCHER = "ACCT";

	@EmbeddedId
	private AccountKey key;

	@Column(name = "ACCOUNT_EYECATCHER", length = 4)
	private String eyeCatcher = EYECATCHER;

	@Column(name = "ACCOUNT_CUSTOMER_NUMBER", length = 10)
	private String customerNumber;

	@Column(name = "ACCOUNT_TYPE", length = 8)
	private String accountType;

	@Column(name = "ACCOUNT_INTEREST_RATE", precision = 6, scale = 2)
	private BigDecimal interestRate;

	@Column(name = "ACCOUNT_OPENED", length = 10)
	private String opened;

	@Column(name = "ACCOUNT_OVERDRAFT_LIMIT")
	private Integer overdraftLimit;

	@Column(name = "ACCOUNT_LAST_STATEMENT", length = 10)
	private String lastStatement;

	@Column(name = "ACCOUNT_NEXT_STATEMENT", length = 10)
	private String nextStatement;

	@Column(name = "ACCOUNT_AVAILABLE_BALANCE", precision = 12, scale = 2)
	private BigDecimal availableBalance;

	@Column(name = "ACCOUNT_ACTUAL_BALANCE", precision = 12, scale = 2)
	private BigDecimal actualBalance;

	public Account()
	{
	}

	public AccountKey getKey()
	{
		return key;
	}

	public void setKey(AccountKey key)
	{
		this.key = key;
	}

	public String getEyeCatcher()
	{
		return eyeCatcher;
	}

	public void setEyeCatcher(String eyeCatcher)
	{
		this.eyeCatcher = eyeCatcher;
	}

	public String getCustomerNumber()
	{
		return customerNumber;
	}

	public void setCustomerNumber(String customerNumber)
	{
		this.customerNumber = customerNumber;
	}

	public String getAccountType()
	{
		return accountType;
	}

	public void setAccountType(String accountType)
	{
		this.accountType = accountType;
	}

	public BigDecimal getInterestRate()
	{
		return interestRate;
	}

	public void setInterestRate(BigDecimal interestRate)
	{
		this.interestRate = interestRate;
	}

	public String getOpened()
	{
		return opened;
	}

	public void setOpened(String opened)
	{
		this.opened = opened;
	}

	public Integer getOverdraftLimit()
	{
		return overdraftLimit;
	}

	public void setOverdraftLimit(Integer overdraftLimit)
	{
		this.overdraftLimit = overdraftLimit;
	}

	public String getLastStatement()
	{
		return lastStatement;
	}

	public void setLastStatement(String lastStatement)
	{
		this.lastStatement = lastStatement;
	}

	public String getNextStatement()
	{
		return nextStatement;
	}

	public void setNextStatement(String nextStatement)
	{
		this.nextStatement = nextStatement;
	}

	public BigDecimal getAvailableBalance()
	{
		return availableBalance;
	}

	public void setAvailableBalance(BigDecimal availableBalance)
	{
		this.availableBalance = availableBalance;
	}

	public BigDecimal getActualBalance()
	{
		return actualBalance;
	}

	public void setActualBalance(BigDecimal actualBalance)
	{
		this.actualBalance = actualBalance;
	}

	@Override
	public String toString()
	{
		return "Account [key=" + key + ", availableBalance=" + availableBalance
				+ ", actualBalance=" + actualBalance + "]";
	}
}
