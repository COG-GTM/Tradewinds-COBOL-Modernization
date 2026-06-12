/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.service;

/**
 * Enumeration of the XFRFUN failure outcomes.
 *
 * <p>
 * Each value maps to a single-character {@code COMM-FAIL-CODE} returned by the
 * COBOL program in its commarea (see {@code XFRFUN.cpy} field
 * {@code COMM-FAIL-CODE}). The original program sets:
 * </p>
 *
 * <ul>
 * <li>{@code '1'} when the FROM account cannot be read
 * ({@code UPDATE-ACCOUNT-DB2-FROM}, SQLCODE +100)</li>
 * <li>{@code '2'} when the TO account cannot be read
 * ({@code UPDATE-ACCOUNT-DB2-TO}, SQLCODE +100) which forces a rollback</li>
 * <li>{@code '3'} for any other Db2 error on a SELECT/UPDATE</li>
 * <li>{@code '4'} when the requested amount is zero or negative
 * ({@code A010})</li>
 * </ul>
 */
public enum TransferFailureReason
{

	NONE(' ', "No failure"),

	FROM_ACCOUNT_NOT_FOUND('1', "Transfer rejected: the FROM account does not exist."),

	TO_ACCOUNT_NOT_FOUND('2', "Transfer rejected: the TO account does not exist."),

	DATASTORE_ERROR('3', "Transfer rejected: a datastore error occurred."),

	NON_POSITIVE_AMOUNT('4', "Transfer rejected: the amount must be greater than zero.");

	private final char code;

	private final String message;

	TransferFailureReason(char code, String message)
	{
		this.code = code;
		this.message = message;
	}

	public String getCode()
	{
		return String.valueOf(code);
	}

	public String getMessage()
	{
		return message;
	}

	public static TransferFailureReason fromCode(String code)
	{
		if (code == null || code.isBlank())
		{
			return NONE;
		}
		for (TransferFailureReason reason : values())
		{
			if (reason.getCode().equals(code))
			{
				return reason;
			}
		}
		throw new IllegalArgumentException("Unknown fail code: " + code);
	}
}
