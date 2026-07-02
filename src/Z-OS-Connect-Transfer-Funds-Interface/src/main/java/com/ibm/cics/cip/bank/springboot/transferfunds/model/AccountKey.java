/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for ACCOUNT table.
 *
 * COBOL: WHERE (ACCOUNT_SORTCODE = :HV-ACCOUNT-SORTCODE
 *          AND ACCOUNT_NUMBER = :HV-ACCOUNT-ACC-NO)
 */
public class AccountKey implements Serializable
{


	private static final long serialVersionUID = 1L;

	private String sortcode;

	private String accountNumber;


	public AccountKey()
	{
	}


	public AccountKey(String sortcode, String accountNumber)
	{
		this.sortcode = sortcode;
		this.accountNumber = accountNumber;
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


	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (o == null || getClass() != o.getClass())
		{
			return false;
		}
		AccountKey that = (AccountKey) o;
		return Objects.equals(sortcode, that.sortcode)
				&& Objects.equals(accountNumber, that.accountNumber);
	}


	@Override
	public int hashCode()
	{
		return Objects.hash(sortcode, accountNumber);
	}
}
