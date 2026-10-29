package com.ibm.cics.cip.bankliberty.dataservice.account;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

@DisplayName("INQACCCU: GET /data/account/{sortcode}?customerNumber=")
class InquireAccountsForCustomerTest extends PostgresTestBase
{

	@Test
	@DisplayName("Returns only this customer's accounts for this sortcode, ordered by account number")
	void returnsCustomerAccountsInAccountNumberOrder() throws Exception
	{
		insertAccount(SORTCODE, "00000007", "0000000042", "ISA", "1.00");
		insertAccount(SORTCODE, "00000003", "0000000042", "LOAN", "2.00");
		insertAccount(SORTCODE, "00000005", "0000000043", "LOAN", "3.00");
		insertAccount(OTHER_SORTCODE, "00000001", "0000000042", "LOAN", "4.00");

		ServiceResult r = service().inquireByCustomer(SORTCODE, "42");
		assertEquals(200, r.getStatus());
		assertEquals("Y", r.getBody().get("success"));
		assertEquals("0000000042", r.getBody().get("customerNumber"));
		assertEquals(Long.valueOf(2), r.getBody().get("numberOfAccounts"));
		java.util.List<?> accounts = (java.util.List<?>) r.getBody().get("accounts");
		assertEquals("00000003", ((Map<?, ?>) accounts.get(0)).get("accountNumber"));
		assertEquals("00000007", ((Map<?, ?>) accounts.get(1)).get("accountNumber"));
	}


	@Test
	@DisplayName("Returns at most 20 accounts, the size of the COBOL ACCOUNT-DETAILS array")
	void capsAtTwentyAccounts() throws Exception
	{
		for (int i = 1; i <= 25; i++)
		{
			insertAccount(SORTCODE, String.format("%08d", i), "0000000042",
					"ISA", "1.00");
		}
		ServiceResult r = service().inquireByCustomer(SORTCODE, "0000000042");
		assertEquals(Long.valueOf(20), r.getBody().get("numberOfAccounts"));
		java.util.List<?> accounts = (java.util.List<?>) r.getBody().get("accounts");
		assertEquals(20, accounts.size());
		assertEquals("00000020", ((Map<?, ?>) accounts.get(19)).get("accountNumber"));
	}


	@Test
	@DisplayName("A customer with no accounts is a success with zero accounts")
	void noAccountsIsSuccess()
	{
		ServiceResult r = service().inquireByCustomer(SORTCODE, "77");
		assertEquals(200, r.getStatus());
		assertEquals(Long.valueOf(0), r.getBody().get("numberOfAccounts"));
	}


	@Test
	@DisplayName("Customer number zero is not a customer: 404, fail code 1, customerFound N")
	void customerZeroIsNotFound()
	{
		ServiceResult r = service().inquireByCustomer(SORTCODE, "0");
		assertEquals(404, r.getStatus());
		assertEquals("1", r.getBody().get("failCode"));
		assertEquals("N", r.getBody().get("customerFound"));
	}


	@Test
	@DisplayName("Customer number 9999999999 (the control record) is not a customer")
	void controlCustomerIsNotFound()
	{
		ServiceResult r = service().inquireByCustomer(SORTCODE, "9999999999");
		assertEquals(404, r.getStatus());
		assertEquals("1", r.getBody().get("failCode"));
	}


	@Test
	@DisplayName("A missing customerNumber query parameter is rejected with 400")
	void missingCustomerNumberIsRejected()
	{
		ServiceResult r = service().inquireByCustomer(SORTCODE, null);
		assertEquals(400, r.getStatus());
	}


	@Test
	@DisplayName("A fetch failure returns 500 with fail code 3 and zero accounts")
	void databaseFailureIsFailCodeThree() throws Exception
	{
		execute("DROP TABLE ACCOUNT");
		ServiceResult r = service().inquireByCustomer(SORTCODE, "42");
		assertEquals(500, r.getStatus());
		assertEquals("3", r.getBody().get("failCode"));
		assertEquals(Long.valueOf(0), r.getBody().get("numberOfAccounts"));
	}
}
