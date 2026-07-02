/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.jsonclasses.transferfunds;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Outbound response mirroring the XFRFUN.cpy COMMAREA output fields:
 *
 *   COMM-FAVBAL    PIC S9(10)V99  -> fromAvailableBalance
 *   COMM-FACTBAL   PIC S9(10)V99  -> fromActualBalance
 *   COMM-TAVBAL    PIC S9(10)V99  -> toAvailableBalance
 *   COMM-TACTBAL   PIC S9(10)V99  -> toActualBalance
 *   COMM-SUCCESS   PIC X          -> success
 *   COMM-FAIL-CODE PIC X          -> failCode
 */
public class TransferFundsResponse
{


	@JsonProperty("fromAccountNumber")
	private String fromAccountNumber;

	@JsonProperty("fromSortCode")
	private String fromSortCode;

	@JsonProperty("toAccountNumber")
	private String toAccountNumber;

	@JsonProperty("toSortCode")
	private String toSortCode;

	@JsonProperty("amount")
	private BigDecimal amount;

	@JsonProperty("fromAvailableBalance")
	private BigDecimal fromAvailableBalance;

	@JsonProperty("fromActualBalance")
	private BigDecimal fromActualBalance;

	@JsonProperty("toAvailableBalance")
	private BigDecimal toAvailableBalance;

	@JsonProperty("toActualBalance")
	private BigDecimal toActualBalance;

	@JsonProperty("success")
	private String success;

	@JsonProperty("failCode")
	private String failCode;


	public TransferFundsResponse()
	{
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


	public BigDecimal getFromAvailableBalance()
	{
		return fromAvailableBalance;
	}


	public void setFromAvailableBalance(BigDecimal fromAvailableBalance)
	{
		this.fromAvailableBalance = fromAvailableBalance;
	}


	public BigDecimal getFromActualBalance()
	{
		return fromActualBalance;
	}


	public void setFromActualBalance(BigDecimal fromActualBalance)
	{
		this.fromActualBalance = fromActualBalance;
	}


	public BigDecimal getToAvailableBalance()
	{
		return toAvailableBalance;
	}


	public void setToAvailableBalance(BigDecimal toAvailableBalance)
	{
		this.toAvailableBalance = toAvailableBalance;
	}


	public BigDecimal getToActualBalance()
	{
		return toActualBalance;
	}


	public void setToActualBalance(BigDecimal toActualBalance)
	{
		this.toActualBalance = toActualBalance;
	}


	public String getSuccess()
	{
		return success;
	}


	public void setSuccess(String success)
	{
		this.success = success;
	}


	public String getFailCode()
	{
		return failCode;
	}


	public void setFailCode(String failCode)
	{
		this.failCode = failCode;
	}


	@Override
	public String toString()
	{
		return "TransferFundsResponse [fromAccountNumber=" + fromAccountNumber
				+ ", toAccountNumber=" + toAccountNumber + ", amount=" + amount
				+ ", success=" + success + ", failCode=" + failCode
				+ ", fromAvailableBalance=" + fromAvailableBalance
				+ ", fromActualBalance=" + fromActualBalance
				+ ", toAvailableBalance=" + toAvailableBalance
				+ ", toActualBalance=" + toActualBalance + "]";
	}
}
