/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** XFRFUN UPDATE-ACCOUNT-DB2 / -FROM / -TO + WRITE-TO-PROCTRAN-DB2. */
class TransferDaoTest extends PostgresTestSupport
{

	private MovementResult transfer(String from, String to, String amount)
	{
		return new MovementDao(conn, FIXED_CLOCK, 0).transfer(SORT_CODE, from,
				to, new BigDecimal(amount), 777);
	}

	private void assertBalance(String account, String expected)
			throws SQLException
	{
		BigDecimal[] b = balances(account);
		assertEquals(new BigDecimal(expected), b[0], "available " + account);
		assertEquals(new BigDecimal(expected), b[1], "actual " + account);
	}

	@Test
	void transferMovesAmountFromLowerToHigherAccountAndWritesTfrRowForFromAccount()
			throws SQLException
	{
		insertAccount("00000001", "CURRENT", "100.00", "100.00");
		insertAccount("00000002", "SAVING", "5.00", "5.00");
		MovementResult r = transfer("00000001", "00000002", "30.25");

		assertTrue(r.isSuccess());
		assertEquals("0", r.getFailCode());
		assertEquals(new BigDecimal("69.75"), r.getAvailableBalance());
		assertEquals(new BigDecimal("69.75"), r.getActualBalance());
		assertEquals(new BigDecimal("35.25"), r.getToAvailableBalance());
		assertEquals(new BigDecimal("35.25"), r.getToActualBalance());
		assertBalance("00000001", "69.75");
		assertBalance("00000002", "35.25");

		List<ProctranRecord> rows = new ProctranDao(conn, FIXED_CLOCK)
				.list(SORT_CODE, 10, 0);
		assertEquals(1, rows.size());
		ProctranRecord p = rows.get(0);
		assertEquals("TFR", p.getType());
		assertEquals("00000001", p.getAccountNumber());
		assertEquals(SORT_CODE, p.getSortCode());
		assertEquals("000000000777", p.getReference());
		assertEquals("TRANSFER                  98765400000002",
				p.getDescription());
		assertEquals(new BigDecimal("30.25"), p.getAmount());
	}

	@Test
	void transferFromHigherToLowerAccountUpdatesToAccountFirstWithSameResult()
			throws SQLException
	{
		insertAccount("00000001", "CURRENT", "0.00", "0.00");
		insertAccount("00000009", "SAVING", "50.00", "50.00");
		assertTrue(transfer("9", "1", "20.00").isSuccess());
		assertBalance("00000009", "30.00");
		assertBalance("00000001", "20.00");
	}

	@Test
	void transferMayOverdrawFromAccountBecauseXfrfunHasNoFundsCheck()
			throws SQLException
	{
		insertAccount("00000001", "CURRENT", "10.00", "10.00");
		insertAccount("00000002", "SAVING", "0.00", "0.00");
		assertTrue(transfer("00000001", "00000002", "100.00").isSuccess());
		assertBalance("00000001", "-90.00");
	}

	@Test
	void zeroOrNegativeAmountFailsWithCode4BeforeAnyAccountIsRead()
			throws SQLException
	{
		MovementResult zero = transfer("00000001", "00000002", "0.00");
		MovementResult negative = transfer("00000001", "00000002", "-1.00");
		assertEquals("4", zero.getFailCode());
		assertEquals("4", negative.getFailCode());
		assertEquals(400, zero.getHttpStatus());
		assertFalse(zero.isSuccess());
	}

	@Test
	void nonPositiveAmountIsCheckedBeforeSameAccountLikeXfrfunA010()
	{
		assertEquals("4", transfer("00000001", "00000001", "0").getFailCode());
	}

	@Test
	void transferToTheSameAccountIsRejectedWithAbendSame() throws SQLException
	{
		insertAccount("00000001", "CURRENT", "10.00", "10.00");
		MovementResult r = transfer("1", "00000001", "5.00");
		assertFalse(r.isSuccess());
		assertEquals("SAME", r.getAbendCode());
		assertEquals(400, r.getHttpStatus());
		assertBalance("00000001", "10.00");
		assertEquals(0, proctranCount());
	}

	@Test
	void missingFromAccountFailsWithCode1() throws SQLException
	{
		insertAccount("00000002", "SAVING", "0.00", "0.00");
		MovementResult r = transfer("00000001", "00000002", "5.00");
		assertEquals("1", r.getFailCode());
		assertEquals(404, r.getHttpStatus());
		assertBalance("00000002", "0.00");
		assertEquals(0, proctranCount());
	}

	@Test
	void missingFromAccountRollsBackTheAlreadyUpdatedToAccountWhenToIsLower()
			throws SQLException
	{
		insertAccount("00000001", "SAVING", "0.00", "0.00");
		MovementResult r = transfer("00000005", "00000001", "5.00");
		assertEquals("1", r.getFailCode());
		assertBalance("00000001", "0.00");
		assertEquals(0, proctranCount());
	}

	@Test
	void missingToAccountFailsWithCode2AndRollsBackTheUpdatedFromAccount()
			throws SQLException
	{
		insertAccount("00000001", "CURRENT", "100.00", "100.00");
		MovementResult r = transfer("00000001", "00000002", "5.00");
		assertEquals("2", r.getFailCode());
		assertEquals(404, r.getHttpStatus());
		assertBalance("00000001", "100.00");
		assertEquals(0, proctranCount());
	}

	@Test
	void whenProctranInsertFailsBothAccountUpdatesAreRolledBackWithAbendWpcd()
			throws SQLException
	{
		insertAccount("00000001", "CURRENT", "100.00", "100.00");
		insertAccount("00000002", "SAVING", "5.00", "5.00");
		failProctranInserts();
		MovementResult r = transfer("00000001", "00000002", "30.00");

		assertFalse(r.isSuccess());
		assertEquals("WPCD", r.getAbendCode());
		assertEquals(500, r.getHttpStatus());
		assertBalance("00000001", "100.00");
		assertBalance("00000002", "5.00");
		assertEquals(0, proctranCount());
	}

	@Test
	void whenToAccountUpdateFailsTheFromUpdateIsRolledBackWithAbendRuf3()
			throws SQLException
	{
		insertAccount("00000001", "CURRENT", "100.00", "100.00");
		insertAccount("00000002", "SAVING", "5.00", "5.00");
		failAccountUpdate("00000002");
		MovementResult r = transfer("00000001", "00000002", "30.00");
		assertEquals("RUF3", r.getAbendCode());
		assertEquals(500, r.getHttpStatus());
		exec("DROP TRIGGER account_fail ON ACCOUNT");
		assertBalance("00000001", "100.00");
		assertBalance("00000002", "5.00");
	}

	@Test
	void whenFromAccountUpdateFailsTheToUpdateIsRolledBackWithAbendFrom()
			throws SQLException
	{
		insertAccount("00000001", "SAVING", "5.00", "5.00");
		insertAccount("00000002", "CURRENT", "100.00", "100.00");
		failAccountUpdate("00000002");
		MovementResult r = transfer("00000002", "00000001", "30.00");
		assertEquals("FROM", r.getAbendCode());
		assertEquals("3", r.getFailCode());
		exec("DROP TRIGGER account_fail ON ACCOUNT");
		assertBalance("00000001", "5.00");
		assertBalance("00000002", "100.00");
	}

	@Test
	void concurrentOppositeTransfersDoNotDeadlockBecauseLowerAccountIsAlwaysLockedFirst()
			throws Exception
	{
		insertAccount("00000001", "CURRENT", "1000.00", "1000.00");
		insertAccount("00000002", "CURRENT", "1000.00", "1000.00");
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try
		{
			List<Future<Integer>> futures = new ArrayList<>();
			futures.add(pool.submit(worker("00000001", "00000002")));
			futures.add(pool.submit(worker("00000002", "00000001")));
			for (Future<Integer> f : futures)
			{
				assertEquals(25, f.get(60, TimeUnit.SECONDS));
			}
		}
		finally
		{
			pool.shutdownNow();
		}
		assertBalance("00000001", "1000.00");
		assertBalance("00000002", "1000.00");
		assertEquals(50, proctranCount());
	}

	private Callable<Integer> worker(String from, String to)
	{
		return () -> {
			int ok = 0;
			try (Connection c = newConnection())
			{
				MovementDao dao = new MovementDao(c, FIXED_CLOCK, 0);
				for (int i = 0; i < 25; i++)
				{
					if (dao.transfer(SORT_CODE, from, to, new BigDecimal("1.00"),
							i).isSuccess())
					{
						ok++;
					}
				}
			}
			return ok;
		};
	}
}
