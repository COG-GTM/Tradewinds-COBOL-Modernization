package com.ibm.cics.cip.bank.springboot.customerservices.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** cbsa-core-service error body. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoreError(String errorMessage, String failCode, String abendCode)
{

	static final CoreError NONE = new CoreError(null, null, null);


	String failCodeOrEmpty()
	{
		return failCode == null ? "" : failCode.trim();
	}
}
