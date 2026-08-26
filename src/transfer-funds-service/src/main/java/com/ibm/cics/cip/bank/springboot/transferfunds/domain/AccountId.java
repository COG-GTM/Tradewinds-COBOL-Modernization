/*
 *
 *    Copyright IBM Corp. 2023
 *
 */
package com.ibm.cics.cip.bank.springboot.transferfunds.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for the ACCOUNT table: sort code + account number,
 * matching the COBOL DESIRED-ACC-KEY (DESIRED-SORT-CODE PIC 9(6) plus
 * DESIRED-ACC-NO PIC 9(8)).
 */
public class AccountId implements Serializable
{
	private static final long serialVersionUID = 1L;

	private String sortCode;

	private String accountNumber;

	public AccountId()
	{
	}

	public AccountId(String sortCode, String accountNumber)
	{
		this.sortCode = sortCode;
		this.accountNumber = accountNumber;
	}

	public String getSortCode()
	{
		return sortCode;
	}

	public String getAccountNumber()
	{
		return accountNumber;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof AccountId))
		{
			return false;
		}
		AccountId other = (AccountId) o;
		return Objects.equals(sortCode, other.sortCode)
				&& Objects.equals(accountNumber, other.accountNumber);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(sortCode, accountNumber);
	}
}
