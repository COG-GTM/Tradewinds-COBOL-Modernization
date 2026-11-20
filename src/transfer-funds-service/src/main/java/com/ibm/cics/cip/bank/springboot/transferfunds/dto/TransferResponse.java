/*
 *
 *    Copyright IBM Corp. 2023
 *
 */
package com.ibm.cics.cip.bank.springboot.transferfunds.dto;

import java.math.BigDecimal;

/**
 * Output half of the XFRFUN COMMAREA: COMM-FAVBAL, COMM-FACTBAL,
 * COMM-TAVBAL, COMM-TACTBAL, COMM-FAIL-CODE and COMM-SUCCESS.
 */
public class TransferResponse
{
	private boolean success;

	private FailCode failCode;

	private BigDecimal fromAvailableBalance;

	private BigDecimal fromActualBalance;

	private BigDecimal toAvailableBalance;

	private BigDecimal toActualBalance;

	public static TransferResponse failure(FailCode failCode)
	{
		TransferResponse response = new TransferResponse();
		response.success = false;
		response.failCode = failCode;
		return response;
	}

	public static TransferResponse ok(BigDecimal fromAvailableBalance,
			BigDecimal fromActualBalance, BigDecimal toAvailableBalance,
			BigDecimal toActualBalance)
	{
		TransferResponse response = new TransferResponse();
		response.success = true;
		response.fromAvailableBalance = fromAvailableBalance;
		response.fromActualBalance = fromActualBalance;
		response.toAvailableBalance = toAvailableBalance;
		response.toActualBalance = toActualBalance;
		return response;
	}

	public boolean isSuccess()
	{
		return success;
	}

	public FailCode getFailCode()
	{
		return failCode;
	}

	public BigDecimal getFromAvailableBalance()
	{
		return fromAvailableBalance;
	}

	public BigDecimal getFromActualBalance()
	{
		return fromActualBalance;
	}

	public BigDecimal getToAvailableBalance()
	{
		return toAvailableBalance;
	}

	public BigDecimal getToActualBalance()
	{
		return toActualBalance;
	}
}
