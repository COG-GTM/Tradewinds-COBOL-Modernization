/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.movement;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared postgres:16 container with the authoritative BankLiberty schema.
 * One container per JVM; each test starts from empty tables.
 */
abstract class PostgresTestSupport
{

	static final String SORT_CODE = "987654";

	/** 2026-10-29 14:05:09 UTC. */
	static final Clock FIXED_CLOCK = Clock.fixed(
			Instant.parse("2026-10-29T14:05:09Z"), ZoneOffset.UTC);

	/** The parent's authoritative DDL. */
	static final String DDL_PATH = "../../etc/install/base/postgres/01_create_tables.sql";

	/** Failure-injection helpers (same pattern as PostgresDataLayerTest). */
	static final String TEST_FUNCTIONS = String.join("\n",
			"CREATE FUNCTION fail_proctran() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'simulated PROCTRAN failure'; END; $$ LANGUAGE plpgsql;",
			"CREATE FUNCTION fail_account_update() RETURNS trigger AS $$ BEGIN",
			"  IF NEW.ACCOUNT_NUMBER = current_setting('test.fail_account', true) THEN",
			"    RAISE EXCEPTION 'simulated ACCOUNT update failure'; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql;");

	static final PostgreSQLContainer<?> POSTGRES;

	static
	{
		POSTGRES = new PostgreSQLContainer<>("postgres:16");
		POSTGRES.start();
		try (Connection c = newConnection(); Statement s = c.createStatement())
		{
			s.execute(Files.readString(Path.of(DDL_PATH)));
			s.execute(TEST_FUNCTIONS);
		}
		catch (SQLException | java.io.IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	Connection conn;

	static Connection newConnection() throws SQLException
	{
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(),
				POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	@BeforeEach
	void openAndReset() throws SQLException
	{
		conn = newConnection();
		exec("DROP TRIGGER IF EXISTS proctran_fail ON PROCTRAN");
		exec("DROP TRIGGER IF EXISTS account_fail ON ACCOUNT");
		exec("TRUNCATE ACCOUNT, PROCTRAN, CONTROL");
	}

	@AfterEach
	void close() throws SQLException
	{
		if (conn != null)
		{
			conn.close();
		}
	}

	void exec(String sql) throws SQLException
	{
		try (Statement s = conn.createStatement())
		{
			s.execute(sql);
		}
	}

	/** Make every PROCTRAN insert fail, like a DB2 SQLCODE != 0. */
	void failProctranInserts() throws SQLException
	{
		exec("CREATE TRIGGER proctran_fail BEFORE INSERT ON PROCTRAN FOR EACH ROW EXECUTE FUNCTION fail_proctran()");
	}

	/** Make UPDATEs of one ACCOUNT row fail (session-level setting). */
	void failAccountUpdate(String accountNumber) throws SQLException
	{
		exec("CREATE TRIGGER account_fail BEFORE UPDATE ON ACCOUNT FOR EACH ROW EXECUTE FUNCTION fail_account_update()");
		exec("SET test.fail_account = '" + accountNumber + "'");
	}

	void insertAccount(String accountNumber, String type, String available,
			String actual) throws SQLException
	{
		try (PreparedStatement ps = conn.prepareStatement(
				"INSERT INTO ACCOUNT VALUES ('ACCT', '0000000001', ?, ?, ?, 1.50, DATE '2020-01-01', 100, DATE '2026-10-01', DATE '2026-11-01', ?, ?)"))
		{
			ps.setString(1, SORT_CODE);
			ps.setString(2, accountNumber);
			ps.setString(3, type);
			ps.setBigDecimal(4, new BigDecimal(available));
			ps.setBigDecimal(5, new BigDecimal(actual));
			ps.executeUpdate();
		}
	}

	BigDecimal[] balances(String accountNumber) throws SQLException
	{
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?"))
		{
			ps.setString(1, SORT_CODE);
			ps.setString(2, accountNumber);
			try (ResultSet rs = ps.executeQuery())
			{
				rs.next();
				return new BigDecimal[] { rs.getBigDecimal(1),
						rs.getBigDecimal(2) };
			}
		}
	}

	int proctranCount() throws SQLException
	{
		try (Statement s = conn.createStatement();
				ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM PROCTRAN"))
		{
			rs.next();
			return rs.getInt(1);
		}
	}

	static Clock clockAt(String isoInstant)
	{
		return Clock.fixed(Instant.parse(isoInstant), ZoneId.of("UTC"));
	}
}
