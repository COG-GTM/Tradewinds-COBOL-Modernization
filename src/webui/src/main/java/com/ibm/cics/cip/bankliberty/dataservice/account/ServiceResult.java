/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.account;

import java.util.Map;

/**
 * HTTP status plus JSON body, kept free of JAX-RS so it can be unit tested
 * without a JAX-RS runtime.
 */
public final class ServiceResult
{

	private final int status;

	private final Map<String, Object> body;


	public ServiceResult(int status, Map<String, Object> body)
	{
		this.status = status;
		this.body = body;
	}


	public int getStatus()
	{
		return status;
	}


	public Map<String, Object> getBody()
	{
		return body;
	}


	public String bodyAsJson()
	{
		return JsonCodec.serialize(body);
	}
}
