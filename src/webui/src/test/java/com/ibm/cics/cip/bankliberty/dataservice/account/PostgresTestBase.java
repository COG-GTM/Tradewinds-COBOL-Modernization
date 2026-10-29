package com.ibm.cics.cip.bankliberty.dataservice.account;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;


/**
 * Shared postgres:16 container; the committed ACCOUNT / PROCTRAN / CONTROL
 * DDL (etc/install/base/postgres/01_create_tables.sql) is re-applied before
 * every test.
 */
abstract class PostgresTestBase
{

	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
			"postgres:16");

	static
	{
		POSTGRES.start();
	}

	/** 29 Oct 2026 14:05:09 London time (GMT). */
	static final Clock CLOCK = Clock.fixed(
			Instant.parse("2026-10-29T14:05:09Z"), ZoneId.of("Europe/London"));

	static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

	static final String SORTCODE = "987654";

	static final String OTHER_SORTCODE = "123456";

	/** The DDL the parent session commits for production. */
	private static final Path DDL = Paths.get(System.getProperty("basedir", "."),
			"..", "..", "etc", "install", "base", "postgres", "01_create_tables.sql");

	Connection conn;


	@BeforeEach
	void createSchema() throws SQLException
	{
		conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
				POSTGRES.getUsername(), POSTGRES.getPassword());
		try (Statement st = conn.createStatement())
		{
			st.execute("DROP TABLE IF EXISTS ACCOUNT, PROCTRAN, CONTROL CASCADE");
			st.execute("DROP FUNCTION IF EXISTS fail_proctran() CASCADE");
			for (String sql : ddlStatements())
			{
				st.execute(sql);
			}
		}
	}


	private static List<String> ddlStatements()
	{
		try
		{
			StringBuilder sb = new StringBuilder();
			for (String line : Files.readAllLines(DDL))
			{
				if (!line.trim().startsWith("--"))
				{
					sb.append(line).append('\n');
				}
			}
			List<String> out = new ArrayList<>();
			for (String stmt : sb.toString().split(";"))
			{
				if (!stmt.trim().isEmpty())
				{
					out.add(stmt.trim());
				}
			}
			return out;
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("Cannot read " + DDL, e);
		}
	}


	@AfterEach
	void closeConnection() throws SQLException
	{
		conn.close();
	}


	AccountDao dao()
	{
		return new AccountDao(conn, CLOCK);
	}


	AccountDataService service()
	{
		return new AccountDataService(conn, CLOCK, () -> "4711");
	}


	void execute(String sql) throws SQLException
	{
		try (Statement st = conn.createStatement())
		{
			st.execute(sql);
		}
	}


	void failNextProctranInserts() throws SQLException
	{
		execute("CREATE FUNCTION fail_proctran() RETURNS trigger AS $$ BEGIN "
				+ "RAISE EXCEPTION 'simulated PROCTRAN failure'; END $$ LANGUAGE plpgsql");
		execute("CREATE TRIGGER proctran_fail BEFORE INSERT ON PROCTRAN "
				+ "FOR EACH ROW EXECUTE FUNCTION fail_proctran()");
	}


	void insertControl(String name, long value) throws SQLException
	{
		try (PreparedStatement ps = conn.prepareStatement(
				"INSERT INTO CONTROL (CONTROL_NAME, CONTROL_VALUE_NUM, CONTROL_VALUE_STR) VALUES (?,?,'')"))
		{
			ps.setString(1, name);
			ps.setLong(2, value);
			ps.executeUpdate();
		}
	}


	void insertAccountControls(long last, long count) throws SQLException
	{
		insertControl(SORTCODE + "-ACCOUNT-LAST", last);
		insertControl(SORTCODE + "-ACCOUNT-COUNT", count);
	}


	Long controlValue(String name) throws SQLException
	{
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT CONTROL_VALUE_NUM FROM CONTROL WHERE CONTROL_NAME = ?"))
		{
			ps.setString(1, name);
			try (ResultSet rs = ps.executeQuery())
			{
				return rs.next() ? rs.getLong(1) : null;
			}
		}
	}


	void insertAccount(String sortcode, String accountNumber,
			String customerNumber, String type, String actualBalance)
			throws SQLException
	{
		try (PreparedStatement ps = conn.prepareStatement(
				"INSERT INTO ACCOUNT VALUES ('ACCT',?,?,?,?,1.75,?,100,?,?,?,?)"))
		{
			ps.setString(1, customerNumber);
			ps.setString(2, sortcode);
			ps.setString(3, accountNumber);
			ps.setString(4, type);
			ps.setObject(5, LocalDate.of(2001, 2, 3));
			ps.setObject(6, LocalDate.of(2021, 7, 1));
			ps.setObject(7, LocalDate.of(2021, 8, 1));
			ps.setBigDecimal(8, new BigDecimal(actualBalance).subtract(BigDecimal.TEN));
			ps.setBigDecimal(9, new BigDecimal(actualBalance));
			ps.executeUpdate();
		}
	}


	long count(String sql) throws SQLException
	{
		try (Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery(sql))
		{
			rs.next();
			return rs.getLong(1);
		}
	}


	List<Map<String, Object>> rows(String sql) throws SQLException
	{
		List<Map<String, Object>> out = new ArrayList<>();
		try (Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery(sql))
		{
			int n = rs.getMetaData().getColumnCount();
			while (rs.next())
			{
				Map<String, Object> row = new LinkedHashMap<>();
				for (int i = 1; i <= n; i++)
				{
					row.put(rs.getMetaData().getColumnName(i).toUpperCase(),
							rs.getObject(i));
				}
				out.add(row);
			}
		}
		return out;
	}


	static Map<?, ?> account(ServiceResult r)
	{
		return (Map<?, ?>) r.getBody().get("account");
	}
}
