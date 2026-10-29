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
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;

/** CRECUST WPD010, DELCUS WPCD010, generic writer and reader. */
class ProctranDaoTest extends PostgresTestSupport
{

	@Test
	void createCustomerWritesOccRowWithZeroAccountZeroAmountAndCustomerLayout()
			throws SQLException
	{
		MovementResult r = new ProctranDao(conn, FIXED_CLOCK)
				.writeCreateCustomer(SORT_CODE, "1234", "Mr John Smith-Jones",
						"31121975", 55);
		assertTrue(r.isSuccess());
		assertEquals(200, r.getHttpStatus());

		ProctranRecord p = new ProctranDao(conn, FIXED_CLOCK)
				.list(SORT_CODE, 10, 0).get(0);
		assertEquals("OCC", p.getType());
		assertEquals("00000000", p.getAccountNumber());
		assertEquals(new BigDecimal("0.00"), p.getAmount());
		assertEquals("000000000055", p.getReference());
		assertEquals("9876540000001234Mr John Smith-31/12/1975",
				p.getDescription());
	}

	@Test
	void deleteCustomerWritesOdcRowWithSameLayoutAsCreate() throws SQLException
	{
		new ProctranDao(conn, FIXED_CLOCK).writeDeleteCustomer(SORT_CODE,
				"0000001234", "Ann", "01022003", 1);
		ProctranRecord p = new ProctranDao(conn, FIXED_CLOCK)
				.list(SORT_CODE, 10, 0).get(0);
		assertEquals("ODC", p.getType());
		assertEquals("00000000", p.getAccountNumber());
		assertEquals("9876540000001234Ann           01/02/2003",
				p.getDescription());
	}

	@Test
	void whenCustomerProctranInsertFailsResultIsAbendHwptAndNoRowIsWritten()
			throws SQLException
	{
		failProctranInserts();
		MovementResult r = new ProctranDao(conn, FIXED_CLOCK)
				.writeCreateCustomer(SORT_CODE, "1", "A", "01012000", 1);
		assertFalse(r.isSuccess());
		assertEquals("HWPT", r.getAbendCode());
		assertEquals(500, r.getHttpStatus());
		MovementResult d = new ProctranDao(conn, FIXED_CLOCK)
				.writeDeleteCustomer(SORT_CODE, "1", "A", "01012000", 1);
		assertEquals("HWPT", d.getAbendCode());
		exec("DROP TRIGGER proctran_fail ON PROCTRAN");
		assertEquals(0, proctranCount());
	}

	@Test
	void genericWriterInsertsAValidatedRowAndFailsWithCode2OnDatabaseError()
			throws SQLException
	{
		ProctranDao dao = new ProctranDao(conn, FIXED_CLOCK);
		ProctranRecord rec = dao.newRecord(SORT_CODE, "7", 9, "CHI",
				"CHEQUE PAID IN", new BigDecimal("12.3"));
		assertTrue(dao.write(rec, null).isSuccess());
		ProctranRecord p = dao.list(SORT_CODE, 10, 0).get(0);
		assertEquals("00000007", p.getAccountNumber());
		assertEquals(ProctranFormat.fixed("CHEQUE PAID IN", 40),
				p.getDescription());
		assertEquals(new BigDecimal("12.30"), p.getAmount());

		failProctranInserts();
		MovementResult r = dao.write(rec, null);
		assertEquals("2", r.getFailCode());
		assertEquals(500, r.getHttpStatus());
	}

	@Test
	void readerOrdersByDateThenTimeAndPagesWithLimitOffsetForOneSortcodeOnly()
			throws SQLException
	{
		write("2026-10-29T10:00:03Z", SORT_CODE, "C");
		write("2026-10-28T23:59:59Z", SORT_CODE, "A");
		write("2026-10-29T10:00:01Z", SORT_CODE, "B");
		write("2026-10-29T09:00:00Z", "111111", "X");

		ProctranDao dao = new ProctranDao(conn, null);
		List<ProctranRecord> all = dao.list(SORT_CODE, 10, 0);
		assertEquals(3, all.size());
		assertEquals("A", all.get(0).getDescription().trim());
		assertEquals("B", all.get(1).getDescription().trim());
		assertEquals("C", all.get(2).getDescription().trim());

		List<ProctranRecord> page = dao.list(SORT_CODE, 1, 1);
		assertEquals(1, page.size());
		assertEquals("B", page.get(0).getDescription().trim());
		assertEquals(0, dao.list(SORT_CODE, 10, 3).size());
	}

	@Test
	void readerFindsRowsWhenSortcodeIsGivenShorterThanTheChar6Column()
			throws SQLException
	{
		write("2026-10-29T10:00:00Z", "000123", "Z");
		assertEquals(1, new ProctranDao(conn, null).list("123", 10, 0).size());
	}

	@Test
	void readerReturnsDescriptionBlankPaddedTo40LikeDb2Char()
			throws SQLException
	{
		write("2026-10-29T10:00:00Z", SORT_CODE, "SHORT");
		String desc = new ProctranDao(conn, null).list(SORT_CODE, 10, 0).get(0)
				.getDescription();
		assertEquals(40, desc.length());
	}

	private void write(String instant, String sortCode, String desc)
	{
		ProctranDao dao = new ProctranDao(conn, clockAt(instant));
		assertTrue(dao.write(dao.newRecord(sortCode, "1", 1, "CRE", desc,
				BigDecimal.ONE), null).isSuccess());
	}
}
