/*
 *
 *    Copyright IBM Corp. 2023
 *
 */
package com.ibm.cics.cip.bank.springboot.transferfunds.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Input half of the XFRFUN COMMAREA (copybook XFRFUN.cpy):
 * COMM-FACCNO, COMM-FSCODE, COMM-TACCNO, COMM-TSCODE and COMM-AMT.
 * Whitelist validation enforces the original PIC 9(n) numeric formats.
 */
public class TransferRequest
{
	@NotNull
	@Pattern(regexp = "\\d{1,8}", message = "fromAccountNumber must be 1-8 digits")
	private String fromAccountNumber;

	@NotNull
	@Pattern(regexp = "\\d{1,6}", message = "fromSortCode must be 1-6 digits")
	private String fromSortCode;

	@NotNull
	@Pattern(regexp = "\\d{1,8}", message = "toAccountNumber must be 1-8 digits")
	private String toAccountNumber;

	@NotNull
	@Pattern(regexp = "\\d{1,6}", message = "toSortCode must be 1-6 digits")
	private String toSortCode;

	@NotNull
	private BigDecimal amount;

	public String getFromAccountNumber()
	{
		return fromAccountNumber;
	}

	public void setFromAccountNumber(String fromAccountNumber)
	{
		this.fromAccountNumber = fromAccountNumber;
	}

	public String getFromSortCode()
	{
		return fromSortCode;
	}

	public void setFromSortCode(String fromSortCode)
	{
		this.fromSortCode = fromSortCode;
	}

	public String getToAccountNumber()
	{
		return toAccountNumber;
	}

	public void setToAccountNumber(String toAccountNumber)
	{
		this.toAccountNumber = toAccountNumber;
	}

	public String getToSortCode()
	{
		return toSortCode;
	}

	public void setToSortCode(String toSortCode)
	{
		this.toSortCode = toSortCode;
	}

	public BigDecimal getAmount()
	{
		return amount;
	}

	public void setAmount(BigDecimal amount)
	{
		this.amount = amount;
	}
}
