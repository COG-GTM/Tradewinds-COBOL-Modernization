/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.dto;

import java.math.BigDecimal;

import com.ibm.cics.cip.bank.springboot.transferfunds.service.TransferFailureReason;

/**
 * Response payload for a funds transfer.
 *
 * <p>
 * Models the output portion of the XFRFUN commarea ({@code XFRFUN.cpy}):
 * {@code COMM-SUCCESS}, {@code COMM-FAIL-CODE}, and the four returned balances
 * {@code COMM-FAVBAL}, {@code COMM-FACTBAL}, {@code COMM-TAVBAL} and
 * {@code COMM-TACTBAL}. The raw {@code commSuccess}/{@code commFailCode} strings
 * are exposed alongside friendlier {@code success}/{@code failureReason} fields.
 * </p>
 */
public class TransferResponse
{

	private boolean success;

	private String commSuccess;

	private String commFailCode;

	private String failureReason;

	private String message;

	private String fromSortCode;

	private String fromAccountNumber;

	private String toSortCode;

	private String toAccountNumber;

	private BigDecimal amount;

	private BigDecimal fromAvailableBalance;

	private BigDecimal fromActualBalance;

	private BigDecimal toAvailableBalance;

	private BigDecimal toActualBalance;

	public TransferResponse()
	{
	}

	/** Build a failed response carrying the supplied XFRFUN fail code. */
	public static TransferResponse failure(TransferFailureReason reason,
			String fromSortCode, String fromAccountNumber, String toSortCode,
			String toAccountNumber, BigDecimal amount)
	{
		TransferResponse response = new TransferResponse();
		response.success = false;
		response.commSuccess = "N";
		response.commFailCode = reason.getCode();
		response.failureReason = reason.name();
		response.message = reason.getMessage();
		response.fromSortCode = fromSortCode;
		response.fromAccountNumber = fromAccountNumber;
		response.toSortCode = toSortCode;
		response.toAccountNumber = toAccountNumber;
		response.amount = amount;
		return response;
	}

	public boolean isSuccess()
	{
		return success;
	}

	public void setSuccess(boolean success)
	{
		this.success = success;
	}

	public String getCommSuccess()
	{
		return commSuccess;
	}

	public void setCommSuccess(String commSuccess)
	{
		this.commSuccess = commSuccess;
	}

	public String getCommFailCode()
	{
		return commFailCode;
	}

	public void setCommFailCode(String commFailCode)
	{
		this.commFailCode = commFailCode;
	}

	public String getFailureReason()
	{
		return failureReason;
	}

	public void setFailureReason(String failureReason)
	{
		this.failureReason = failureReason;
	}

	public String getMessage()
	{
		return message;
	}

	public void setMessage(String message)
	{
		this.message = message;
	}

	public String getFromSortCode()
	{
		return fromSortCode;
	}

	public void setFromSortCode(String fromSortCode)
	{
		this.fromSortCode = fromSortCode;
	}

	public String getFromAccountNumber()
	{
		return fromAccountNumber;
	}

	public void setFromAccountNumber(String fromAccountNumber)
	{
		this.fromAccountNumber = fromAccountNumber;
	}

	public String getToSortCode()
	{
		return toSortCode;
	}

	public void setToSortCode(String toSortCode)
	{
		this.toSortCode = toSortCode;
	}

	public String getToAccountNumber()
	{
		return toAccountNumber;
	}

	public void setToAccountNumber(String toAccountNumber)
	{
		this.toAccountNumber = toAccountNumber;
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

	@Override
	public String toString()
	{
		return "TransferResponse [success=" + success + ", commFailCode="
				+ commFailCode + ", fromAvailableBalance=" + fromAvailableBalance
				+ ", toAvailableBalance=" + toAvailableBalance + "]";
	}
}
