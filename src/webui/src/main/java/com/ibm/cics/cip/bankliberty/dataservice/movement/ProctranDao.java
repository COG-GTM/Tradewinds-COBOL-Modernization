/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.movement;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PROCTRAN access. Replaces the WRITE-TO-PROCTRAN-DB2 / WRITE-PROCTRAN-DB2
 * paragraphs of DBCRFUN, XFRFUN, CRECUST and DELCUS.
 */
public class ProctranDao
{

	private static final Logger logger = Logger
			.getLogger("com.ibm.cics.cip.bankliberty.dataservice.movement");

	static final String SQL_INSERT = "INSERT INTO PROCTRAN (PROCTRAN_EYECATCHER, PROCTRAN_SORTCODE, PROCTRAN_NUMBER, PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_REF, PROCTRAN_TYPE, PROCTRAN_DESC, PROCTRAN_AMOUNT) VALUES (?,?,?,?,?,?,?,?,?)";

	static final String SQL_LIST = "SELECT PROCTRAN_EYECATCHER, PROCTRAN_SORTCODE, PROCTRAN_NUMBER, PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_REF, PROCTRAN_TYPE, PROCTRAN_DESC, PROCTRAN_AMOUNT FROM PROCTRAN WHERE PROCTRAN_SORTCODE = ? ORDER BY PROCTRAN_DATE ASC, PROCTRAN_TIME ASC LIMIT ? OFFSET ?";

	/** CRECUST / DELCUS: MOVE ZEROS TO HV-PROCTRAN-ACC-NUMBER. */
	static final String ZERO_ACCOUNT = "00000000";

	private final Connection conn;

	private final Clock clock;

	public ProctranDao(Connection conn, Clock clock)
	{
		this.conn = conn;
		this.clock = clock;
	}

	/**
	 * Build a row stamped like EXEC CICS ASKTIME/FORMATTIME (date, HHMMSS)
	 * with eyecatcher PRTR and the 12-digit task reference.
	 */
	public ProctranRecord newRecord(String sortCode, String accountNumber,
			long taskNumber, String type, String description, BigDecimal amount)
	{
		LocalDateTime now = LocalDateTime.now(clock);
		return new ProctranRecord(ProctranFormat.EYECATCHER,
				ProctranFormat.sortCode(sortCode),
				ProctranFormat.accountNumber(accountNumber), now.toLocalDate(),
				ProctranFormat.time(now.toLocalTime()),
				ProctranFormat.reference(taskNumber), ProctranFormat.type(type),
				ProctranFormat.description(description),
				ProctranFormat.amount(amount));
	}

	/** Insert within the caller's transaction (no commit/rollback). */
	void insert(ProctranRecord r) throws SQLException
	{
		try (PreparedStatement stmt = conn.prepareStatement(SQL_INSERT))
		{
			stmt.setString(1, r.getEyecatcher());
			stmt.setString(2, r.getSortCode());
			stmt.setString(3, r.getAccountNumber());
			stmt.setDate(4, Date.valueOf(r.getDate()));
			stmt.setString(5, r.getTime());
			stmt.setString(6, r.getReference());
			stmt.setString(7, r.getType());
			stmt.setString(8, r.getDescription());
			stmt.setBigDecimal(9, r.getAmount());
			stmt.executeUpdate();
		}
	}

	/**
	 * Generic validated writer: one atomic unit of work. On failure returns
	 * HTTP 500 with the supplied abend code (null for the generic writer).
	 */
	public MovementResult write(ProctranRecord r, String abendCodeOnFailure)
	{
		boolean previousAutoCommit = true;
		try
		{
			previousAutoCommit = conn.getAutoCommit();
			conn.setAutoCommit(false);
			insert(r);
			conn.commit();
			return MovementResult.ok(r);
		}
		catch (SQLException e)
		{
			logger.log(Level.SEVERE, "Unable to write to PROCTRAN", e);
			rollbackQuietly(conn);
			String msg = "Unable to write to PROCTRAN: " + e.getMessage();
			return abendCodeOnFailure == null
					? MovementResult.fail(500, "2", msg)
					: MovementResult.abend(500, "0", abendCodeOnFailure, msg);
		}
		finally
		{
			restoreAutoCommit(conn, previousAutoCommit);
		}
	}

	/** CRECUST WRITE-PROCTRAN-DB2 (WPD010): type OCC; abend HWPT on failure. */
	public MovementResult writeCreateCustomer(String sortCode,
			String customerNumber, String customerName, String dateOfBirth,
			long taskNumber)
	{
		return write(customerRecord(ProctranFormat.TYPE_BRANCH_CREATE_CUSTOMER,
				sortCode, customerNumber, customerName, dateOfBirth,
				taskNumber), "HWPT");
	}

	/**
	 * DELCUS WRITE-PROCTRAN-CUST-DB2 (WPCD010): type ODC; abend HWPT on
	 * failure.
	 */
	public MovementResult writeDeleteCustomer(String sortCode,
			String customerNumber, String customerName, String dateOfBirth,
			long taskNumber)
	{
		return write(customerRecord(ProctranFormat.TYPE_BRANCH_DELETE_CUSTOMER,
				sortCode, customerNumber, customerName, dateOfBirth,
				taskNumber), "HWPT");
	}

	private ProctranRecord customerRecord(String type, String sortCode,
			String customerNumber, String customerName, String dateOfBirth,
			long taskNumber)
	{
		String desc = ProctranFormat.customerDescription(sortCode,
				customerNumber, customerName, dateOfBirth);
		return newRecord(sortCode, ZERO_ACCOUNT, taskNumber, type, desc,
				BigDecimal.ZERO);
	}

	/** Read PROCTRAN for a sort code, oldest first, with LIMIT/OFFSET. */
	public List<ProctranRecord> list(String sortCode, int limit, int offset)
			throws SQLException
	{
		List<ProctranRecord> rows = new ArrayList<>();
		try (PreparedStatement stmt = conn.prepareStatement(SQL_LIST))
		{
			stmt.setString(1, ProctranFormat.sortCode(sortCode));
			stmt.setInt(2, limit);
			stmt.setInt(3, offset);
			try (ResultSet rs = stmt.executeQuery())
			{
				while (rs.next())
				{
					Date d = rs.getDate("PROCTRAN_DATE");
					rows.add(new ProctranRecord(
							rs.getString("PROCTRAN_EYECATCHER"),
							rs.getString("PROCTRAN_SORTCODE"),
							rs.getString("PROCTRAN_NUMBER"),
							d == null ? null : d.toLocalDate(),
							rs.getString("PROCTRAN_TIME"),
							rs.getString("PROCTRAN_REF"),
							rs.getString("PROCTRAN_TYPE"),
							rs.getString("PROCTRAN_DESC"),
							rs.getBigDecimal("PROCTRAN_AMOUNT")));
				}
			}
		}
		return rows;
	}

	static void rollbackQuietly(Connection conn)
	{
		try
		{
			conn.rollback();
		}
		catch (SQLException e)
		{
			logger.log(Level.SEVERE,
					"Rollback failed, possible data integrity issue", e);
		}
	}

	static void restoreAutoCommit(Connection conn, boolean autoCommit)
	{
		try
		{
			conn.setAutoCommit(autoCommit);
		}
		catch (SQLException e)
		{
			logger.log(Level.WARNING, "Unable to restore autocommit", e);
		}
	}
}
