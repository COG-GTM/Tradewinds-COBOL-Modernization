/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.movement;

import java.math.BigDecimal;

/**
 * Outcome of a data-service unit of work: the COBOL COMMAREA success flag and
 * fail code, an optional abend code (the COBOL program would have issued EXEC
 * CICS ABEND ABCODE(...)), the HTTP status, balances and the PROCTRAN row
 * written.
 */
public class MovementResult
{

	private final int httpStatus;

	private final boolean success;

	private final String failCode;

	private final String abendCode;

	private final String message;

	private BigDecimal availableBalance;

	private BigDecimal actualBalance;

	private BigDecimal toAvailableBalance;

	private BigDecimal toActualBalance;

	private ProctranRecord proctran;

	private MovementResult(int httpStatus, boolean success, String failCode,
			String abendCode, String message)
	{
		this.httpStatus = httpStatus;
		this.success = success;
		this.failCode = failCode;
		this.abendCode = abendCode;
		this.message = message;
	}

	public static MovementResult ok(ProctranRecord proctran)
	{
		MovementResult r = new MovementResult(200, true, "0", null, null);
		r.proctran = proctran;
		return r;
	}

	public static MovementResult fail(int httpStatus, String failCode,
			String message)
	{
		return new MovementResult(httpStatus, false, failCode, null, message);
	}

	public static MovementResult abend(int httpStatus, String failCode,
			String abendCode, String message)
	{
		return new MovementResult(httpStatus, false, failCode, abendCode,
				message);
	}

	MovementResult withBalances(BigDecimal available, BigDecimal actual)
	{
		this.availableBalance = available;
		this.actualBalance = actual;
		return this;
	}

	MovementResult withToBalances(BigDecimal available, BigDecimal actual)
	{
		this.toAvailableBalance = available;
		this.toActualBalance = actual;
		return this;
	}

	public int getHttpStatus()
	{
		return httpStatus;
	}

	public boolean isSuccess()
	{
		return success;
	}

	public String getFailCode()
	{
		return failCode;
	}

	public String getAbendCode()
	{
		return abendCode;
	}

	public String getMessage()
	{
		return message;
	}

	/** For transfers this is the FROM account (COMM-FAVBAL). */
	public BigDecimal getAvailableBalance()
	{
		return availableBalance;
	}

	/** For transfers this is the FROM account (COMM-FACTBAL). */
	public BigDecimal getActualBalance()
	{
		return actualBalance;
	}

	public BigDecimal getToAvailableBalance()
	{
		return toAvailableBalance;
	}

	public BigDecimal getToActualBalance()
	{
		return toActualBalance;
	}

	public ProctranRecord getProctran()
	{
		return proctran;
	}
}
