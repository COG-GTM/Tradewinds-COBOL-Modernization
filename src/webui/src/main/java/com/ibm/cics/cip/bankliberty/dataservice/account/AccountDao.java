/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.account;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ACCOUNT / CONTROL / PROCTRAN data access replacing the embedded SQL of
 * INQACC, INQACCCU, CREACC, UPDACC, DELACC and BANKDATA. Plain JDBC on a
 * caller-supplied connection; every method that was a COBOL unit of work is
 * atomic (commit on success, rollback on any failure, mirroring EXEC CICS
 * SYNCPOINT ROLLBACK / abend backout).
 */
public class AccountDao
{

	/** INQACCCU: ACCOUNT-DETAILS OCCURS 1 TO 20. */
	public static final int MAX_ACCOUNTS_PER_CUSTOMER = 20;

	/** INQACC: INQACC-ACCNO = 99999999 means "highest account for sortcode". */
	public static final String LAST_ACCOUNT_SENTINEL = "99999999";

	/** CREACC WAD010: next statement = today + 30 days. */
	public static final int NEXT_STATEMENT_DAYS = 30;

	static final String CONTROL_ACCOUNT_LAST_SUFFIX = "-ACCOUNT-LAST";

	static final String CONTROL_ACCOUNT_COUNT_SUFFIX = "-ACCOUNT-COUNT";

	private static final String UNIQUE_VIOLATION = "23505";

	private static final int BATCH_SIZE = 1000;

	private static final String COLUMNS = "ACCOUNT_EYECATCHER, ACCOUNT_CUSTOMER_NUMBER, ACCOUNT_SORTCODE, "
			+ "ACCOUNT_NUMBER, ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE, ACCOUNT_OPENED, "
			+ "ACCOUNT_OVERDRAFT_LIMIT, ACCOUNT_LAST_STATEMENT, ACCOUNT_NEXT_STATEMENT, "
			+ "ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE";

	private static final String SQL_SELECT_ONE = "SELECT " + COLUMNS
			+ " FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?";

	private static final String SQL_SELECT_ONE_FOR_UPDATE = SQL_SELECT_ONE
			+ " FOR UPDATE";

	private static final String SQL_SELECT_LAST = "SELECT " + COLUMNS
			+ " FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ?"
			+ " ORDER BY ACCOUNT_NUMBER DESC LIMIT 1";

	private static final String SQL_SELECT_BY_CUSTOMER = "SELECT " + COLUMNS
			+ " FROM ACCOUNT WHERE ACCOUNT_CUSTOMER_NUMBER = ? AND ACCOUNT_SORTCODE = ?"
			+ " ORDER BY ACCOUNT_NUMBER LIMIT ?";

	private static final String SQL_INSERT_ACCOUNT = "INSERT INTO ACCOUNT (" + COLUMNS
			+ ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";

	private static final String SQL_UPDATE_ACCOUNT = "UPDATE ACCOUNT SET ACCOUNT_TYPE = ?, "
			+ "ACCOUNT_INTEREST_RATE = ?, ACCOUNT_OVERDRAFT_LIMIT = ? "
			+ "WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?";

	private static final String SQL_DELETE_ACCOUNT = "DELETE FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?";

	private static final String SQL_DELETE_ACCOUNTS_FOR_SORTCODE = "DELETE FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ?";

	private static final String SQL_SELECT_CONTROL_FOR_UPDATE = "SELECT CONTROL_VALUE_NUM FROM CONTROL WHERE CONTROL_NAME = ? FOR UPDATE";

	private static final String SQL_UPDATE_CONTROL = "UPDATE CONTROL SET CONTROL_VALUE_NUM = ? WHERE CONTROL_NAME = ?";

	private static final String SQL_DELETE_CONTROL = "DELETE FROM CONTROL WHERE CONTROL_NAME = ?";

	private static final String SQL_INSERT_CONTROL = "INSERT INTO CONTROL (CONTROL_NAME, CONTROL_VALUE_NUM, CONTROL_VALUE_STR) VALUES (?,?,?)";

	/**
	 * Transaction-scoped advisory lock serialising account-number allocation
	 * and bulk reset per sortcode (the CONTROL rows cannot be the lock target
	 * because reset deletes and recreates them).
	 */
	private static final String SQL_LOCK_SORTCODE = "SELECT pg_advisory_xact_lock(?, ?)";

	/** Advisory-lock namespace: 'ACCT'. */
	static final int ACCOUNT_LOCK_CLASS = 0x41434354;

	private static final String SQL_INSERT_PROCTRAN = "INSERT INTO PROCTRAN (PROCTRAN_EYECATCHER, PROCTRAN_SORTCODE, "
			+ "PROCTRAN_NUMBER, PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_REF, PROCTRAN_TYPE, "
			+ "PROCTRAN_DESC, PROCTRAN_AMOUNT) VALUES (?,?,?,?,?,?,?,?,?)";

	private final Connection conn;

	private final Clock clock;


	public AccountDao(Connection conn, Clock clock)
	{
		this.conn = conn;
		this.clock = clock;
	}


	/** Request data for {@link #createAccount}. Keys already fixed-width. */
	public static class NewAccount
	{
		public String sortcode;

		public String customerNumber;

		/** null = allocate from CONTROL &lt;sortcode&gt;-ACCOUNT-LAST. */
		public String accountNumber;

		public String accountType;

		public BigDecimal interestRate = BigDecimal.ZERO;

		public long overdraftLimit;

		public BigDecimal availableBalance = BigDecimal.ZERO;

		public BigDecimal actualBalance = BigDecimal.ZERO;
	}


	@FunctionalInterface
	private interface UnitOfWork<T>
	{
		T run() throws SQLException, DataServiceException;
	}


	// ---------------------------------------------------------------- reads

	/**
	 * INQACC READ-ACCOUNT-DB2 (or READ-ACCOUNT-LAST when the account number
	 * is 99999999).
	 */
	public Optional<AccountRecord> findAccount(String sortcode,
			String accountNumber) throws SQLException
	{
		if (LAST_ACCOUNT_SENTINEL.equals(accountNumber))
		{
			return findLastAccount(sortcode);
		}
		try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_ONE))
		{
			ps.setString(1, sortcode);
			ps.setString(2, accountNumber);
			return single(ps);
		}
	}


	/** INQACC GET-LAST-ACCOUNT-DB2: ORDER BY ACCOUNT_NUMBER DESC, first row. */
	public Optional<AccountRecord> findLastAccount(String sortcode)
			throws SQLException
	{
		try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_LAST))
		{
			ps.setString(1, sortcode);
			return single(ps);
		}
	}


	/** INQACCCU READ-ACCOUNT-DB2 / FETCH-DATA: at most 20 rows. */
	public List<AccountRecord> findAccountsForCustomer(String sortcode,
			String customerNumber) throws SQLException
	{
		List<AccountRecord> result = new ArrayList<>();
		try (PreparedStatement ps = conn
				.prepareStatement(SQL_SELECT_BY_CUSTOMER))
		{
			ps.setString(1, customerNumber);
			ps.setString(2, sortcode);
			ps.setInt(3, MAX_ACCOUNTS_PER_CUSTOMER);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					result.add(map(rs));
				}
			}
		}
		return result;
	}


	// --------------------------------------------------------------- writes

	/**
	 * CREACC FIND-NEXT-ACCOUNT + WRITE-ACCOUNT-DB2 + WRITE-PROCTRAN-DB2 as
	 * one atomic unit of work.
	 */
	public AccountRecord createAccount(NewAccount req, String taskRef)
			throws DataServiceException
	{
		return inTransaction(() -> {
			lockSortcode(req.sortcode);
			String lastName = controlName(req.sortcode,
					CONTROL_ACCOUNT_LAST_SUFFIX);
			String countName = controlName(req.sortcode,
					CONTROL_ACCOUNT_COUNT_SUFFIX);

			long last = readControlForUpdate(lastName);
			long allocated;
			if (req.accountNumber == null)
			{
				allocated = last + 1;
				updateControl(lastName, allocated);
			}
			else
			{
				allocated = Long.parseLong(req.accountNumber);
				if (allocated > last)
				{
					updateControl(lastName, allocated);
				}
			}
			if (allocated > 99999999L)
			{
				throw new DataServiceException(500, "7", null,
						"Account number range exhausted for sortcode "
								+ req.sortcode,
						null);
			}
			long count = readControlForUpdate(countName);
			updateControl(countName, count + 1);

			LocalDate today = LocalDate.now(clock);
			AccountRecord rec = new AccountRecord();
			rec.setCustomerNumber(req.customerNumber);
			rec.setSortcode(req.sortcode);
			rec.setAccountNumber(CobolFormat.zeroPad(allocated,
					CobolFormat.ACCOUNT_NUMBER_LENGTH));
			rec.setAccountType(CobolFormat.blankPad(req.accountType,
					CobolFormat.ACCOUNT_TYPE_LENGTH));
			rec.setInterestRate(req.interestRate);
			rec.setOverdraftLimit(req.overdraftLimit);
			rec.setAvailableBalance(req.availableBalance);
			rec.setActualBalance(req.actualBalance);
			rec.setOpened(today);
			rec.setLastStatement(today);
			rec.setNextStatement(today.plusDays(NEXT_STATEMENT_DAYS));

			try
			{
				insertAccount(rec);
			}
			catch (SQLException e)
			{
				int status = UNIQUE_VIOLATION.equals(e.getSQLState()) ? 409
						: 500;
				throw new DataServiceException(status, "7", null,
						"ACCOUNT insert failed: " + e.getMessage(), e);
			}

			insertProctranOrAbend(rec.getSortcode(), rec.getAccountNumber(),
					taskRef, AccountProctran.TYPE_BRANCH_CREATE_ACCOUNT,
					AccountProctran.createAccountDescription(
							rec.getCustomerNumber(), rec.getAccountType(),
							rec.getLastStatement(), rec.getNextStatement()),
					BigDecimal.ZERO);
			return rec;
		});
	}


	/**
	 * UPDACC UPDATE-ACCOUNT-DB2: read, validate type, update type / rate /
	 * overdraft. Returns the row as it is after the update.
	 */
	public AccountRecord updateAccount(String sortcode, String accountNumber,
			String accountType, BigDecimal interestRate, long overdraftLimit)
			throws DataServiceException
	{
		return inTransaction(() -> {
			AccountRecord rec;
			try
			{
				rec = selectForUpdate(sortcode, accountNumber).orElse(null);
			}
			catch (SQLException e)
			{
				throw new DataServiceException(500, "2", null,
						"ACCOUNT select failed: " + e.getMessage(), e);
			}
			if (rec == null)
			{
				throw DataServiceException.notFound("1", "Account "
						+ sortcode + "/" + accountNumber + " not found");
			}
			if (accountType == null || accountType.isEmpty()
					|| accountType.charAt(0) == ' ')
			{
				throw DataServiceException.validation("A",
						"accountType must not be blank or start with a space");
			}
			String paddedType = CobolFormat.blankPad(accountType,
					CobolFormat.ACCOUNT_TYPE_LENGTH);
			try (PreparedStatement ps = conn
					.prepareStatement(SQL_UPDATE_ACCOUNT))
			{
				ps.setString(1, paddedType);
				ps.setBigDecimal(2, interestRate);
				ps.setLong(3, overdraftLimit);
				ps.setString(4, sortcode);
				ps.setString(5, accountNumber);
				ps.executeUpdate();
			}
			catch (SQLException e)
			{
				throw new DataServiceException(500, "3", null,
						"ACCOUNT update failed: " + e.getMessage(), e);
			}
			rec.setAccountType(paddedType);
			rec.setInterestRate(interestRate);
			rec.setOverdraftLimit(overdraftLimit);
			return rec;
		});
	}


	/**
	 * DELACC READ-ACCOUNT-DB2 + DEL-ACCOUNT-DB2 + WRITE-PROCTRAN-DB2 as one
	 * atomic unit of work. Returns the deleted row.
	 */
	public AccountRecord deleteAccount(String sortcode, String accountNumber,
			String taskRef) throws DataServiceException
	{
		return inTransaction(() -> {
			AccountRecord rec;
			try
			{
				rec = selectForUpdate(sortcode, accountNumber).orElse(null);
			}
			catch (SQLException e)
			{
				throw new DataServiceException(500, " ", "HRAC",
						"ACCOUNT select failed: " + e.getMessage(), e);
			}
			if (rec == null)
			{
				throw DataServiceException.notFound("1", "Account "
						+ sortcode + "/" + accountNumber + " not found");
			}
			try (PreparedStatement ps = conn
					.prepareStatement(SQL_DELETE_ACCOUNT))
			{
				ps.setString(1, sortcode);
				ps.setString(2, accountNumber);
				ps.executeUpdate();
			}
			catch (SQLException e)
			{
				throw new DataServiceException(500, "3", null,
						"ACCOUNT delete failed: " + e.getMessage(), e);
			}
			insertProctranOrAbend(rec.getSortcode(), rec.getAccountNumber(),
					taskRef, AccountProctran.TYPE_BRANCH_DELETE_ACCOUNT,
					AccountProctran.deleteAccountDescription(
							rec.getCustomerNumber(), rec.getAccountType(),
							rec.getLastStatement(), rec.getNextStatement()),
					rec.getActualBalance());
			return rec;
		});
	}


	/**
	 * BANKDATA DELETE-DB2-ROWS + POPULATE-ACC + CONTROL inserts: replace all
	 * ACCOUNT rows and the account CONTROL rows for one sortcode, atomically,
	 * using JDBC batch inserts.
	 *
	 * @param lastAccountNumber value for &lt;sortcode&gt;-ACCOUNT-LAST; null =
	 *            highest account number in the batch (0 if empty)
	 * @param numberOfAccounts value for &lt;sortcode&gt;-ACCOUNT-COUNT; null =
	 *            batch size
	 * @return number of ACCOUNT rows inserted
	 */
	public int resetAccounts(String sortcode, List<AccountRecord> accounts,
			Long lastAccountNumber, Long numberOfAccounts)
			throws DataServiceException
	{
		return inTransaction(() -> {
			lockSortcode(sortcode);
			try (PreparedStatement ps = conn
					.prepareStatement(SQL_DELETE_ACCOUNTS_FOR_SORTCODE))
			{
				ps.setString(1, sortcode);
				ps.executeUpdate();
			}
			String lastName = controlName(sortcode,
					CONTROL_ACCOUNT_LAST_SUFFIX);
			String countName = controlName(sortcode,
					CONTROL_ACCOUNT_COUNT_SUFFIX);
			try (PreparedStatement ps = conn
					.prepareStatement(SQL_DELETE_CONTROL))
			{
				ps.setString(1, lastName);
				ps.executeUpdate();
				ps.setString(1, countName);
				ps.executeUpdate();
			}

			long highest = 0;
			try (PreparedStatement ps = conn
					.prepareStatement(SQL_INSERT_ACCOUNT))
			{
				int pending = 0;
				for (AccountRecord rec : accounts)
				{
					bindAccount(ps, rec);
					ps.addBatch();
					highest = Math.max(highest,
							Long.parseLong(rec.getAccountNumber()));
					if (++pending == BATCH_SIZE)
					{
						ps.executeBatch();
						pending = 0;
					}
				}
				if (pending > 0)
				{
					ps.executeBatch();
				}
			}
			catch (SQLException e)
			{
				SQLException root = e.getNextException() != null
						? e.getNextException() : e;
				boolean dup = UNIQUE_VIOLATION.equals(e.getSQLState())
						|| UNIQUE_VIOLATION.equals(root.getSQLState());
				throw new DataServiceException(dup ? 409 : 500, "I", null,
						"ACCOUNT batch insert failed: " + root.getMessage(),
						e);
			}

			try (PreparedStatement ps = conn
					.prepareStatement(SQL_INSERT_CONTROL))
			{
				ps.setString(1, lastName);
				ps.setLong(2, lastAccountNumber != null ? lastAccountNumber
						: highest);
				ps.setString(3, "");
				ps.executeUpdate();
				ps.setString(1, countName);
				ps.setLong(2, numberOfAccounts != null ? numberOfAccounts
						: accounts.size());
				ps.setString(3, "");
				ps.executeUpdate();
			}
			return accounts.size();
		});
	}


	// -------------------------------------------------------------- helpers

	static String controlName(String sortcode, String suffix)
	{
		return sortcode + suffix;
	}


	private void lockSortcode(String sortcode) throws SQLException
	{
		try (PreparedStatement ps = conn.prepareStatement(SQL_LOCK_SORTCODE))
		{
			ps.setInt(1, ACCOUNT_LOCK_CLASS);
			ps.setInt(2, Integer.parseInt(sortcode));
			ps.executeQuery().close();
		}
	}


	private <T> T inTransaction(UnitOfWork<T> work)
			throws DataServiceException
	{
		boolean previousAutoCommit = true;
		try
		{
			previousAutoCommit = conn.getAutoCommit();
			conn.setAutoCommit(false);
			T result = work.run();
			conn.commit();
			return result;
		}
		catch (DataServiceException e)
		{
			rollbackQuietly();
			throw e;
		}
		catch (SQLException | RuntimeException e)
		{
			rollbackQuietly();
			throw new DataServiceException(500, " ", null,
					"Database failure: " + e.getMessage(), e);
		}
		finally
		{
			try
			{
				conn.setAutoCommit(previousAutoCommit);
			}
			catch (SQLException e)
			{
				// connection unusable; nothing more to restore
			}
		}
	}


	private void rollbackQuietly()
	{
		try
		{
			conn.rollback();
		}
		catch (SQLException e)
		{
			// the original failure is what the caller needs to see
		}
	}


	/** CONTROL row missing or unreadable: CREACC abends HNCS. */
	private long readControlForUpdate(String name) throws DataServiceException
	{
		try (PreparedStatement ps = conn
				.prepareStatement(SQL_SELECT_CONTROL_FOR_UPDATE))
		{
			ps.setString(1, name);
			try (ResultSet rs = ps.executeQuery())
			{
				if (!rs.next())
				{
					throw new DataServiceException(500, " ", "HNCS",
							"CONTROL row " + name + " not found", null);
				}
				return rs.getLong(1);
			}
		}
		catch (SQLException e)
		{
			throw new DataServiceException(500, " ", "HNCS",
					"CONTROL select failed for " + name + ": "
							+ e.getMessage(),
					e);
		}
	}


	private void updateControl(String name, long value)
			throws DataServiceException
	{
		try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_CONTROL))
		{
			ps.setLong(1, value);
			ps.setString(2, name);
			ps.executeUpdate();
		}
		catch (SQLException e)
		{
			throw new DataServiceException(500, " ", "HNCS",
					"CONTROL update failed for " + name + ": "
							+ e.getMessage(),
					e);
		}
	}


	/** PROCTRAN insert failure: CREACC / DELACC abend HWPT (full backout). */
	private void insertProctranOrAbend(String sortcode, String accountNumber,
			String taskRef, String type, String description,
			BigDecimal amount) throws DataServiceException
	{
		LocalDateTime now = LocalDateTime.now(clock);
		try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_PROCTRAN))
		{
			ps.setString(1, AccountProctran.EYECATCHER);
			ps.setString(2, sortcode);
			ps.setString(3, accountNumber);
			ps.setObject(4, now.toLocalDate());
			ps.setString(5, CobolFormat.hhmmss(now.toLocalTime()));
			ps.setString(6, taskRef);
			ps.setString(7, type);
			ps.setString(8, description);
			ps.setBigDecimal(9, amount == null ? BigDecimal.ZERO : amount);
			ps.executeUpdate();
		}
		catch (SQLException e)
		{
			throw new DataServiceException(500, " ", "HWPT",
					"PROCTRAN insert failed: " + e.getMessage(), e);
		}
	}


	private Optional<AccountRecord> selectForUpdate(String sortcode,
			String accountNumber) throws SQLException
	{
		try (PreparedStatement ps = conn
				.prepareStatement(SQL_SELECT_ONE_FOR_UPDATE))
		{
			ps.setString(1, sortcode);
			ps.setString(2, accountNumber);
			return single(ps);
		}
	}


	private void insertAccount(AccountRecord rec) throws SQLException
	{
		try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_ACCOUNT))
		{
			bindAccount(ps, rec);
			ps.executeUpdate();
		}
	}


	private static void bindAccount(PreparedStatement ps, AccountRecord rec)
			throws SQLException
	{
		ps.setString(1, rec.getEyecatcher());
		ps.setString(2, rec.getCustomerNumber());
		ps.setString(3, rec.getSortcode());
		ps.setString(4, rec.getAccountNumber());
		ps.setString(5, CobolFormat.blankPad(rec.getAccountType(),
				CobolFormat.ACCOUNT_TYPE_LENGTH));
		ps.setBigDecimal(6, rec.getInterestRate());
		ps.setObject(7, rec.getOpened());
		ps.setLong(8, rec.getOverdraftLimit());
		ps.setObject(9, rec.getLastStatement());
		ps.setObject(10, rec.getNextStatement());
		ps.setBigDecimal(11, rec.getAvailableBalance());
		ps.setBigDecimal(12, rec.getActualBalance());
	}


	private static Optional<AccountRecord> single(PreparedStatement ps)
			throws SQLException
	{
		try (ResultSet rs = ps.executeQuery())
		{
			return rs.next() ? Optional.of(map(rs)) : Optional.empty();
		}
	}


	private static AccountRecord map(ResultSet rs) throws SQLException
	{
		AccountRecord rec = new AccountRecord();
		rec.setEyecatcher(rs.getString("ACCOUNT_EYECATCHER"));
		rec.setCustomerNumber(rs.getString("ACCOUNT_CUSTOMER_NUMBER"));
		rec.setSortcode(rs.getString("ACCOUNT_SORTCODE"));
		rec.setAccountNumber(rs.getString("ACCOUNT_NUMBER"));
		rec.setAccountType(rs.getString("ACCOUNT_TYPE"));
		rec.setInterestRate(rs.getBigDecimal("ACCOUNT_INTEREST_RATE"));
		rec.setOpened(rs.getObject("ACCOUNT_OPENED", LocalDate.class));
		rec.setOverdraftLimit(rs.getLong("ACCOUNT_OVERDRAFT_LIMIT"));
		rec.setLastStatement(
				rs.getObject("ACCOUNT_LAST_STATEMENT", LocalDate.class));
		rec.setNextStatement(
				rs.getObject("ACCOUNT_NEXT_STATEMENT", LocalDate.class));
		rec.setAvailableBalance(rs.getBigDecimal("ACCOUNT_AVAILABLE_BALANCE"));
		rec.setActualBalance(rs.getBigDecimal("ACCOUNT_ACTUAL_BALANCE"));
		return rec;
	}
}
