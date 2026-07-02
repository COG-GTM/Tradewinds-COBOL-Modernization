/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.jsonclasses.transferfunds;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Inbound request form for a funds transfer.
 *
 * Maps to the input fields of XFRFUN.cpy COMMAREA:
 *   COMM-FACCNO  PIC 9(8)       -> fromAccountNumber
 *   COMM-FSCODE  PIC 9(6)       -> fromSortCode
 *   COMM-TACCNO  PIC 9(8)       -> toAccountNumber
 *   COMM-TSCODE  PIC 9(6)       -> toSortCode
 *   COMM-AMT     PIC S9(10)V99  -> amount (BigDecimal)
 */
public class TransferFundsForm
{


	@NotNull
	@Size(min = 1, max = 8)
	private String fromAccountNumber;

	@Size(max = 6)
	private String fromSortCode;

	@NotNull
	@Size(min = 1, max = 8)
	private String toAccountNumber;

	@Size(max = 6)
	private String toSortCode;

	@NotNull
	@Positive
	private BigDecimal amount;


	public TransferFundsForm()
	{
	}


	public TransferFundsForm(String fromAccountNumber, String fromSortCode,
			String toAccountNumber, String toSortCode, BigDecimal amount)
	{
		this.fromAccountNumber = fromAccountNumber;
		this.fromSortCode = fromSortCode;
		this.toAccountNumber = toAccountNumber;
		this.toSortCode = toSortCode;
		this.amount = amount;
	}


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


	@Override
	public String toString()
	{
		return "TransferFundsForm [fromAccountNumber=" + fromAccountNumber
				+ ", fromSortCode=" + fromSortCode + ", toAccountNumber="
				+ toAccountNumber + ", toSortCode=" + toSortCode + ", amount="
				+ amount + "]";
	}
}
