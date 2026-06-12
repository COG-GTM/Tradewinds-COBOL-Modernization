/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request payload for a funds transfer.
 *
 * <p>
 * Models the input portion of the XFRFUN commarea ({@code XFRFUN.cpy}):
 * {@code COMM-FACCNO}, {@code COMM-TACCNO} and {@code COMM-AMT}.
 * </p>
 *
 * <p>
 * The original program forces both the FROM and TO sort codes to the bank's own
 * {@code SORTCODE} constant (987654) at {@code A010}
 * ({@code MOVE SORTCODE TO COMM-FSCODE COMM-TSCODE}), so the sort code is not
 * accepted from the caller here; it is supplied by configuration in the service
 * layer.
 * </p>
 *
 * <p>
 * Note that the amount sign is deliberately <em>not</em> validated with
 * {@code @Positive}. The COBOL program accepts the value and returns fail code
 * {@code '4'} for a zero/negative amount, so that rule is enforced in the
 * service to preserve the original contract.
 * </p>
 */
public class TransferRequest
{

	@NotNull
	@Pattern(regexp = "\\d{1,8}", message = "fromAccountNumber must be up to 8 digits")
	private String fromAccountNumber;

	@NotNull
	@Pattern(regexp = "\\d{1,8}", message = "toAccountNumber must be up to 8 digits")
	private String toAccountNumber;

	@NotNull
	private BigDecimal amount;

	public TransferRequest()
	{
	}

	public TransferRequest(String fromAccountNumber, String toAccountNumber,
			BigDecimal amount)
	{
		this.fromAccountNumber = fromAccountNumber;
		this.toAccountNumber = toAccountNumber;
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

	@Override
	public String toString()
	{
		return "TransferRequest [fromAccountNumber=" + fromAccountNumber
				+ ", toAccountNumber=" + toAccountNumber + ", amount=" + amount
				+ "]";
	}
}
