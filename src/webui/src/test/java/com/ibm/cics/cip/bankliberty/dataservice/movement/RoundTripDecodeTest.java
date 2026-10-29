/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Calendar;

import org.junit.jupiter.api.Test;

import com.ibm.cics.cip.bankliberty.datainterfaces.PROCTRAN;
import com.ibm.cics.cip.bankliberty.web.db2.ProcessedTransaction;

/**
 * Rows written by this data service, read back from Postgres, must decode
 * through the existing fixed-offset parsing used by
 * ProcessedTransactionResource (which delegates to
 * web/db2/ProcessedTransaction.getProcessedTransactions) and through the
 * PROCTRAN.cpy-generated datainterfaces/PROCTRAN.java layout.
 */
class RoundTripDecodeTest extends PostgresTestSupport
{

	/** Invoke the real private decoder in web/db2/ProcessedTransaction. */
	private static ProcessedTransaction decode(String decoderMethod,
			ProctranRecord row) throws Exception
	{
		ProcessedTransaction pt = new ProcessedTransaction();
		pt.setType(row.getType());
		pt.setDescription(row.getDescription());
		pt.setAccountNumber(row.getAccountNumber());
		pt.setSortcode(row.getSortCode());
		Method m = ProcessedTransaction.class.getDeclaredMethod(decoderMethod,
				ProcessedTransaction.class);
		m.setAccessible(true);
		return (ProcessedTransaction) m.invoke(pt, pt);
	}

	private ProctranRecord onlyRow() throws SQLException
	{
		return new ProctranDao(conn, null).list(SORT_CODE, 1, 0).get(0);
	}

	@Test
	void transferRowDecodesTargetSortcodeAndAccountThroughProcessedTransactionOffsets26And32()
			throws Exception
	{
		insertAccount("00000001", "CURRENT", "100.00", "100.00");
		insertAccount("00012345", "SAVING", "0.00", "0.00");
		assertTrue(new MovementDao(conn, FIXED_CLOCK, 0)
				.transfer(SORT_CODE, "1", "12345", new BigDecimal("10.00"), 1)
				.isSuccess());

		ProcessedTransaction pt = decode("processTransferRecord", onlyRow());
		assertTrue(pt.isTransfer());
		// ProcessedTransaction.java:422-424 substring(26,32) / (32,40)
		assertEquals(SORT_CODE, pt.getTargetSortcode());
		assertEquals("00012345", pt.getTargetAccountNumber());
	}

	@Test
	void transferRowDecodesThroughProctranCopybookXfrRedefinition()
			throws Exception
	{
		insertAccount("00000001", "CURRENT", "100.00", "100.00");
		insertAccount("00000002", "SAVING", "0.00", "0.00");
		new MovementDao(conn, FIXED_CLOCK, 0).transfer(SORT_CODE, "1", "2",
				new BigDecimal("1.00"), 1);

		// PROCTRAN.java:217-232 (PROC-TRAN-DESC-XFR: X(26), 9(6), 9(8))
		PROCTRAN layout = new PROCTRAN();
		layout.setProcTranDesc(onlyRow().getDescription());
		assertEquals("TRANSFER", layout.getProcTranDescXfrHeader().trim());
		assertEquals(987654, layout.getProcTranDescXfrSortcode());
		assertEquals(2, layout.getProcTranDescXfrAccount());
	}

	@Test
	void createCustomerRowDecodesCustomerNameAndDobThroughProcessedTransactionOffsets()
			throws Exception
	{
		new ProctranDao(conn, FIXED_CLOCK).writeCreateCustomer(SORT_CODE, "42",
				"Dr Jane Doe", "07081969", 1);
		ProctranRecord row = onlyRow();
		ProcessedTransaction pt = decode("processCreateDeleteCustomerRecord",
				row);
		// ProcessedTransaction.java:338-347 substring(6,16) (16,30) (30,32)
		// (33,35) (36,40)
		assertEquals("0000000042", pt.getCustomer());
		assertEquals("Dr Jane Doe   ", pt.getCustomerName());
		Calendar dob = Calendar.getInstance();
		dob.setTime(pt.getDateOfBirth());
		assertEquals(7, dob.get(Calendar.DAY_OF_MONTH));
		assertEquals(Calendar.AUGUST, dob.get(Calendar.MONTH));
		assertEquals(1969, dob.get(Calendar.YEAR));
	}

	@Test
	void deleteCustomerRowDecodesThroughTheSameOffsetsAsCreate()
			throws Exception
	{
		new ProctranDao(conn, FIXED_CLOCK).writeDeleteCustomer(SORT_CODE,
				"9999999999", "A Very Long Customer Name", "29021996", 1);
		ProcessedTransaction pt = decode("processCreateDeleteCustomerRecord",
				onlyRow());
		assertEquals("9999999999", pt.getCustomer());
		assertEquals("A Very Long Cu", pt.getCustomerName());
		Calendar dob = Calendar.getInstance();
		dob.setTime(pt.getDateOfBirth());
		assertEquals(29, dob.get(Calendar.DAY_OF_MONTH));
		assertEquals(Calendar.FEBRUARY, dob.get(Calendar.MONTH));
		assertEquals(1996, dob.get(Calendar.YEAR));
	}

	@Test
	void customerRowDecodesSortcodeCustomerAndNameThroughProctranCopybookCrecusRedefinition()
			throws Exception
	{
		new ProctranDao(conn, FIXED_CLOCK).writeCreateCustomer(SORT_CODE, "42",
				"Dr Jane Doe", "07081969", 1);
		// PROCTRAN.java:390-398 (PROC-TRAN-DESC-CRECUS: 9(6), 9(10), X(14))
		PROCTRAN layout = new PROCTRAN();
		layout.setProcTranDesc(onlyRow().getDescription());
		assertEquals(987654, layout.getProcDescCrecusSortcode());
		assertEquals(42L, layout.getProcDescCrecusCustomer());
		assertEquals("Dr Jane Doe", layout.getProcDescCrecusName().trim());
	}

	@Test
	void timeAndAmountDecodeLikeGetProcessedTransactions() throws Exception
	{
		insertAccount("00000001", "CURRENT", "100.00", "100.00");
		new MovementDao(conn, clockAt("2026-10-29T08:07:06Z")).debitCredit(
				SORT_CODE, "1", new BigDecimal("-12.34"), 0, null, null, 1);
		ProctranRecord row = onlyRow();
		// ProcessedTransaction.java:298-300
		String t = row.getTime();
		assertEquals(6, t.length());
		assertEquals(8, Integer.parseInt(t.substring(0, 2)));
		assertEquals(7, Integer.parseInt(t.substring(2, 4)));
		assertEquals(6, Integer.parseInt(t.substring(4)));
		assertEquals(-12.34, row.getAmount().doubleValue());
		assertEquals("COUNTER WTHDRW", row.getDescription().trim());
	}
}
