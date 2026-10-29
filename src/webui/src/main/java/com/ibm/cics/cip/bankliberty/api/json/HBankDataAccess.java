/*
 *
 *    Copyright IBM Corp. 2023
 *
 */
package com.ibm.cics.cip.bankliberty.api.json;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import javax.sql.DataSource;

import com.ibm.cics.server.InvalidRequestException;
import com.ibm.cics.server.RolledBackException;
import com.ibm.cics.server.Task;

/**
 * This class is used to hold a HashTable which in turn holds PostgreSQL
 * connections, one per CICS task (or per thread when running standalone)
 *
 */

public class HBankDataAccess
{


	protected Connection conn = null;

	static int connectionCount = 0;

	private static final String DBCONN = "DBCONN";

	static Map<String, Connection> cornedBeef = null;

	private static Logger logger = Logger
			.getLogger("com.ibm.cics.cip.bankliberty.api.json");


	public HBankDataAccess()
	{
		sortOutLogging();
		// If the hashmap 'cornedBeef' does not exist then create a new
		// hashtable
		if (cornedBeef == null)
		{
			logger.log(Level.FINE,
					() -> "HBankDataAccess creating new hashtable");
			HBankDataAccess.createHashMap();
		}
	}


	private static void createHashMap()
	{
		cornedBeef = new ConcurrentHashMap<>();

	}


	protected void openConnection()
	{
		// Open a connection to the PostgreSQL database
		logger.entering(this.getClass().getName(), "openConnection()");

		Integer taskNumberInteger = BankRuntime.taskNumber();
		String dbConnString = DBCONN.concat(taskNumberInteger.toString());
		logger.log(Level.FINE,
				() -> "Attempting to get DBCONN for task number "
						+ taskNumberInteger.toString());
		this.conn = cornedBeef.get(dbConnString);
		if (this.conn == null)
		{
			HBankDataAccess.incrementConnCount();
			logger.log(Level.FINE,
					() -> "Attempting to create DBCONN for task number "
							+ taskNumberInteger.toString());
			// Attempt to open a connection
			openConnectionInternal();
			logger.log(Level.FINE,
					() -> "Creation succcessful for DBCONN for task number "
							+ taskNumberInteger.toString());
		}
		else
		{
			logger.log(Level.FINE, () -> "Reusing DBCONN for task number "
					+ taskNumberInteger.toString());

			try
			{
				// If the connection is closed, try to reopen the connection
				if (this.conn.isClosed())
				{
					logger.warning(
							"PostgreSQL connection was closed, attempting to reopen");
					openConnectionInternal();
				}
			}
			catch (SQLException e)
			{
				logger.severe(e.getLocalizedMessage());
			}
		}

		logger.exiting(this.getClass().getName(), "openConnection()");
	}


	public void terminate()
	{
		// Close the connection
		closeConnection();
	}


	public void closeConnection()
	{
		// Close the connection to the PostgreSQL database
		logger.entering(this.getClass().getName(), "closeConnection()");

		HBankDataAccess.decrementConnCount();

		Integer taskNumberInterger = BankRuntime.taskNumber();
		String dbConnString = DBCONN.concat(taskNumberInterger.toString());
		this.conn = cornedBeef.get(dbConnString);
		// If there is an open connection
		if (this.conn != null)
		{
			logger.log(Level.FINE, () -> "We have a PostgreSQL connection to close");
			try
			{
				logger.log(Level.FINE, () -> "Syncpointing");
				if (!this.conn.getAutoCommit())
				{
					this.conn.commit();
				}
				if (!BankRuntime.isStandalone())
				{
					Task.getTask().commit();
				}
				// Close the connection
				this.conn.close();
				cornedBeef.remove(dbConnString);
			}
			catch (SQLException e)
			{
				logger.severe(
						"SQLException closing PostgreSQL connection "
								+ e.getErrorCode() + "," + e.getSQLState() + ","
								+ e.getMessage());
			}
			catch (InvalidRequestException | RolledBackException e)
			{
				logger.severe(e.getLocalizedMessage());
			}
		}
		logger.exiting(this.getClass().getName(), "closeConnection()");
	}


	void openConnectionInternal()
	{
		logger.entering(this.getClass().getName(), "openConnectionInternal");
		try
		{
			// If there is no current connection
			if (this.conn == null)
			{
				logger.log(Level.FINE,
						() -> "About to attempt to get PostgreSQL connection");
				// Try and get a connection
				this.conn = newConnection();
				// PostgreSQL has no READ UNCOMMITTED (it is treated as READ
				// COMMITTED). Statements run in an explicit transaction that
				// closeConnection() commits and BankRuntime.rollback() undoes.
				this.conn.setTransactionIsolation(
						Connection.TRANSACTION_READ_COMMITTED);
				this.conn.setAutoCommit(false);
				Integer taskNumberInterger = BankRuntime.taskNumber();
				String dbConnString = DBCONN
						.concat(taskNumberInterger.toString());
				cornedBeef.put(dbConnString, this.conn);
				HBankDataAccess.incrementConnCount();

			}
			else
			{
				// If the connection is closed, open a new connection
				if (this.conn.isClosed())
				{
					logger.log(Level.FINE,
							() -> "PostgreSQL connection was closed, getting a new one");
					this.conn = newConnection();
					this.conn.setAutoCommit(false);
					cornedBeef.put(DBCONN.concat(Integer
							.toString(BankRuntime.taskNumber())), this.conn);
				}
			}
		}
		catch (NamingException | SQLException e)
		{
			logger.severe(e.getMessage());
			if (BankRuntime.isStandalone())
			{
				throw new IllegalStateException(e);
			}
			Task.getTask().abend("HDB2");
		}
	}


	private Connection newConnection() throws NamingException, SQLException
	{
		if (BankRuntime.isStandalone())
		{
			return BankRuntime.standaloneConnection();
		}
		String jndiString = "jdbc/defaultCICSDataSource";
		Context ctx = new InitialContext();
		DataSource ds = (DataSource) ctx.lookup(jndiString);
		logger.log(Level.FINE, () -> "jndi string is " + jndiString);
		return ds.getConnection();
	}


	static void rollbackCurrentConnection()
	{
		if (cornedBeef == null)
		{
			return;
		}
		Connection current = cornedBeef.get(
				DBCONN.concat(Integer.toString(BankRuntime.taskNumber())));
		if (current == null)
		{
			return;
		}
		try
		{
			if (!current.isClosed() && !current.getAutoCommit())
			{
				current.rollback();
			}
		}
		catch (SQLException e)
		{
			logger.severe("Rollback failed " + e.getMessage());
		}
	}


	protected void sortOutLogging()
	{
		try
		{
			LogManager.getLogManager().readConfiguration();
		}
		catch (SecurityException | IOException e)
		{
			logger.severe(e.toString());
		}
	}


	private static void incrementConnCount()
	{
		HBankDataAccess.connectionCount++;
	}


	private static void decrementConnCount()
	{
		HBankDataAccess.connectionCount--;
	}
}
