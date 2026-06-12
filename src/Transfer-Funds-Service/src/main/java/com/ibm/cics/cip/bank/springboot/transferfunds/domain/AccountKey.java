/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.domain;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Composite primary key for the ACCOUNT table.
 *
 * <p>
 * Mirrors the COBOL {@code ACCOUNT-KEY} group from the {@code ACCOUNT} copybook,
 * which is made up of the 6-digit sort code and the 8-digit account number. In
 * XFRFUN this key is used as the {@code WHERE} clause when selecting and
 * updating both the FROM and TO accounts.
 * </p>
 */
@Embeddable
public class AccountKey implements Serializable
{

	private static final long serialVersionUID = 1L;

	@Column(name = "ACCOUNT_SORTCODE", nullable = false, length = 6)
	private String sortCode;

	@Column(name = "ACCOUNT_NUMBER", nullable = false, length = 8)
	private String accountNumber;

	public AccountKey()
	{
	}

	public AccountKey(String sortCode, String accountNumber)
	{
		this.sortCode = sortCode;
		this.accountNumber = accountNumber;
	}

	public String getSortCode()
	{
		return sortCode;
	}

	public void setSortCode(String sortCode)
	{
		this.sortCode = sortCode;
	}

	public String getAccountNumber()
	{
		return accountNumber;
	}

	public void setAccountNumber(String accountNumber)
	{
		this.accountNumber = accountNumber;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof AccountKey))
		{
			return false;
		}
		AccountKey that = (AccountKey) o;
		return Objects.equals(sortCode, that.sortCode)
				&& Objects.equals(accountNumber, that.accountNumber);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(sortCode, accountNumber);
	}

	@Override
	public String toString()
	{
		return sortCode + "/" + accountNumber;
	}
}
