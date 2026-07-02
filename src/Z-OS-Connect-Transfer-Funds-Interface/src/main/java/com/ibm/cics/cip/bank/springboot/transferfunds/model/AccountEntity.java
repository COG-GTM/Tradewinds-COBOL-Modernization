/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * JPA entity mapping the Db2 ACCOUNT table.
 *
 * COBOL source: ACCDB2.cpy / HOST-ACCOUNT-ROW in XFRFUN.cbl
 *
 * Key COBOL types preserved:
 *   ACCOUNT_AVAILABLE_BALANCE  DECIMAL(10,2)  -> BigDecimal
 *   ACCOUNT_ACTUAL_BALANCE     DECIMAL(10,2)  -> BigDecimal
 *   ACCOUNT_INTEREST_RATE      DECIMAL(4,2)   -> BigDecimal
 *   ACCOUNT_OVERDRAFT_LIMIT    INTEGER        -> int
 */
@Entity
@Table(name = "ACCOUNT")
@IdClass(AccountKey.class)
public class AccountEntity
{


	@Column(name = "ACCOUNT_EYECATCHER", length = 4)
	private String eyecatcher;

	@Column(name = "ACCOUNT_CUSTOMER_NUMBER", length = 10)
	private String customerNumber;

	@Id
	@Column(name = "ACCOUNT_SORTCODE", length = 6, nullable = false)
	private String sortcode;

	@Id
	@Column(name = "ACCOUNT_NUMBER", length = 8, nullable = false)
	private String accountNumber;

	@Column(name = "ACCOUNT_TYPE", length = 8)
	private String accountType;

	@Column(name = "ACCOUNT_INTEREST_RATE", precision = 4, scale = 2)
	private BigDecimal interestRate;

	@Column(name = "ACCOUNT_OPENED")
	private LocalDate opened;

	@Column(name = "ACCOUNT_OVERDRAFT_LIMIT")
	private int overdraftLimit;

	@Column(name = "ACCOUNT_LAST_STATEMENT")
	private LocalDate lastStatement;

	@Column(name = "ACCOUNT_NEXT_STATEMENT")
	private LocalDate nextStatement;

	@Column(name = "ACCOUNT_AVAILABLE_BALANCE", precision = 12, scale = 2)
	private BigDecimal availableBalance;

	@Column(name = "ACCOUNT_ACTUAL_BALANCE", precision = 12, scale = 2)
	private BigDecimal actualBalance;


	public AccountEntity()
	{
	}


	public String getEyecatcher()
	{
		return eyecatcher;
	}


	public void setEyecatcher(String eyecatcher)
	{
		this.eyecatcher = eyecatcher;
	}


	public String getCustomerNumber()
	{
		return customerNumber;
	}


	public void setCustomerNumber(String customerNumber)
	{
		this.customerNumber = customerNumber;
	}


	public String getSortcode()
	{
		return sortcode;
	}


	public void setSortcode(String sortcode)
	{
		this.sortcode = sortcode;
	}


	public String getAccountNumber()
	{
		return accountNumber;
	}


	public void setAccountNumber(String accountNumber)
	{
		this.accountNumber = accountNumber;
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


	public LocalDate getOpened()
	{
		return opened;
	}


	public void setOpened(LocalDate opened)
	{
		this.opened = opened;
	}


	public int getOverdraftLimit()
	{
		return overdraftLimit;
	}


	public void setOverdraftLimit(int overdraftLimit)
	{
		this.overdraftLimit = overdraftLimit;
	}


	public LocalDate getLastStatement()
	{
		return lastStatement;
	}


	public void setLastStatement(LocalDate lastStatement)
	{
		this.lastStatement = lastStatement;
	}


	public LocalDate getNextStatement()
	{
		return nextStatement;
	}


	public void setNextStatement(LocalDate nextStatement)
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
}
