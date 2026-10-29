/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.movement;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Money movement units of work replacing the embedded SQL of DBCRFUN
 * (UPDATE-ACCOUNT-DB2 + WRITE-TO-PROCTRAN-DB2) and XFRFUN
 * (UPDATE-ACCOUNT-DB2 / -FROM / -TO + WRITE-TO-PROCTRAN-DB2). Every public
 * method is atomic: it commits on success and rolls back on any failure,
 * preserving the COBOL EXEC CICS SYNCPOINT ROLLBACK / abend backout.
 */
public class MovementDao
{

	private static final Logger logger = Logger
			.getLogger("com.ibm.cics.cip.bankliberty.dataservice.movement");

	static final String SQL_SELECT_FOR_UPDATE = "SELECT ACCOUNT_TYPE, ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ? FOR UPDATE";

	static final String SQL_UPDATE_BALANCES = "UPDATE ACCOUNT SET ACCOUNT_AVAILABLE_BALANCE = ?, ACCOUNT_ACTUAL_BALANCE = ? WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?";

	/** XFRFUN: IF DB2-DEADLOCK-RETRY < 6 ... GO TO UPDATE-ACCOUNT-DB2. */
	static final int MAX_DEADLOCK_RETRIES = 5;

	private static final String MORTGAGE = "MORTGAGE";

	private static final String LOAN = "LOAN    ";

	private final Connection conn;

	private final ProctranDao proctranDao;

	private final long deadlockRetryDelayMillis;

	public MovementDao(Connection conn, Clock clock)
	{
		this(conn, clock, 1000L);
	}

	/**
	 * @param deadlockRetryDelayMillis XFRFUN uses EXEC CICS DELAY FOR
	 *            SECONDS(1) between deadlock retries
	 */
	public MovementDao(Connection conn, Clock clock,
			long deadlockRetryDelayMillis)
	{
		this.conn = conn;
		this.proctranDao = new ProctranDao(conn, clock);
		this.deadlockRetryDelayMillis = deadlockRetryDelayMillis;
	}

	/**
	 * DBCRFUN. Negative amount = debit, zero or positive = credit.
	 */
	public MovementResult debitCredit(String sortCode, String accountNumber,
			BigDecimal amount, int facilityType, String applId, String userId,
			long taskNumber)
	{
		String sc = ProctranFormat.sortCode(sortCode);
		String acc = ProctranFormat.accountNumber(accountNumber);
		BigDecimal amt = ProctranFormat.amount(amount);
		boolean debit = amt.signum() < 0;
		boolean payment = facilityType == ProctranFormat.FACILITY_TYPE_PAYMENT;
		ProctranRecord proctran = proctranDao.newRecord(sc, acc, taskNumber,
				ProctranFormat.debitCreditType(debit, facilityType),
				ProctranFormat.debitCreditDescription(debit, facilityType,
						applId, userId),
				amt);

		boolean previousAutoCommit = true;
		try
		{
			previousAutoCommit = conn.getAutoCommit();
			conn.setAutoCommit(false);
		}
		catch (SQLException e)
		{
			return MovementResult.fail(500, "2", e.getMessage());
		}
		try
		{
			AccountRow row;
			try
			{
				row = selectForUpdate(sc, acc);
			}
			catch (SQLException e)
			{
				logger.log(Level.SEVERE, "DBCRFUN select failed", e);
				ProctranDao.rollbackQuietly(conn);
				return MovementResult.fail(500, "2", e.getMessage());
			}
			if (row == null)
			{
				ProctranDao.rollbackQuietly(conn);
				return MovementResult.fail(404, "1", "Account not found");
			}

			boolean mortgageOrLoan = MORTGAGE.equals(row.type)
					|| LOAN.equals(row.type);
			if (debit)
			{
				if (mortgageOrLoan && payment)
				{
					ProctranDao.rollbackQuietly(conn);
					return MovementResult.fail(400, "4",
							"Payments not allowed on MORTGAGE or LOAN accounts");
				}
				if (row.available.add(amt).signum() < 0 && payment)
				{
					ProctranDao.rollbackQuietly(conn);
					return MovementResult.fail(400, "3", "Insufficient funds");
				}
			}
			if (mortgageOrLoan && payment)
			{
				ProctranDao.rollbackQuietly(conn);
				return MovementResult.fail(400, "4",
						"Payments not allowed on MORTGAGE or LOAN accounts");
			}

			BigDecimal newAvailable = row.available.add(amt);
			BigDecimal newActual = row.actual.add(amt);
			try
			{
				updateBalances(sc, acc, newAvailable, newActual);
			}
			catch (SQLException e)
			{
				logger.log(Level.SEVERE, "DBCRFUN update failed", e);
				ProctranDao.rollbackQuietly(conn);
				return MovementResult.fail(500, "2", e.getMessage())
						.withBalances(newAvailable, newActual);
			}

			try
			{
				proctranDao.insert(proctran);
			}
			catch (SQLException e)
			{
				logger.log(Level.SEVERE,
						"UNABLE TO WRITE TO PROCTRAN DB2 DATASTORE", e);
				ProctranDao.rollbackQuietly(conn);
				// COBOL: MOVE '02' TO COMM-FAIL-CODE (PIC X) stores '0'.
				return MovementResult.fail(500, "0",
						"Unable to write to PROCTRAN, account update rolled back: "
								+ e.getMessage())
						.withBalances(newAvailable, newActual);
			}

			try
			{
				conn.commit();
			}
			catch (SQLException e)
			{
				ProctranDao.rollbackQuietly(conn);
				return MovementResult.fail(500, "2", e.getMessage());
			}
			return MovementResult.ok(proctran).withBalances(newAvailable,
					newActual);
		}
		finally
		{
			ProctranDao.restoreAutoCommit(conn, previousAutoCommit);
		}
	}

	/**
	 * XFRFUN. Both accounts are in {@code sortCode} (XFRFUN moves SORTCODE
	 * to COMM-FSCODE and COMM-TSCODE).
	 */
	public MovementResult transfer(String sortCode, String fromAccount,
			String toAccount, BigDecimal amount, long taskNumber)
	{
		String sc = ProctranFormat.sortCode(sortCode);
		String from = ProctranFormat.accountNumber(fromAccount);
		String to = ProctranFormat.accountNumber(toAccount);
		BigDecimal amt = ProctranFormat.amount(amount);

		// A010: IF COMM-AMT <= ZERO -> fail code '4' (checked first)
		if (amt.signum() <= 0)
		{
			return MovementResult.fail(400, "4",
					"Transfer amount must be greater than zero");
		}
		// UAD010: same account -> EXEC CICS ABEND ABCODE('SAME')
		if (from.equals(to))
		{
			return MovementResult.abend(400, "0", "SAME",
					"Cannot transfer to the same account");
		}

		ProctranRecord proctran = proctranDao.newRecord(sc, from, taskNumber,
				ProctranFormat.TYPE_TRANSFER,
				ProctranFormat.transferDescription(sc, to), amt);

		boolean previousAutoCommit = true;
		try
		{
			previousAutoCommit = conn.getAutoCommit();
			conn.setAutoCommit(false);
		}
		catch (SQLException e)
		{
			return MovementResult.fail(500, "3", e.getMessage());
		}
		try
		{
			for (int retry = 0;; retry++)
			{
				try
				{
					return transferOnce(sc, from, to, amt, proctran);
				}
				catch (DeadlockException e)
				{
					ProctranDao.rollbackQuietly(conn);
					if (retry >= MAX_DEADLOCK_RETRIES)
					{
						return e.exhausted;
					}
					logger.log(Level.WARNING,
							"XFRFUN deadlock, retrying (" + (retry + 1) + ")");
					sleep(deadlockRetryDelayMillis);
				}
			}
		}
		finally
		{
			ProctranDao.restoreAutoCommit(conn, previousAutoCommit);
		}
	}

	private MovementResult transferOnce(String sc, String from, String to,
			BigDecimal amt, ProctranRecord proctran) throws DeadlockException
	{
		Step fromStep;
		Step toStep;
		// UAD010: lock/update the lower account number first
		if (Long.parseLong(from) < Long.parseLong(to))
		{
			fromStep = updateFrom(sc, from, amt);
			if (fromStep.failure != null)
			{
				ProctranDao.rollbackQuietly(conn);
				return fromStep.failure;
			}
			toStep = updateTo(sc, to, amt);
			if (toStep.failure != null)
			{
				ProctranDao.rollbackQuietly(conn);
				return toStep.failure;
			}
		}
		else
		{
			toStep = updateTo(sc, to, amt);
			if (toStep.failure != null)
			{
				ProctranDao.rollbackQuietly(conn);
				return toStep.failure;
			}
			fromStep = updateFrom(sc, from, amt);
			if (fromStep.failure != null)
			{
				ProctranDao.rollbackQuietly(conn);
				return fromStep.failure;
			}
		}

		try
		{
			proctranDao.insert(proctran);
		}
		catch (SQLException e)
		{
			logger.log(Level.SEVERE,
					"Unable to WRITE to PROCTRAN DB2 datastore", e);
			ProctranDao.rollbackQuietly(conn);
			return MovementResult.abend(500, "0", "WPCD",
					"Unable to write to PROCTRAN, both account updates rolled back: "
							+ e.getMessage());
		}

		try
		{
			conn.commit();
		}
		catch (SQLException e)
		{
			if (isDeadlock(e))
			{
				throw new DeadlockException(MovementResult.abend(500, "3",
						"RUF3", e.getMessage()));
			}
			ProctranDao.rollbackQuietly(conn);
			return MovementResult.fail(500, "3", e.getMessage());
		}
		return MovementResult.ok(proctran)
				.withBalances(fromStep.available, fromStep.actual)
				.withToBalances(toStep.available, toStep.actual);
	}

	/**
	 * UPDATE-ACCOUNT-DB2-FROM: not found -> '1'; other SQL error -> '3' and
	 * the caller abends 'FROM'.
	 */
	private Step updateFrom(String sc, String acc, BigDecimal amt)
			throws DeadlockException
	{
		AccountRow row;
		try
		{
			row = selectForUpdate(sc, acc);
		}
		catch (SQLException e)
		{
			return sqlFailure(e, MovementResult.abend(500, "3", "FROM",
					"Error reading FROM account: " + e.getMessage()));
		}
		if (row == null)
		{
			return Step.failed(
					MovementResult.fail(404, "1", "FROM account not found"));
		}
		BigDecimal newAvailable = row.available.subtract(amt);
		BigDecimal newActual = row.actual.subtract(amt);
		try
		{
			updateBalances(sc, acc, newAvailable, newActual);
		}
		catch (SQLException e)
		{
			return sqlFailure(e, MovementResult.abend(500, "3", "FROM",
					"Error updating FROM account: " + e.getMessage()));
		}
		return Step.ok(newAvailable, newActual);
	}

	/**
	 * UPDATE-ACCOUNT-DB2-TO: not found -> '2'; select error -> '3' + abend
	 * RUF2; update error -> abend RUF3 (fail code left unchanged).
	 */
	private Step updateTo(String sc, String acc, BigDecimal amt)
			throws DeadlockException
	{
		AccountRow row;
		try
		{
			row = selectForUpdate(sc, acc);
		}
		catch (SQLException e)
		{
			return sqlFailure(e, MovementResult.abend(500, "3", "RUF2",
					"Error reading TO account: " + e.getMessage()));
		}
		if (row == null)
		{
			return Step.failed(
					MovementResult.fail(404, "2", "TO account not found"));
		}
		BigDecimal newAvailable = row.available.add(amt);
		BigDecimal newActual = row.actual.add(amt);
		try
		{
			updateBalances(sc, acc, newAvailable, newActual);
		}
		catch (SQLException e)
		{
			return sqlFailure(e, MovementResult.abend(500, "0", "RUF3",
					"Error updating TO account: " + e.getMessage()));
		}
		return Step.ok(newAvailable, newActual);
	}

	private Step sqlFailure(SQLException e, MovementResult failure)
			throws DeadlockException
	{
		logger.log(Level.SEVERE, failure.getMessage(), e);
		if (isDeadlock(e))
		{
			throw new DeadlockException(failure);
		}
		return Step.failed(failure);
	}

	private AccountRow selectForUpdate(String sc, String acc)
			throws SQLException
	{
		try (PreparedStatement stmt = conn
				.prepareStatement(SQL_SELECT_FOR_UPDATE))
		{
			stmt.setString(1, sc);
			stmt.setString(2, acc);
			try (ResultSet rs = stmt.executeQuery())
			{
				if (!rs.next())
				{
					return null;
				}
				return new AccountRow(
						ProctranFormat.fixed(rs.getString(1), 8),
						rs.getBigDecimal(2), rs.getBigDecimal(3));
			}
		}
	}

	private void updateBalances(String sc, String acc, BigDecimal available,
			BigDecimal actual) throws SQLException
	{
		try (PreparedStatement stmt = conn
				.prepareStatement(SQL_UPDATE_BALANCES))
		{
			stmt.setBigDecimal(1, available);
			stmt.setBigDecimal(2, actual);
			stmt.setString(3, sc);
			stmt.setString(4, acc);
			if (stmt.executeUpdate() != 1)
			{
				throw new SQLException("ACCOUNT row " + sc + "/" + acc
						+ " not updated");
			}
		}
	}

	static boolean isDeadlock(SQLException e)
	{
		// 40P01 deadlock_detected, 40001 serialization_failure (DB2 -911)
		return "40P01".equals(e.getSQLState())
				|| "40001".equals(e.getSQLState());
	}

	private static void sleep(long millis)
	{
		if (millis <= 0)
		{
			return;
		}
		try
		{
			Thread.sleep(millis);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

	private static final class AccountRow
	{

		final String type;

		final BigDecimal available;

		final BigDecimal actual;

		AccountRow(String type, BigDecimal available, BigDecimal actual)
		{
			this.type = type;
			this.available = available;
			this.actual = actual;
		}
	}

	private static final class Step
	{

		final MovementResult failure;

		final BigDecimal available;

		final BigDecimal actual;

		private Step(MovementResult failure, BigDecimal available,
				BigDecimal actual)
		{
			this.failure = failure;
			this.available = available;
			this.actual = actual;
		}

		static Step ok(BigDecimal available, BigDecimal actual)
		{
			return new Step(null, available, actual);
		}

		static Step failed(MovementResult failure)
		{
			return new Step(failure, null, null);
		}
	}

	private static final class DeadlockException extends Exception
	{

		private static final long serialVersionUID = 1L;

		final transient MovementResult exhausted;

		DeadlockException(MovementResult exhausted)
		{
			super(exhausted.getMessage());
			this.exhausted = exhausted;
		}
	}
}
