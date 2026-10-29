/*
 *
 *    Copyright IBM Corp. 2023, 2025
 *
 */
package com.ibm.cics.cip.bankliberty.api.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ibm.cics.cip.bankliberty.web.db2.Account;
import com.ibm.json.java.JSONArray;
import com.ibm.json.java.JSONObject;

import jakarta.ws.rs.core.Response;

/**
 * Runs the web tier's data layer against the PostgreSQL DDL in
 * etc/install/base/postgres, outside CICS (BankRuntime standalone mode).
 */
@Testcontainers
class PostgresDataLayerTest
{

	private static final String SORTCODE = "987654";

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
			"postgres:16");


	@BeforeAll
	static void createSchema() throws Exception
	{
		System.setProperty(BankRuntime.JDBC_URL, POSTGRES.getJdbcUrl());
		System.setProperty(BankRuntime.JDBC_USER, POSTGRES.getUsername());
		System.setProperty(BankRuntime.JDBC_PASSWORD, POSTGRES.getPassword());
		SortCodeResource.sortCodeString = SORTCODE;
		String ddl = Files.readString(Path
				.of("../../etc/install/base/postgres/01_create_tables.sql"));
		try (Connection c = connect(); Statement s = c.createStatement())
		{
			s.execute(ddl);
		}
	}


	@AfterAll
	static void clearProperties()
	{
		System.clearProperty(BankRuntime.JDBC_URL);
		System.clearProperty(BankRuntime.JDBC_USER);
		System.clearProperty(BankRuntime.JDBC_PASSWORD);
	}


	@BeforeEach
	void emptyTables() throws SQLException
	{
		sql("DROP TRIGGER IF EXISTS proctran_fail ON PROCTRAN");
		sql("DELETE FROM PROCTRAN");
		sql("DELETE FROM ACCOUNT");
		sql("DELETE FROM CONTROL");
	}


	@Test
	void createAndDeleteAccountRecordsDecode() throws Exception
	{
		ProcessedTransactionAccountJSON account = new ProcessedTransactionAccountJSON();
		account.setSortCode(SORTCODE);
		account.setAccountNumber("00000123");
		account.setCustomerNumber("0000000042");
		account.setType("ISA");
		account.setLastStatement(date(2024, 1, 31));
		account.setNextStatement(date(2024, 2, 29));
		account.setActualBalance(new BigDecimal("12.34"));

		ProcessedTransactionResource resource = new ProcessedTransactionResource();
		assertEquals(200, resource.writeCreateAccountInternal(account).getStatus());
		assertEquals(200, resource.writeDeleteAccountInternal(account).getStatus());
		commit();

		List<JSONObject> rows = readProctran(null, null);
		assertEquals(2, rows.size());
		DateFormat df = DateFormat.getDateInstance();
		for (JSONObject row : rows)
		{
			assertTrue(row.get("type").equals("ICA") || row.get("type").equals("IDA"),
					row.toString());
			assertEquals("0000000042", row.get("customer"));
			assertEquals("ISA     ", row.get("accountType"));
			assertEquals(df.format(date(2024, 1, 31)), row.get("lastStatement"));
			assertEquals(df.format(date(2024, 2, 29)), row.get("nextStatement"));
		}
		assertEquals("ICA", rows.get(0).get("type"));
		assertEquals("IDA", rows.get(1).get("type"));
	}


	@Test
	void createAndDeleteCustomerRecordsDecode() throws Exception
	{
		ProcessedTransactionCreateCustomerJSON created = new ProcessedTransactionCreateCustomerJSON();
		created.setSortCode(SORTCODE);
		created.setAccountNumber("0");
		created.setCustomerNumber("0000000077");
		created.setCustomerName("Mr Ada Lovelace");
		created.setCustomerDOB(date(1985, 12, 10));

		ProcessedTransactionDeleteCustomerJSON deleted = new ProcessedTransactionDeleteCustomerJSON();
		deleted.setSortCode(SORTCODE);
		deleted.setAccountNumber("0");
		deleted.setCustomerNumber("0000000077");
		deleted.setCustomerName("Mr Ada Lovelace");
		deleted.setCustomerDOB(date(1985, 12, 10));

		ProcessedTransactionResource resource = new ProcessedTransactionResource();
		assertEquals(200, resource.writeCreateCustomerInternal(created).getStatus());
		assertEquals(200, resource.writeDeleteCustomerInternal(deleted).getStatus());
		commit();

		List<JSONObject> rows = readProctran(null, null);
		assertEquals(2, rows.size());
		assertEquals("ICC", rows.get(0).get("type"));
		assertEquals("IDC", rows.get(1).get("type"));
		DateFormat df = DateFormat.getDateInstance();
		for (JSONObject row : rows)
		{
			assertEquals("0000000077", row.get("customer"));
			assertEquals("Mr Ada Lovelac", row.get("customerName"));
			assertEquals(df.format(date(1985, 12, 10)), row.get("dateOfBirth"));
		}
	}


	@Test
	void transferRecordDecodesTargetFromBlankPaddedDescription() throws Exception
	{
		ProcessedTransactionTransferLocalJSON transfer = new ProcessedTransactionTransferLocalJSON();
		transfer.setSortCode(SORTCODE);
		transfer.setAccountNumber("00000001");
		transfer.setTargetAccountNumber("00000002");
		transfer.setAmount(new BigDecimal("25.50"));

		assertEquals(200, new ProcessedTransactionResource()
				.writeTransferLocalInternal(transfer).getStatus());
		commit();

		List<JSONObject> rows = readProctran(null, null);
		assertEquals(1, rows.size());
		JSONObject row = rows.get(0);
		assertEquals("TFR", row.get("type"));
		assertEquals(SORTCODE, row.get("targetSortcode"));
		assertEquals("00000002", row.get("targetAccount"));
		assertEquals(25.50, ((Number) row.get("amount")).doubleValue());

		try (Connection c = connect(); Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(
						"SELECT PROCTRAN_DESC FROM PROCTRAN"))
		{
			rs.next();
			assertEquals(40, rs.getString(1).length(),
					"CHAR(40) must come back blank padded");
		}
	}


	@Test
	void debitAndCreditWriteDecimalScaleAndTypeCodes() throws Exception
	{
		ProcessedTransactionDebitCreditJSON debit = new ProcessedTransactionDebitCreditJSON();
		debit.setSortCode(SORTCODE);
		debit.setAccountNumber("00000001");
		debit.setAmount(new BigDecimal("-10.05"));
		ProcessedTransactionDebitCreditJSON credit = new ProcessedTransactionDebitCreditJSON();
		credit.setSortCode(SORTCODE);
		credit.setAccountNumber("00000001");
		credit.setAmount(new BigDecimal("99.99"));

		ProcessedTransactionResource resource = new ProcessedTransactionResource();
		assertEquals(200, resource.writeInternal(debit).getStatus());
		assertEquals(200, resource.writeInternal(credit).getStatus());
		commit();

		List<JSONObject> rows = readProctran(null, null);
		assertEquals("DEB", rows.get(0).get("type"));
		assertEquals("INTERNET WTHDRW", rows.get(0).get("description"));
		assertEquals(-10.05, ((Number) rows.get(0).get("amount")).doubleValue());
		assertEquals("CRE", rows.get(1).get("type"));
		assertEquals(99.99, ((Number) rows.get(1).get("amount")).doubleValue());
	}


	@Test
	void proctranPaginationUsesLimitOffset() throws Exception
	{
		for (int i = 1; i <= 5; i++)
		{
			sql("INSERT INTO PROCTRAN VALUES ('PRTR','" + SORTCODE + "','0000000" + i
					+ "',DATE '2024-01-0" + i + "','120000','000000000001','CRE','PAGE " + i
					+ "'," + i + ".00)");
		}
		List<JSONObject> page = readProctran(2, 1);
		assertEquals(2, page.size());
		assertEquals("PAGE 2", page.get(0).get("description"));
		assertEquals("PAGE 3", page.get(1).get("description"));
		assertEquals(5, readProctran(null, null).size());
	}


	@Test
	void accountsByBalanceMatchesLegacyRowNumberWindow() throws Exception
	{
		for (int i = 1; i <= 6; i++)
		{
			seedAccount(i, i * 100);
		}
		Account[] accounts = new Account().getAccountsByBalance(
				Integer.valueOf(SORTCODE), new BigDecimal("0"), false, 2, 3);
		commit();
		// Legacy DB2 SQL selected "rn between offset and offset + limit - 1"
		// i.e. rows 2..4 of the ordered result
		assertEquals(3, accounts.length);
		assertEquals("00000002", accounts[0].getAccountNumber());
		assertEquals("00000004", accounts[2].getAccountNumber());
	}


	@Test
	void deleteAccountRollsBackWhenProctranWriteFails() throws Exception
	{
		seedAccount(9, 500);
		sql("CREATE OR REPLACE FUNCTION proctran_fail() RETURNS trigger AS $$ "
				+ "BEGIN RAISE EXCEPTION 'PROCTRAN unavailable'; END $$ LANGUAGE plpgsql");
		sql("CREATE TRIGGER proctran_fail BEFORE INSERT ON PROCTRAN "
				+ "FOR EACH ROW EXECUTE FUNCTION proctran_fail()");

		Response response = new AccountsResource().deleteAccountInternal(9L);
		commit();

		assertNotEquals(200, response.getStatus());
		assertEquals(1, count("SELECT count(*) FROM ACCOUNT WHERE ACCOUNT_NUMBER = '00000009'"),
				"ACCOUNT delete must be rolled back");
		assertEquals(0, count("SELECT count(*) FROM PROCTRAN"));
	}


	@Test
	void deleteAccountCommitsAccountAndProctranTogether() throws Exception
	{
		seedAccount(8, 500);
		Response response = new AccountsResource().deleteAccountInternal(8L);
		commit();

		assertEquals(200, response.getStatus());
		assertEquals(0, count("SELECT count(*) FROM ACCOUNT WHERE ACCOUNT_NUMBER = '00000008'"));
		assertEquals(1, count("SELECT count(*) FROM PROCTRAN WHERE PROCTRAN_TYPE = 'IDA'"));
	}


	private static void seedAccount(int number, int balance) throws SQLException
	{
		sql(String.format("INSERT INTO ACCOUNT VALUES ('ACCT','0000000001','%s','%08d','CURRENT',"
				+ "1.50,DATE '2023-01-01',100,DATE '2024-01-01',DATE '2024-02-01',%d.00,%d.00)",
				SORTCODE, number, balance, balance));
	}


	private static List<JSONObject> readProctran(Integer limit, Integer offset)
			throws Exception
	{
		Response response = new ProcessedTransactionResource()
				.getProcessedTransactionInternal(limit, offset);
		commit();
		assertEquals(200, response.getStatus(), String.valueOf(response.getEntity()));
		JSONObject body = JSONObject.parse((String) response.getEntity());
		JSONArray array = (JSONArray) body.get("processedTransactions");
		List<JSONObject> rows = new ArrayList<>();
		for (Object o : array)
		{
			rows.add((JSONObject) o);
		}
		return rows;
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


	private static Connection connect() throws SQLException
	{
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(),
				POSTGRES.getUsername(), POSTGRES.getPassword());
	}


	private static void sql(String statement) throws SQLException
	{
		try (Connection c = connect(); Statement s = c.createStatement())
		{
			s.execute(statement);
		}
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
}
