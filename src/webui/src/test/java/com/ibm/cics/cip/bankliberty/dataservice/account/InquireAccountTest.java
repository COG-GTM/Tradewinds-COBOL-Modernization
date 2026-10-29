package com.ibm.cics.cip.bankliberty.dataservice.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

@DisplayName("INQACC: GET /data/account/{sortcode}/{number} and /{sortcode}/last")
class InquireAccountTest extends PostgresTestBase
{

	@BeforeEach
	void seed() throws Exception
	{
		insertAccount(SORTCODE, "00000002", "0000000001", "ISA", "100.00");
		insertAccount(SORTCODE, "00000010", "0000000001", "CURRENT", "200.50");
		insertAccount(SORTCODE, "00000009", "0000000002", "LOAN", "-5.00");
		insertAccount(OTHER_SORTCODE, "00000050", "0000000003", "SAVING", "1.00");
	}


	@Test
	@DisplayName("An existing account is returned with COMMAREA-formatted fields")
	void existingAccountIsReturned()
	{
		ServiceResult r = service().inquire(SORTCODE, "00000002");
		assertEquals(200, r.getStatus());
		assertEquals("Y", r.getBody().get("success"));
		Map<?, ?> a = account(r);
		assertEquals("ACCT", a.get("eyeCatcher"));
		assertEquals("0000000001", a.get("customerNumber"));
		assertEquals(SORTCODE, a.get("sortcode"));
		assertEquals("00000002", a.get("accountNumber"));
		assertEquals("ISA     ", a.get("accountType"));
		assertEquals("1.75", a.get("interestRate"));
		assertEquals("03022001", a.get("opened"));
		assertEquals(Long.valueOf(100), a.get("overdraftLimit"));
		assertEquals("01072021", a.get("lastStatementDate"));
		assertEquals("01082021", a.get("nextStatementDate"));
		assertEquals("90.00", a.get("availableBalance"));
		assertEquals("100.00", a.get("actualBalance"));
	}


	@Test
	@DisplayName("Keys shorter than the CHAR column width are zero-padded and still found by equality")
	void shortKeysAreZeroPaddedAndFound()
	{
		ServiceResult r = service().inquire(SORTCODE, "2");
		assertEquals(200, r.getStatus());
		assertEquals("00000002", account(r).get("accountNumber"));
	}


	@Test
	@DisplayName("A missing account returns 404 with success N (INQACC-SUCCESS = 'N')")
	void missingAccountIsNotFound()
	{
		ServiceResult r = service().inquire(SORTCODE, "00000003");
		assertEquals(404, r.getStatus());
		assertEquals("N", r.getBody().get("success"));
		assertEquals("1", r.getBody().get("failCode"));
	}


	@Test
	@DisplayName("Account number 99999999 returns the highest account number for the sortcode")
	void sentinelReturnsLastAccount() throws Exception
	{
		ServiceResult r = service().inquire(SORTCODE, "99999999");
		assertEquals(200, r.getStatus());
		assertEquals("00000010", account(r).get("accountNumber"));
		assertEquals("00000010",
				dao().findAccount(SORTCODE, "99999999").get().getAccountNumber());
	}


	@Test
	@DisplayName("/last returns the highest account number for the sortcode, ignoring other sortcodes")
	void lastReturnsHighestAccount()
	{
		ServiceResult r = service().inquireLast(SORTCODE);
		assertEquals(200, r.getStatus());
		assertEquals("00000010", account(r).get("accountNumber"));
	}


	@Test
	@DisplayName("/last for a sortcode with no accounts returns 404 carrying the legacy HNCS abend code")
	void lastWithNoAccountsIsNotFound()
	{
		ServiceResult r = service().inquireLast("111111");
		assertEquals(404, r.getStatus());
		assertEquals("HNCS", r.getBody().get("abendCode"));
	}


	@Test
	@DisplayName("A non-numeric sortcode is rejected with 400")
	void invalidSortcodeIsRejected()
	{
		ServiceResult r = service().inquire("98A654", "1");
		assertEquals(400, r.getStatus());
		assertEquals("V", r.getBody().get("failCode"));
	}


	@Test
	@DisplayName("A database failure returns 500 with the legacy HRAC abend code")
	void databaseFailureIsServerError() throws Exception
	{
		execute("DROP TABLE ACCOUNT");
		ServiceResult r = service().inquire(SORTCODE, "1");
		assertEquals(500, r.getStatus());
		assertEquals("HRAC", r.getBody().get("abendCode"));
		assertTrue(r.getBody().get("message").toString().contains("ACCOUNT"));
	}
}
