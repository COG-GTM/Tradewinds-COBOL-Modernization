/*
 *
 *    Copyright IBM Corp. 2023, 2025
 *
 */

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * Copies ACCOUNT, PROCTRAN and CONTROL from DB2 to PostgreSQL over JDBC and
 * verifies the copy.
 *
 * CHAR columns are copied as-is (no trimming), so the fixed-offset
 * PROCTRAN_DESC layout and leading-zero sort codes/account numbers survive;
 * DECIMAL columns go through BigDecimal so scale is kept; DATE columns go
 * through java.sql.Date. After loading, each table's row count and an
 * order-independent SHA-256 over every column value is compared between the
 * two databases. Exit code is non-zero if any table differs.
 *
 * Usage (Java 17+, single-file launch):
 *   java -cp db2jcc.jar:postgresql.jar MigrateDb2ToPostgres.java [--verify-only]
 * Connection settings come from environment variables:
 *   DB2_JDBC_URL, DB2_USER, DB2_PASSWORD, DB2_SCHEMA (optional)
 *   PG_JDBC_URL, PG_USER, PG_PASSWORD
 */
public class MigrateDb2ToPostgres
{

	private static final String[] TABLES = { "CONTROL", "ACCOUNT", "PROCTRAN" };

	private static final int BATCH = 1000;


	public static void main(String[] args) throws Exception
	{
		java.util.Set<String> opts = java.util.Set.of(args);
		boolean verifyOnly = opts.contains("--verify-only");
		boolean replace = opts.contains("--replace");
		String schema = System.getenv("DB2_SCHEMA");
		String prefix = schema == null || schema.isBlank() ? "" : schema + ".";

		try (Connection db2 = DriverManager.getConnection(env("DB2_JDBC_URL"),
				env("DB2_USER"), env("DB2_PASSWORD"));
				Connection pg = DriverManager.getConnection(env("PG_JDBC_URL"),
						env("PG_USER"), env("PG_PASSWORD")))
		{
			db2.setReadOnly(true);
			pg.setAutoCommit(false);
			if (!verifyOnly)
			{
				// Refuse to touch a populated target unless --replace is given:
				// after cutover PostgreSQL may hold rows that Db2 never saw.
				try (Statement st = pg.createStatement())
				{
					for (String table : TABLES)
					{
						try (ResultSet rs = st
								.executeQuery("SELECT COUNT(*) FROM " + table))
						{
							rs.next();
							if (rs.getLong(1) > 0 && !replace)
							{
								System.err.println(table + " is not empty; rerun with"
										+ " --replace to overwrite it from Db2, or"
										+ " --verify-only to compare");
								System.exit(2);
							}
						}
					}
					if (replace)
					{
						for (String table : TABLES)
						{
							st.executeUpdate("DELETE FROM " + table);
						}
					}
				}
				for (String table : TABLES)
				{
					copy(db2, prefix + table, pg, table);
				}
				pg.commit();
			}

			boolean ok = true;
			System.out.printf("%-10s %10s %10s  %-16s %-16s %s%n", "TABLE",
					"DB2 ROWS", "PG ROWS", "DB2 SHA-256", "PG SHA-256",
					"RESULT");
			for (String table : TABLES)
			{
				Digest a = digest(db2, prefix + table);
				Digest b = digest(pg, table);
				boolean match = a.rows == b.rows && a.sha.equals(b.sha);
				ok &= match;
				System.out.printf("%-10s %10d %10d  %-16s %-16s %s%n", table,
						a.rows, b.rows, a.sha.substring(0, 16),
						b.sha.substring(0, 16), match ? "MATCH" : "MISMATCH");
			}
			System.exit(ok ? 0 : 1);
		}
	}


	private static void copy(Connection src, String srcTable, Connection dst,
			String dstTable) throws SQLException
	{
		try (Statement s = src.createStatement();
				ResultSet rs = s.executeQuery("SELECT * FROM " + srcTable))
		{
			ResultSetMetaData md = rs.getMetaData();
			int n = md.getColumnCount();
			List<String> cols = new ArrayList<>();
			for (int i = 1; i <= n; i++)
			{
				cols.add(md.getColumnName(i));
			}
			String sql = "INSERT INTO " + dstTable + " (" + String.join(",", cols)
					+ ") VALUES (" + String.join(",", Collections.nCopies(n, "?"))
					+ ")";
			long rows = 0;
			try (PreparedStatement ins = dst.prepareStatement(sql))
			{
				while (rs.next())
				{
					for (int i = 1; i <= n; i++)
					{
						switch (md.getColumnType(i))
						{
						case Types.DECIMAL:
						case Types.NUMERIC:
							ins.setBigDecimal(i, rs.getBigDecimal(i));
							break;
						case Types.DATE:
							ins.setDate(i, rs.getDate(i));
							break;
						case Types.INTEGER:
							ins.setObject(i, rs.getObject(i), Types.INTEGER);
							break;
						default:
							ins.setString(i, rs.getString(i));
						}
					}
					ins.addBatch();
					if (++rows % BATCH == 0)
					{
						ins.executeBatch();
					}
				}
				ins.executeBatch();
			}
			System.out.printf("Copied %d rows %s -> %s%n", rows, srcTable,
					dstTable);
		}
	}


	private static Digest digest(Connection c, String table) throws Exception
	{
		List<String> rows = new ArrayList<>();
		try (Statement s = c.createStatement();
				ResultSet rs = s.executeQuery("SELECT * FROM " + table))
		{
			ResultSetMetaData md = rs.getMetaData();
			List<String> names = new ArrayList<>();
			for (int i = 1; i <= md.getColumnCount(); i++)
			{
				names.add(md.getColumnName(i).toUpperCase());
			}
			List<String> sorted = new ArrayList<>(names);
			Collections.sort(sorted);
			while (rs.next())
			{
				StringBuilder row = new StringBuilder();
				for (String name : sorted)
				{
					row.append(name).append('=').append(canonical(rs,
							names.indexOf(name) + 1, md)).append('\u0001');
				}
				rows.add(row.toString());
			}
		}
		Collections.sort(rows);
		MessageDigest sha = MessageDigest.getInstance("SHA-256");
		for (String row : rows)
		{
			sha.update(row.getBytes(StandardCharsets.UTF_8));
			sha.update((byte) '\n');
		}
		return new Digest(rows.size(), HexFormat.of().formatHex(sha.digest()));
	}


	private static String canonical(ResultSet rs, int i, ResultSetMetaData md)
			throws SQLException
	{
		switch (md.getColumnType(i))
		{
		case Types.DECIMAL:
		case Types.NUMERIC:
			BigDecimal d = rs.getBigDecimal(i);
			return d == null ? "<null>" : d.setScale(md.getScale(i)).toPlainString();
		case Types.DATE:
			java.sql.Date date = rs.getDate(i);
			return date == null ? "<null>" : date.toString();
		default:
			Object o = rs.getObject(i);
			return o == null ? "<null>" : o.toString();
		}
	}


	private static String env(String name)
	{
		String v = System.getenv(name);
		if (v == null)
		{
			throw new IllegalStateException("Missing environment variable " + name);
		}
		return v;
	}


	private record Digest(long rows, String sha)
	{
	}
}
