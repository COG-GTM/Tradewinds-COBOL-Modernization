/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/** DBCRFUN UPDATE-ACCOUNT-DB2 + WRITE-TO-PROCTRAN-DB2. */
class DebitCreditDaoTest extends PostgresTestSupport
{

	private MovementResult debitCredit(String account, String amount,
			int facilityType)
	{
		return new MovementDao(conn, FIXED_CLOCK).debitCredit(SORT_CODE,
				account, new BigDecimal(amount), facilityType, "CICSAPPL",
				"PAYUSER1", 4321);
	}

	private ProctranRecord onlyProctranRow() throws SQLException
	{
		List<ProctranRecord> rows = new ProctranDao(conn, FIXED_CLOCK)
				.list(SORT_CODE, 10, 0);
		assertEquals(1, rows.size());
		return rows.get(0);
	}

	private void assertUnchanged(String account, String available,
			String actual) throws SQLException
	{
		BigDecimal[] b = balances(account);
		assertEquals(new BigDecimal(available), b[0]);
		assertEquals(new BigDecimal(actual), b[1]);
	}

	@Test
	void tellerCreditAddsAmountToAvailableAndActualBalanceAndWritesCreCounterRecved()
			throws SQLException
	{
		insertAccount("00000001", "CURRENT", "100.00", "90.00");
		MovementResult r = debitCredit("00000001", "25.50", 0);

		assertTrue(r.isSuccess());
		assertEquals("0", r.getFailCode());
		assertEquals(200, r.getHttpStatus());
		assertEquals(new BigDecimal("125.50"), r.getAvailableBalance());
		assertEquals(new BigDecimal("115.50"), r.getActualBalance());
		assertUnchanged("00000001", "125.50", "115.50");

		ProctranRecord p = onlyProctranRow();
		assertEquals("PRTR", p.getEyecatcher());
		assertEquals(SORT_CODE, p.getSortCode());
		assertEquals("00000001", p.getAccountNumber());
		assertEquals(LocalDate.of(2026, 10, 29), p.getDate());
		assertEquals("140509", p.getTime());
		assertEquals("000000004321", p.getReference());
		assertEquals("CRE", p.getType());
		assertEquals(ProctranFormat.fixed("COUNTER RECVED", 40),
				p.getDescription());
		assertEquals(new BigDecimal("25.50"), p.getAmount());
	}

	@Test
	void tellerDebitSubtractsAmountAndWritesDebCounterWthdrwWithNegativeAmount()
			throws SQLException
	{
		insertAccount("00000001", "SAVING", "100.00", "100.00");
		MovementResult r = debitCredit("00000001", "-40.00", 0);

		assertTrue(r.isSuccess());
		assertUnchanged("00000001", "60.00", "60.00");
		ProctranRecord p = onlyProctranRow();
		assertEquals("DEB", p.getType());
		assertEquals("COUNTER WTHDRW", p.getDescription().trim());
		assertEquals(new BigDecimal("-40.00"), p.getAmount());
	}

	@Test
	void zeroAmountIsTreatedAsACreditBecauseDbcrfunOnlyDebitsWhenAmountIsNegative()
			throws SQLException
	{
		insertAccount("00000001", "SAVING", "100.00", "100.00");
		assertTrue(debitCredit("00000001", "0", 0).isSuccess());
		assertEquals("CRE", onlyProctranRow().getType());
	}

	@Test
	void tellerDebitMayTakeBalanceNegativeBecauseDbcrfunOnlyChecksFundsForPaymentInterface()
			throws SQLException
	{
		insertAccount("00000001", "CURRENT", "10.00", "10.00");
		MovementResult r = debitCredit("00000001", "-500.00", 0);
		assertTrue(r.isSuccess());
		assertUnchanged("00000001", "-490.00", "-490.00");
	}

	@Test
	void paymentDebitWithInsufficientAvailableBalanceFailsWithCode3AndChangesNothing()
			throws SQLException
	{
		insertAccount("00000001", "CURRENT", "10.00", "500.00");
		MovementResult r = debitCredit("00000001", "-10.01", 496);

		assertFalse(r.isSuccess());
		assertEquals("3", r.getFailCode());
		assertEquals(400, r.getHttpStatus());
		assertUnchanged("00000001", "10.00", "500.00");
		assertEquals(0, proctranCount());
	}

	@Test
	void paymentDebitIgnoresOverdraftLimitBecauseDbcrfunComparesAvailableBalanceWithZero()
			throws SQLException
	{
		// account has ACCOUNT_OVERDRAFT_LIMIT 100 but DBCRFUN never reads it
		insertAccount("00000001", "CURRENT", "50.00", "50.00");
		assertEquals("3", debitCredit("00000001", "-60.00", 496).getFailCode());
	}

	@Test
	void paymentDebitThatLeavesExactlyZeroIsAllowedAndWritesPdrWithOrigin()
			throws SQLException
	{
		insertAccount("00000001", "CURRENT", "10.00", "10.00");
		MovementResult r = debitCredit("00000001", "-10.00", 496);

		assertTrue(r.isSuccess());
		assertUnchanged("00000001", "0.00", "0.00");
		ProctranRecord p = onlyProctranRow();
		assertEquals("PDR", p.getType());
		assertEquals(ProctranFormat.fixed("CICSAPPLPAYUSE", 40),
				p.getDescription());
	}

	@Test
	void paymentCreditWritesPcrWithOriginAsDescription() throws SQLException
	{
		insertAccount("00000001", "ISA", "10.00", "10.00");
		assertTrue(debitCredit("00000001", "5.00", 496).isSuccess());
		ProctranRecord p = onlyProctranRow();
		assertEquals("PCR", p.getType());
		assertEquals("CICSAPPLPAYUSE", p.getDescription().trim());
	}

	@Test
	void paymentDebitFromMortgageAccountFailsWithCode4EvenWithFunds()
			throws SQLException
	{
		insertAccount("00000001", "MORTGAGE", "1000.00", "1000.00");
		MovementResult r = debitCredit("00000001", "-1.00", 496);
		assertEquals("4", r.getFailCode());
		assertEquals(400, r.getHttpStatus());
		assertUnchanged("00000001", "1000.00", "1000.00");
		assertEquals(0, proctranCount());
	}

	@Test
	void paymentDebitFromLoanAccountReportsCode4BeforeInsufficientFundsCode3()
			throws SQLException
	{
		insertAccount("00000001", "LOAN", "0.00", "0.00");
		assertEquals("4", debitCredit("00000001", "-5.00", 496).getFailCode());
	}

	@Test
	void paymentCreditToLoanOrMortgageAccountAlsoFailsWithCode4()
			throws SQLException
	{
		insertAccount("00000001", "LOAN", "-500.00", "-500.00");
		insertAccount("00000002", "MORTGAGE", "-500.00", "-500.00");
		assertEquals("4", debitCredit("00000001", "50.00", 496).getFailCode());
		assertEquals("4", debitCredit("00000002", "50.00", 496).getFailCode());
		assertUnchanged("00000001", "-500.00", "-500.00");
	}

	@Test
	void tellerTransactionsOnMortgageAndLoanAccountsAreAllowed()
			throws SQLException
	{
		insertAccount("00000001", "MORTGAGE", "-500.00", "-500.00");
		insertAccount("00000002", "LOAN", "-500.00", "-500.00");
		assertTrue(debitCredit("00000001", "50.00", 0).isSuccess());
		assertTrue(debitCredit("00000002", "-50.00", 0).isSuccess());
		assertUnchanged("00000001", "-450.00", "-450.00");
		assertUnchanged("00000002", "-550.00", "-550.00");
	}

	@Test
	void unknownAccountFailsWithCode1AndHttp404() throws SQLException
	{
		MovementResult r = debitCredit("00000099", "1.00", 0);
		assertFalse(r.isSuccess());
		assertEquals("1", r.getFailCode());
		assertEquals(404, r.getHttpStatus());
		assertEquals(0, proctranCount());
	}

	@Test
	void accountNumberShorterThanTheChar8ColumnIsZeroPaddedAndFound()
			throws SQLException
	{
		insertAccount("00000042", "SAVING", "1.00", "1.00");
		MovementResult r = debitCredit("42", "1.00", 0);
		assertTrue(r.isSuccess());
		assertEquals("00000042", onlyProctranRow().getAccountNumber());
		assertUnchanged("00000042", "2.00", "2.00");
	}

	@Test
	void whenProctranInsertFailsTheBalanceUpdateIsRolledBackAndFailCodeIsTruncated02()
			throws SQLException
	{
		insertAccount("00000001", "CURRENT", "100.00", "100.00");
		failProctranInserts();
		MovementResult r = debitCredit("00000001", "-30.00", 0);

		assertFalse(r.isSuccess());
		// MOVE '02' TO COMM-FAIL-CODE (PIC X) leaves '0'
		assertEquals("0", r.getFailCode());
		assertEquals(500, r.getHttpStatus());
		assertNull(r.getAbendCode());
		assertUnchanged("00000001", "100.00", "100.00");
		assertEquals(0, proctranCount());
	}

	@Test
	void whenAccountUpdateFailsResultIsCode2AndNothingIsWritten()
			throws SQLException
	{
		insertAccount("00000001", "CURRENT", "100.00", "100.00");
		failAccountUpdate("00000001");
		MovementResult r = debitCredit("00000001", "-30.00", 0);
		assertEquals("2", r.getFailCode());
		assertEquals(500, r.getHttpStatus());
		exec("DROP TRIGGER account_fail ON ACCOUNT");
		assertUnchanged("00000001", "100.00", "100.00");
		assertEquals(0, proctranCount());
	}

	@Test
	void whenAccountSelectFailsWithSqlErrorResultIsCode2() throws SQLException
	{
		exec("ALTER TABLE ACCOUNT RENAME TO ACCOUNT_HIDDEN");
		try
		{
			MovementResult r = debitCredit("00000001", "1.00", 0);
			assertEquals("2", r.getFailCode());
			assertEquals(500, r.getHttpStatus());
		}
		finally
		{
			exec("ALTER TABLE ACCOUNT_HIDDEN RENAME TO ACCOUNT");
		}
	}

	@Test
	void connectionAutoCommitIsRestoredAfterTheUnitOfWork() throws SQLException
	{
		insertAccount("00000001", "CURRENT", "100.00", "100.00");
		assertTrue(conn.getAutoCommit());
		debitCredit("00000001", "1.00", 0);
		assertTrue(conn.getAutoCommit());
	}
}
