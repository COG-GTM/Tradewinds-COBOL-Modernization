package com.ibm.cics.cip.bankliberty.dataservice.account;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


@DisplayName("CREACC: POST /data/account")
class CreateAccountTest extends PostgresTestBase
{

	private static final String BODY = "{\"sortcode\":\"987654\",\"customerNumber\":\"42\","
			+ "\"accountType\":\"ISA\",\"interestRate\":2.10,\"overdraftLimit\":250,"
			+ "\"availableBalance\":\"10.00\",\"actualBalance\":\"10.00\",\"taskRef\":\"123\"}";


	@Test
	@DisplayName("Allocates ACCOUNT-LAST + 1, bumps ACCOUNT-COUNT, inserts the account and an OCA PROCTRAN row")
	void createsAccountAllocatedFromControl() throws Exception
	{
		insertAccountControls(41, 30);

		ServiceResult r = service().create(BODY);

		assertEquals(201, r.getStatus());
		assertEquals("Y", r.getBody().get("success"));
		Map<?, ?> a = account(r);
		assertEquals("00000042", a.get("accountNumber"));
		assertEquals("0000000042", a.get("customerNumber"));
		assertEquals("ISA     ", a.get("accountType"));
		assertEquals("2.10", a.get("interestRate"));
		assertEquals(Long.valueOf(250), a.get("overdraftLimit"));
		assertEquals("29102026", a.get("opened"));
		assertEquals("29102026", a.get("lastStatementDate"));
		assertEquals("28112026", a.get("nextStatementDate"));

		assertEquals(Long.valueOf(42), controlValue(SORTCODE + "-ACCOUNT-LAST"));
		assertEquals(Long.valueOf(31), controlValue(SORTCODE + "-ACCOUNT-COUNT"));

		List<Map<String, Object>> acc = rows("SELECT * FROM ACCOUNT");
		assertEquals(1, acc.size());
		assertEquals(Date.valueOf("2026-10-29"), acc.get(0).get("ACCOUNT_OPENED"));
		assertEquals(Date.valueOf("2026-11-28"), acc.get(0).get("ACCOUNT_NEXT_STATEMENT"));

		List<Map<String, Object>> pt = rows("SELECT * FROM PROCTRAN");
		assertEquals(1, pt.size());
		Map<String, Object> p = pt.get(0);
		assertEquals("PRTR", p.get("PROCTRAN_EYECATCHER"));
		assertEquals(SORTCODE, p.get("PROCTRAN_SORTCODE"));
		assertEquals("00000042", p.get("PROCTRAN_NUMBER"));
		assertEquals(Date.valueOf("2026-10-29"), p.get("PROCTRAN_DATE"));
		assertEquals("140509", p.get("PROCTRAN_TIME"));
		assertEquals("000000000123", p.get("PROCTRAN_REF"));
		assertEquals("OCA", p.get("PROCTRAN_TYPE"));
		assertEquals("0000000042ISA     2910202628112026      ", p.get("PROCTRAN_DESC"));
		assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) p.get("PROCTRAN_AMOUNT")));
	}


	@Test
	@DisplayName("CONTROL names shorter than CHAR(32) are found by equality")
	void shortControlNamesAreFound() throws Exception
	{
		insertAccountControls(0, 0);
		assertEquals(201, service().create(BODY).getStatus());
		assertEquals(Long.valueOf(1), controlValue("987654-ACCOUNT-LAST"));
	}


	@Test
	@DisplayName("A COBOL-allocated account number is used as-is and advances ACCOUNT-LAST only if higher")
	void callerSuppliedAccountNumberIsUsed() throws Exception
	{
		insertAccountControls(100, 5);
		ServiceResult r = service().create(BODY.replace("\"taskRef\"", "\"accountNumber\":\"500\",\"taskRef\""));
		assertEquals(201, r.getStatus());
		assertEquals("00000500", account(r).get("accountNumber"));
		assertEquals(Long.valueOf(500), controlValue(SORTCODE + "-ACCOUNT-LAST"));

		r = service().create(BODY.replace("\"taskRef\"", "\"accountNumber\":\"50\",\"taskRef\""));
		assertEquals("00000050", account(r).get("accountNumber"));
		assertEquals(Long.valueOf(500), controlValue(SORTCODE + "-ACCOUNT-LAST"));
		assertEquals(Long.valueOf(7), controlValue(SORTCODE + "-ACCOUNT-COUNT"));
	}


	@Test
	@DisplayName("Account type must start with ISA, MORTGAGE, SAVING, CURRENT or LOAN, else fail code A and nothing written")
	void invalidAccountTypeIsFailCodeA() throws Exception
	{
		insertAccountControls(41, 30);
		ServiceResult r = service().create(BODY.replace("\"ISA\"", "\"CHEQUE\""));
		assertEquals(400, r.getStatus());
		assertEquals("A", r.getBody().get("failCode"));
		assertEquals(0, count("SELECT COUNT(*) FROM ACCOUNT"));
		assertEquals(Long.valueOf(41), controlValue(SORTCODE + "-ACCOUNT-LAST"));
	}


	@Test
	@DisplayName("Account type check is a prefix match, exactly like ACCOUNT-TYPE-CHECK")
	void accountTypeCheckIsPrefixMatch() throws Exception
	{
		insertAccountControls(0, 0);
		assertEquals(201, service().create(BODY.replace("\"ISA\"", "\"ISAPLUS\"")).getStatus());
		assertEquals(201, service().create(BODY.replace("\"ISA\"", "\"SAVINGS\"")).getStatus());
	}


	@Test
	@DisplayName("A missing CONTROL row returns 500 with the legacy HNCS abend code")
	void missingControlRowIsHncs()
	{
		ServiceResult r = service().create(BODY);
		assertEquals(500, r.getStatus());
		assertEquals("HNCS", r.getBody().get("abendCode"));
	}


	@Test
	@DisplayName("A duplicate account number returns 409 fail code 7 and rolls back the CONTROL increments")
	void duplicateAccountIsConflictAndRollsBack() throws Exception
	{
		insertAccountControls(41, 30);
		insertAccount(SORTCODE, "00000042", "0000000001", "ISA", "1.00");
		ServiceResult r = service().create(BODY);
		assertEquals(409, r.getStatus());
		assertEquals("7", r.getBody().get("failCode"));
		assertEquals(Long.valueOf(41), controlValue(SORTCODE + "-ACCOUNT-LAST"));
		assertEquals(Long.valueOf(30), controlValue(SORTCODE + "-ACCOUNT-COUNT"));
	}


	@Test
	@DisplayName("If the PROCTRAN insert fails the account and CONTROL changes are rolled back (abend HWPT)")
	void proctranFailureRollsBackEverything() throws Exception
	{
		insertAccountControls(41, 30);
		failNextProctranInserts();
		ServiceResult r = service().create(BODY);
		assertEquals(500, r.getStatus());
		assertEquals("HWPT", r.getBody().get("abendCode"));
		assertEquals(0, count("SELECT COUNT(*) FROM ACCOUNT"));
		assertEquals(0, count("SELECT COUNT(*) FROM PROCTRAN"));
		assertEquals(Long.valueOf(41), controlValue(SORTCODE + "-ACCOUNT-LAST"));
		assertEquals(Long.valueOf(30), controlValue(SORTCODE + "-ACCOUNT-COUNT"));
		assertEquals(true, conn.getAutoCommit());
	}


	@Test
	@DisplayName("Without a taskRef the current task number is used, zero-padded to 12")
	void defaultTaskRefIsUsed() throws Exception
	{
		insertAccountControls(0, 0);
		service().create(BODY.replace(",\"taskRef\":\"123\"", ""));
		assertEquals("000000004711", rows("SELECT PROCTRAN_REF FROM PROCTRAN").get(0).get("PROCTRAN_REF"));
	}


	@Test
	@DisplayName("A request without a customer number is rejected with 400")
	void missingCustomerNumberIsRejected() throws Exception
	{
		insertAccountControls(0, 0);
		ServiceResult r = service().create("{\"sortcode\":\"987654\",\"accountType\":\"ISA\"}");
		assertEquals(400, r.getStatus());
		assertEquals("V", r.getBody().get("failCode"));
	}


	@Test
	@DisplayName("A body that is not JSON is rejected with 400")
	void malformedBodyIsRejected()
	{
		assertEquals(400, service().create("not json").getStatus());
	}
}
