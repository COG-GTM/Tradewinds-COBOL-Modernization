/*                                                                        */
/* Copyright IBM Corp. 2023, 2025                                         */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.paymentinterface;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Connection details for cbsa-core-service. Previously static fields fed by
 * JCommander flags and the CBSA_ZOSCONN_HOST/CBSA_ZOSCONN_PORT system
 * properties pointing at z/OS Connect; now bound from
 * {@code cbsa.core.base-url} (default {@code http://localhost:8080}) and
 * {@code cbsa.core.context-path} (default {@code /webui-1.0}).
 */
@Component
public class ConnectionInfo
{

	private final String baseUrl;

	private final String contextPath;


	public ConnectionInfo(
			@Value("${cbsa.core.base-url:http://localhost:8080}") String baseUrl,
			@Value("${cbsa.core.context-path:/webui-1.0}") String contextPath)
	{
		this.baseUrl = stripTrailingSlash(baseUrl);
		this.contextPath = normaliseContextPath(contextPath);
	}


	/** Base URL including the cbsa-core-service context path. */
	public String getAddressAndPort()
	{
		return baseUrl + contextPath;
	}


	public String getBaseUrl()
	{
		return baseUrl;
	}


	public String getContextPath()
	{
		return contextPath;
	}


	private static String stripTrailingSlash(String value)
	{
		String trimmed = value.trim();
		while (trimmed.endsWith("/"))
		{
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed;
	}


	private static String normaliseContextPath(String value)
	{
		String trimmed = stripTrailingSlash(value == null ? "" : value);
		if (trimmed.isEmpty())
		{
			return "";
		}
		return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
	}
}
