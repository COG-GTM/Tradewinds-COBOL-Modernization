/*
 *
 *    Copyright IBM Corp. 2023, 2025
 *
 */
package com.ibm.cics.cip.bankliberty.api.json;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

import com.ibm.cics.server.InvalidRequestException;
import com.ibm.cics.server.Task;

/**
 * Unit-of-work helper for the PostgreSQL data layer.
 *
 * Inside CICS the JDBC connection comes from the Liberty datasource
 * jdbc/defaultCICSDataSource. A PostgreSQL type-4 connection is not enlisted
 * in the CICS unit of work, so JDBC commit/rollback is driven explicitly
 * alongside the CICS syncpoint.
 *
 * When CBSA_JDBC_URL is set (system property or environment variable) the
 * web tier runs standalone, outside CICS: connections come from
 * DriverManager and the thread id stands in for the CICS task number.
 */
public final class BankRuntime
{

	public static final String JDBC_URL = "CBSA_JDBC_URL";

	public static final String JDBC_USER = "CBSA_JDBC_USER";

	public static final String JDBC_PASSWORD = "CBSA_JDBC_PASSWORD";

	private static final Logger logger = Logger
			.getLogger("com.ibm.cics.cip.bankliberty.api.json");


	private BankRuntime()
	{
	}


	static String setting(String name)
	{
		String value = System.getProperty(name);
		return value != null ? value : System.getenv(name);
	}


	public static boolean isStandalone()
	{
		return setting(JDBC_URL) != null;
	}


	public static int taskNumber()
	{
		if (isStandalone())
		{
			return (int) Thread.currentThread().getId();
		}
		return Task.getTask().getTaskNumber();
	}


	static Connection standaloneConnection() throws SQLException
	{
		return DriverManager.getConnection(setting(JDBC_URL),
				setting(JDBC_USER), setting(JDBC_PASSWORD));
	}


	/**
	 * Roll back the current JDBC unit of work and, inside CICS, the CICS unit
	 * of work (VSAM and other recoverable resources).
	 */
	public static void rollback() throws InvalidRequestException
	{
		HBankDataAccess.rollbackCurrentConnection();
		if (!isStandalone())
		{
			Task.getTask().rollback();
		}
		logger.fine("Unit of work rolled back");
	}
}
