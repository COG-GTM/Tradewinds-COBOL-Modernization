/*
 *
 *    Copyright IBM Corp. 2023
 *
 */
package com.ibm.cics.cip.bank.springboot.transferfunds.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * COMM-FAIL-CODE values set by XFRFUN.
 */
public enum FailCode
{
	/** '1': FROM account not found (SQLCODE +100 on the FROM SELECT). */
	FROM_ACCOUNT_NOT_FOUND("1"),

	/** '2': TO account not found (SQLCODE +100 on the TO SELECT). */
	TO_ACCOUNT_NOT_FOUND("2"),

	/** '3': datastore error on SELECT or UPDATE. */
	DATASTORE_ERROR("3"),

	/** '4': transfer amount was zero or negative (COMM-AMT <= 0). */
	NON_POSITIVE_AMOUNT("4"),

	/**
	 * Same-account transfer. XFRFUN abends with code 'SAME'; the service
	 * rejects the request instead of abending.
	 */
	SAME_ACCOUNT("SAME");

	private final String code;

	FailCode(String code)
	{
		this.code = code;
	}

	@JsonValue
	public String getCode()
	{
		return code;
	}
}
