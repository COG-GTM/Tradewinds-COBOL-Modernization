/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.account;

/**
 * A COBOL-equivalent failure: the HTTP status to return, the COMMAREA fail
 * code the COBOL caller should set, and (for paths where the legacy program
 * issued EXEC CICS ABEND) the abend code it should re-issue.
 */
public class DataServiceException extends Exception
{

	private static final long serialVersionUID = 1L;

	private final int httpStatus;

	private final String failCode;

	private final String abendCode;


	public DataServiceException(int httpStatus, String failCode,
			String abendCode, String message, Throwable cause)
	{
		super(message, cause);
		this.httpStatus = httpStatus;
		this.failCode = failCode == null ? " " : failCode;
		this.abendCode = abendCode;
	}


	public DataServiceException(int httpStatus, String failCode,
			String message)
	{
		this(httpStatus, failCode, null, message, null);
	}


	public static DataServiceException validation(String failCode,
			String message)
	{
		return new DataServiceException(400, failCode, message);
	}


	public static DataServiceException notFound(String failCode,
			String message)
	{
		return new DataServiceException(404, failCode, message);
	}


	public int getHttpStatus()
	{
		return httpStatus;
	}


	public String getFailCode()
	{
		return failCode;
	}


	public String getAbendCode()
	{
		return abendCode;
	}
}
