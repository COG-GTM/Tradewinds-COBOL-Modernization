package com.ibm.cics.cip.bankliberty.dataservice.account;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


@DisplayName("UPDACC: PUT /data/account/{sortcode}/{number}")
class UpdateAccountTest extends PostgresTestBase
{

	@BeforeEach
	void seed() throws Exception
	{
		insertAccount(SORTCODE, "00000001", "0000000042", "ISA", "100.00");
	}


	@Test
	@DisplayName("Only account type, interest rate and overdraft limit change; the updated row is returned")
	void updatesOnlyTheThreeEditableFields() throws Exception
	{
		ServiceResult r = service().update(SORTCODE, "1",
				"{\"accountType\":\"LOAN\",\"interestRate\":3.5,\"overdraftLimit\":999}");
		assertEquals(200, r.getStatus());
		Map<?, ?> a = account(r);
		assertEquals("LOAN    ", a.get("accountType"));
		assertEquals("3.50", a.get("interestRate"));
		assertEquals(Long.valueOf(999), a.get("overdraftLimit"));
		assertEquals("100.00", a.get("actualBalance"));
		assertEquals("03022001", a.get("opened"));

		Map<String, Object> row = rows("SELECT * FROM ACCOUNT").get(0);
		assertEquals("LOAN    ", row.get("ACCOUNT_TYPE"));
		assertEquals(0, new BigDecimal("3.50").compareTo((BigDecimal) row.get("ACCOUNT_INTEREST_RATE")));
		assertEquals(999, row.get("ACCOUNT_OVERDRAFT_LIMIT"));
		assertEquals(0, new BigDecimal("90.00").compareTo((BigDecimal) row.get("ACCOUNT_AVAILABLE_BALANCE")));
	}


	@Test
	@DisplayName("A missing account returns 404 even when the new type is blank (read happens before validation)")
	void missingAccountIsNotFoundBeforeTypeValidation()
	{
		ServiceResult r = service().update(SORTCODE, "2",
				"{\"accountType\":\"\",\"interestRate\":1,\"overdraftLimit\":1}");
		assertEquals(404, r.getStatus());
		assertEquals("N", r.getBody().get("success"));
	}


	@Test
	@DisplayName("A blank account type is rejected and the row is unchanged")
	void blankTypeIsRejected() throws Exception
	{
		ServiceResult r = service().update(SORTCODE, "1",
				"{\"accountType\":\"   \",\"interestRate\":1,\"overdraftLimit\":1}");
		assertEquals(400, r.getStatus());
		assertEquals("ISA     ", rows("SELECT ACCOUNT_TYPE FROM ACCOUNT").get(0).get("ACCOUNT_TYPE"));
	}


	@Test
	@DisplayName("An account type whose first character is a space is rejected")
	void leadingSpaceTypeIsRejected()
	{
		ServiceResult r = service().update(SORTCODE, "1",
				"{\"accountType\":\" LOAN\",\"interestRate\":1,\"overdraftLimit\":1}");
		assertEquals(400, r.getStatus());
		assertEquals("A", r.getBody().get("failCode"));
	}


	@Test
	@DisplayName("Interest rate is required")
	void missingInterestRateIsRejected()
	{
		ServiceResult r = service().update(SORTCODE, "1",
				"{\"accountType\":\"LOAN\",\"overdraftLimit\":1}");
		assertEquals(400, r.getStatus());
		assertEquals("V", r.getBody().get("failCode"));
	}


	@Test
	@DisplayName("A database failure on update returns 500 and leaves the row unchanged")
	void updateFailureIsServerError() throws Exception
	{
		execute("ALTER TABLE ACCOUNT ADD CONSTRAINT no_big_overdraft CHECK (ACCOUNT_OVERDRAFT_LIMIT < 500)");
		ServiceResult r = service().update(SORTCODE, "1",
				"{\"accountType\":\"LOAN\",\"interestRate\":1,\"overdraftLimit\":600}");
		assertEquals(500, r.getStatus());
		assertEquals("3", r.getBody().get("failCode"));
		assertEquals("ISA     ", rows("SELECT ACCOUNT_TYPE FROM ACCOUNT").get(0).get("ACCOUNT_TYPE"));
	}
}
