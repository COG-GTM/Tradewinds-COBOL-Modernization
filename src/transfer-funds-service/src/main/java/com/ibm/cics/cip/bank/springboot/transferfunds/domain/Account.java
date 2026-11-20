/*
 *
 *    Copyright IBM Corp. 2023
 *
 */
package com.ibm.cics.cip.bank.springboot.transferfunds.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * JPA mapping of the Db2 ACCOUNT table used by XFRFUN. Column names match
 * the Db2 DDL so the same schema can be reused; field types mirror the
 * COBOL host variables (HOST-ACCOUNT-ROW).
 */
@Entity
@Table(name = "ACCOUNT")
@IdClass(AccountId.class)
public class Account
{
	@Column(name = "ACCOUNT_EYECATCHER", length = 4)
	private String eyecatcher;

	@Column(name = "ACCOUNT_CUSTOMER_NUMBER", length = 10)
	private String customerNumber;

	@Id
	@Column(name = "ACCOUNT_SORTCODE", length = 6)
	private String sortCode;

	@Id
	@Column(name = "ACCOUNT_NUMBER", length = 8)
	private String accountNumber;

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

	public String getSortCode()
	{
		return sortCode;
	}

	public String getAccountNumber()
	{
		return accountNumber;
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

	/**
	 * Debits this account, mirroring UPDATE-ACCOUNT-DB2-FROM: XFRFUN
	 * performs no overdraft-limit checking on transfers.
	 */
	public void debit(BigDecimal amount)
	{
		availableBalance = availableBalance.subtract(amount);
		actualBalance = actualBalance.subtract(amount);
	}

	/**
	 * Credits this account, mirroring UPDATE-ACCOUNT-DB2-TO.
	 */
	public void credit(BigDecimal amount)
	{
		availableBalance = availableBalance.add(amount);
		actualBalance = actualBalance.add(amount);
	}
}
