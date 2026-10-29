/*
 *
 *    Copyright IBM Corp. 2023, 2025
 *
 */
package com.ibm.cics.cip.bankliberty.api.json;

import java.io.FileWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Calendar;
import java.util.Map;

import com.ibm.cics.cip.bankliberty.web.db2.Account;
import com.ibm.json.java.JSONArray;
import com.ibm.json.java.JSONObject;

import jakarta.ws.rs.core.Response;

/**
 * A/B scenario: drives the web tier's data-access paths (the same
 * *Internal methods the REST endpoints call) against whatever database
 * CBSA_JDBC_URL points at, and writes every response plus the final
 * ACCOUNT/PROCTRAN/CONTROL contents to a JSON file. Run once against the
 * DB2 baseline and once against the PostgreSQL build, then diff.
 *
 * Wall-clock values (PROCTRAN time/reference, today's date) are masked so
 * the two runs are comparable.
 *
 * Usage: java -DCBSA_JDBC_URL=... -DCBSA_JDBC_USER=... -DCBSA_JDBC_PASSWORD=...
 *        com.ibm.cics.cip.bankliberty.api.json.AbScenario out.json
 */
public class AbScenario
{

	private static final String SORTCODE = "987654";

	private static final JSONObject RESULT = new JSONObject();

	private static String today;


	public static void main(String[] args) throws Exception
	{
		SortCodeResource.sortCodeString = SORTCODE;
		today = new Date(System.currentTimeMillis()).toString();
		RESULT.put("database", url().startsWith("jdbc:db2") ? "DB2" : "PostgreSQL");

		sql("DELETE FROM PROCTRAN");
		sql("DELETE FROM ACCOUNT");
		sql("DELETE FROM CONTROL");
		for (int i = 1; i <= 9; i++)
		{
			sql(String.format("INSERT INTO ACCOUNT VALUES ('ACCT','%010d','%s','%08d',"
					+ "'CURRENT',1.50,DATE('2023-01-01'),100,DATE('2024-01-01'),"
					+ "DATE('2024-02-01'),%d.25,%d.25)", i % 3 + 1, SORTCODE, i,
					i * 100, i * 100));
		}
		sql("INSERT INTO CONTROL VALUES ('" + SORTCODE + "-ACCOUNT-LAST',9,NULL)");

		ProcessedTransactionResource ptr = new ProcessedTransactionResource();

		ProcessedTransactionAccountJSON acct = new ProcessedTransactionAccountJSON();
		acct.setSortCode(SORTCODE);
		acct.setAccountNumber("00000123");
		acct.setCustomerNumber("0000000042");
		acct.setType("ISA");
		acct.setLastStatement(date(2024, 1, 31));
		acct.setNextStatement(date(2024, 2, 29));
		acct.setActualBalance(new BigDecimal("12.34"));
		step("1 PROCTRAN create account (ICA)", ptr.writeCreateAccountInternal(acct));
		step("2 PROCTRAN delete account (IDA)", ptr.writeDeleteAccountInternal(acct));

		ProcessedTransactionCreateCustomerJSON cc = new ProcessedTransactionCreateCustomerJSON();
		cc.setSortCode(SORTCODE);
		cc.setAccountNumber("0");
		cc.setCustomerNumber("0000000077");
		cc.setCustomerName("Mr Ada Lovelace");
		cc.setCustomerDOB(date(1985, 12, 10));
		step("3 PROCTRAN create customer (ICC)", ptr.writeCreateCustomerInternal(cc));
		ProcessedTransactionDeleteCustomerJSON dc = new ProcessedTransactionDeleteCustomerJSON();
		dc.setSortCode(SORTCODE);
		dc.setAccountNumber("0");
		dc.setCustomerNumber("0000000077");
		dc.setCustomerName("Mr Ada Lovelace");
		dc.setCustomerDOB(date(1985, 12, 10));
		step("4 PROCTRAN delete customer (IDC)", ptr.writeDeleteCustomerInternal(dc));

		ProcessedTransactionTransferLocalJSON tfr = new ProcessedTransactionTransferLocalJSON();
		tfr.setSortCode(SORTCODE);
		tfr.setAccountNumber("00000001");
		tfr.setTargetAccountNumber("00000002");
		tfr.setAmount(new BigDecimal("25.50"));
		step("5 PROCTRAN transfer (TFR)", ptr.writeTransferLocalInternal(tfr));

		ProcessedTransactionDebitCreditJSON deb = new ProcessedTransactionDebitCreditJSON();
		deb.setSortCode(SORTCODE);
		deb.setAccountNumber("00000001");
		deb.setAmount(new BigDecimal("-10.05"));
		step("6 PROCTRAN debit (DEB)", ptr.writeInternal(deb));
		ProcessedTransactionDebitCreditJSON cre = new ProcessedTransactionDebitCreditJSON();
		cre.setSortCode(SORTCODE);
		cre.setAccountNumber("00000001");
		cre.setAmount(new BigDecimal("99.99"));
		step("7 PROCTRAN credit (CRE)", ptr.writeInternal(cre));

		step("8 Read + decode all PROCTRAN", ptr.getProcessedTransactionInternal(null, null));
		step("9 Read PROCTRAN page (limit 2, offset 3)", ptr.getProcessedTransactionInternal(2, 3));

		JSONArray page = new JSONArray();
		for (Account a : new Account().getAccountsByBalance(Integer.valueOf(SORTCODE),
				new BigDecimal("250"), false, 2, 3))
		{
			page.add(a.getAccountNumber() + " " + a.getActualBalance());
		}
		commit();
		RESULT.put("10 Accounts by balance (>250, offset 2, limit 3)", page);

		step("11 Delete account 8 (ACCOUNT + PROCTRAN commit)",
				new AccountsResource().deleteAccountInternal(8L));

		failProctranInserts(true);
		Response failed = new AccountsResource().deleteAccountInternal(9L);
		commit();
		failProctranInserts(false);
		JSONObject rollback = new JSONObject();
		rollback.put("httpStatus", failed.getStatus());
		rollback.put("account9StillExists",
				count("SELECT count(*) FROM ACCOUNT WHERE ACCOUNT_NUMBER='00000009'") == 1);
		RESULT.put("12 Delete account 9 with PROCTRAN insert failing (rollback)", rollback);

		RESULT.put("13 Final ACCOUNT table", dump("SELECT * FROM ACCOUNT ORDER BY ACCOUNT_NUMBER"));
		RESULT.put("14 Final PROCTRAN table", dump("SELECT * FROM PROCTRAN ORDER BY "
				+ "PROCTRAN_TYPE, PROCTRAN_NUMBER, PROCTRAN_AMOUNT"));
		RESULT.put("15 Final CONTROL table", dump("SELECT * FROM CONTROL"));

		try (Writer w = new FileWriter(args[0]))
		{
			w.write(RESULT.serialize(true));
		}
		System.out.println("Wrote " + args[0] + " for " + RESULT.get("database"));
	}


	private static void step(String name, Response response) throws Exception
	{
		commit();
		JSONObject r = new JSONObject();
		r.put("httpStatus", response.getStatus());
		Object entity = response.getEntity();
		if (entity instanceof String && ((String) entity).startsWith("{"))
		{
			r.put("body", mask(JSONObject.parse((String) entity)));
		}
		RESULT.put(name, r);
	}


	private static Object mask(Object value)
	{
		if (value instanceof JSONObject)
		{
			JSONObject in = (JSONObject) value;
			JSONObject out = new JSONObject();
			for (Object o : in.entrySet())
			{
				Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
				String key = e.getKey().toString().toLowerCase();
				boolean volatileKey = key.contains("time") || key.equals("ref")
						|| key.contains("reference") || key.equals("id");
				out.put(e.getKey(), volatileKey ? "<masked>" : mask(e.getValue()));
			}
			return out;
		}
		if (value instanceof JSONArray)
		{
			JSONArray out = new JSONArray();
			for (Object o : (JSONArray) value)
			{
				out.add(mask(o));
			}
			return out;
		}
		return value;
	}


	private static JSONArray dump(String query) throws SQLException
	{
		JSONArray rows = new JSONArray();
		try (Connection c = connect(); Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(query))
		{
			ResultSetMetaData md = rs.getMetaData();
			while (rs.next())
			{
				StringBuilder row = new StringBuilder();
				for (int i = 1; i <= md.getColumnCount(); i++)
				{
					String col = md.getColumnName(i).toUpperCase();
					Object v = rs.getObject(i);
					String text;
					if (col.equals("PROCTRAN_TIME") || col.equals("PROCTRAN_REF"))
					{
						text = "<masked>";
					}
					else if (v instanceof BigDecimal)
					{
						text = ((BigDecimal) v).toPlainString();
					}
					else if (v instanceof java.sql.Date)
					{
						text = v.toString().equals(today) ? "<today>" : v.toString();
					}
					else
					{
						text = v == null ? "NULL" : "'" + v + "'";
					}
					row.append(i > 1 ? " | " : "").append(text);
				}
				rows.add(row.toString());
			}
		}
		return rows;
	}


	private static void failProctranInserts(boolean on) throws SQLException
	{
		boolean db2 = url().startsWith("jdbc:db2");
		if (on && db2)
		{
			sql("CREATE TRIGGER PROCTRAN_FAIL NO CASCADE BEFORE INSERT ON PROCTRAN "
					+ "FOR EACH ROW SIGNAL SQLSTATE '75001' ('PROCTRAN unavailable')");
		}
		else if (on)
		{
			sql("CREATE OR REPLACE FUNCTION proctran_fail() RETURNS trigger AS $$ "
					+ "BEGIN RAISE EXCEPTION 'PROCTRAN unavailable'; END $$ LANGUAGE plpgsql");
			sql("CREATE TRIGGER proctran_fail BEFORE INSERT ON PROCTRAN "
					+ "FOR EACH ROW EXECUTE FUNCTION proctran_fail()");
		}
		else if (db2)
		{
			sql("DROP TRIGGER PROCTRAN_FAIL");
		}
		else
		{
			sql("DROP TRIGGER proctran_fail ON PROCTRAN");
			sql("DROP FUNCTION proctran_fail()");
		}
	}


	private static void commit()
	{
		new HBankDataAccess().terminate();
	}


	private static Date date(int year, int month, int day)
	{
		Calendar c = Calendar.getInstance();
		c.clear();
		c.set(year, month - 1, day);
		return new Date(c.getTimeInMillis());
	}


	private static String url()
	{
		return BankRuntime.setting(BankRuntime.JDBC_URL);
	}


	private static Connection connect() throws SQLException
	{
		return DriverManager.getConnection(url(),
				BankRuntime.setting(BankRuntime.JDBC_USER),
				BankRuntime.setting(BankRuntime.JDBC_PASSWORD));
	}


	private static long count(String query) throws SQLException
	{
		try (Connection c = connect(); Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(query))
		{
			rs.next();
			return rs.getLong(1);
		}
	}


	private static void sql(String statement) throws SQLException
	{
		try (Connection c = connect(); Statement s = c.createStatement())
		{
			s.execute(statement);
		}
	}
}
