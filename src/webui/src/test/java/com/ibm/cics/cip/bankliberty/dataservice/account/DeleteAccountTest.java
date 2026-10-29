package com.ibm.cics.cip.bankliberty.dataservice.account;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


@DisplayName("DELACC: DELETE /data/account/{sortcode}/{number}")
class DeleteAccountTest extends PostgresTestBase
{

	@BeforeEach
	void seed() throws Exception
	{
		insertAccount(SORTCODE, "00000001", "0000000042", "MORTGAGE", "-1234.56");
	}


	@Test
	@DisplayName("Deletes the account, writes an ODA PROCTRAN row for the actual balance and returns the deleted record")
	void deletesAndWritesProctran() throws Exception
	{
		ServiceResult r = service().delete(SORTCODE, "1", "98765");
		assertEquals(200, r.getStatus());
		Map<?, ?> a = account(r);
		assertEquals("00000001", a.get("accountNumber"));
		assertEquals("MORTGAGE", a.get("accountType"));
		assertEquals("-1234.56", a.get("actualBalance"));

		assertEquals(0, count("SELECT COUNT(*) FROM ACCOUNT"));
		Map<String, Object> p = rows("SELECT * FROM PROCTRAN").get(0);
		assertEquals("ODA", p.get("PROCTRAN_TYPE"));
		assertEquals("00000001", p.get("PROCTRAN_NUMBER"));
		assertEquals("000000098765", p.get("PROCTRAN_REF"));
		assertEquals("140509", p.get("PROCTRAN_TIME"));
		assertEquals("0000000042MORTGAGE0107202101082021DELETE", p.get("PROCTRAN_DESC"));
		assertEquals(0, new BigDecimal("-1234.56").compareTo((BigDecimal) p.get("PROCTRAN_AMOUNT")));
	}


	@Test
	@DisplayName("A missing account returns 404 fail code 1 and writes no PROCTRAN row")
	void missingAccountIsNotFound() throws Exception
	{
		ServiceResult r = service().delete(SORTCODE, "2", "1");
		assertEquals(404, r.getStatus());
		assertEquals("1", r.getBody().get("failCode"));
		assertEquals(0, count("SELECT COUNT(*) FROM PROCTRAN"));
		assertEquals(1, count("SELECT COUNT(*) FROM ACCOUNT"));
	}


	@Test
	@DisplayName("If the PROCTRAN insert fails the delete is rolled back (abend HWPT)")
	void proctranFailureRollsBackDelete() throws Exception
	{
		failNextProctranInserts();
		ServiceResult r = service().delete(SORTCODE, "1", "1");
		assertEquals(500, r.getStatus());
		assertEquals("HWPT", r.getBody().get("abendCode"));
		assertEquals(1, count("SELECT COUNT(*) FROM ACCOUNT"));
	}


	@Test
	@DisplayName("A non-numeric taskRef is rejected with 400")
	void invalidTaskRefIsRejected() throws Exception
	{
		assertEquals(400, service().delete(SORTCODE, "1", "abc").getStatus());
		assertEquals(1, count("SELECT COUNT(*) FROM ACCOUNT"));
	}
}
