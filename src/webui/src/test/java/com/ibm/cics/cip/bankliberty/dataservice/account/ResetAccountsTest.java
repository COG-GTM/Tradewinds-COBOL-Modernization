package com.ibm.cics.cip.bankliberty.dataservice.account;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BANKDATA: POST /data/admin/account/reset")
class ResetAccountsTest extends PostgresTestBase
{

	@BeforeEach
	void seed() throws Exception
	{
		insertAccount(SORTCODE, "00000001", "0000000001", "ISA", "1.00");
		insertAccount(OTHER_SORTCODE, "00000001", "0000000001", "ISA", "1.00");
		insertAccountControls(1, 1);
	}


	private static String acct(String number, String customer)
	{
		return "{\"customerNumber\":\"" + customer + "\",\"accountNumber\":\"" + number
				+ "\",\"accountType\":\"LOAN\",\"interestRate\":17.90,\"overdraftLimit\":0,"
				+ "\"opened\":\"01012000\",\"lastStatementDate\":\"2021-07-01\","
				+ "\"nextStatementDate\":\"01082021\",\"availableBalance\":-50.25,\"actualBalance\":-50.25}";
	}


	@Test
	@DisplayName("Replaces every account for the sortcode, leaves other sortcodes alone and rewrites CONTROL")
	void replacesAccountsAndControl() throws Exception
	{
		ServiceResult r = service().reset(SORTCODE,
				"[" + acct("10", "5") + "," + acct("12", "5") + "," + acct("11", "6") + "]", null, null);
		assertEquals(200, r.getStatus());
		assertEquals(Long.valueOf(3), r.getBody().get("accountsInserted"));
		assertEquals(3, count("SELECT COUNT(*) FROM ACCOUNT WHERE ACCOUNT_SORTCODE = '987654'"));
		assertEquals(0, count("SELECT COUNT(*) FROM ACCOUNT WHERE ACCOUNT_SORTCODE = '987654' AND ACCOUNT_NUMBER = '00000001'"));
		assertEquals(1, count("SELECT COUNT(*) FROM ACCOUNT WHERE ACCOUNT_SORTCODE = '123456'"));
		assertEquals(Long.valueOf(12), controlValue(SORTCODE + "-ACCOUNT-LAST"));
		assertEquals(Long.valueOf(3), controlValue(SORTCODE + "-ACCOUNT-COUNT"));
		assertEquals(2, count("SELECT COUNT(*) FROM CONTROL"));
	}


	@Test
	@DisplayName("Explicit lastAccountNumber / numberOfAccounts override the computed CONTROL values")
	void explicitControlValuesWin() throws Exception
	{
		service().reset(SORTCODE, "[" + acct("10", "5") + "]", "99", "7");
		assertEquals(Long.valueOf(99), controlValue(SORTCODE + "-ACCOUNT-LAST"));
		assertEquals(Long.valueOf(7), controlValue(SORTCODE + "-ACCOUNT-COUNT"));
	}


	@Test
	@DisplayName("Large loads are batch-inserted (2,500 rows across multiple JDBC batches)")
	void largeLoadIsBatched() throws Exception
	{
		StringBuilder sb = new StringBuilder("[");
		for (int i = 1; i <= 2500; i++)
		{
			sb.append(i > 1 ? "," : "").append(acct(Integer.toString(i), Integer.toString(i / 5 + 1)));
		}
		ServiceResult r = service().reset(SORTCODE, sb.append("]").toString(), null, null);
		assertEquals(200, r.getStatus());
		assertEquals(2500, count("SELECT COUNT(*) FROM ACCOUNT WHERE ACCOUNT_SORTCODE = '987654'"));
		assertEquals(Long.valueOf(2500), controlValue(SORTCODE + "-ACCOUNT-LAST"));
	}


	@Test
	@DisplayName("A duplicate account number in the batch returns 409 and the previous data is kept")
	void duplicateRollsBackWholeReset() throws Exception
	{
		ServiceResult r = service().reset(SORTCODE, "[" + acct("10", "5") + "," + acct("10", "6") + "]", null, null);
		assertEquals(409, r.getStatus());
		assertEquals(1, count("SELECT COUNT(*) FROM ACCOUNT WHERE ACCOUNT_SORTCODE = '987654' AND ACCOUNT_NUMBER = '00000001'"));
		assertEquals(Long.valueOf(1), controlValue(SORTCODE + "-ACCOUNT-LAST"));
	}


	@Test
	@DisplayName("A body that is not a JSON array is rejected with 400")
	void nonArrayBodyIsRejected()
	{
		assertEquals(400, service().reset(SORTCODE, "{}", null, null).getStatus());
	}


	@Test
	@DisplayName("An account whose sortcode differs from the reset sortcode is rejected")
	void mismatchedSortcodeIsRejected() throws Exception
	{
		String bad = acct("10", "5").replace("{", "{\"sortcode\":\"123456\",");
		assertEquals(400, service().reset(SORTCODE, "[" + bad + "]", null, null).getStatus());
		assertEquals(1, count("SELECT COUNT(*) FROM ACCOUNT WHERE ACCOUNT_SORTCODE = '987654'"));
	}
}
