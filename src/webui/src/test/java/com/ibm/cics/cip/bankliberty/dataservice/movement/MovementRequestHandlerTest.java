/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import com.ibm.cics.cip.bankliberty.dataservice.movement.MovementJson.CustomerRequest;
import com.ibm.cics.cip.bankliberty.dataservice.movement.MovementJson.DebitCreditRequest;
import com.ibm.cics.cip.bankliberty.dataservice.movement.MovementJson.MovementResponse;
import com.ibm.cics.cip.bankliberty.dataservice.movement.MovementJson.ProctranListResponse;
import com.ibm.cics.cip.bankliberty.dataservice.movement.MovementJson.ProctranWriteRequest;
import com.ibm.cics.cip.bankliberty.dataservice.movement.MovementJson.TransferRequest;

/** Resource-level request validation and JSON mapping (no JCICS). */
class MovementRequestHandlerTest extends PostgresTestSupport
{

	static DebitCreditRequest debitCredit(String account, String amount,
			Integer facilityType)
	{
		DebitCreditRequest r = new DebitCreditRequest();
		r.sortCode = SORT_CODE;
		r.accountNumber = account;
		r.amount = amount;
		r.facilityType = facilityType;
		r.applId = "APPL";
		r.userId = "USER";
		r.taskNumber = 12L;
		return r;
	}

	static TransferRequest transfer(String from, String to, String amount)
	{
		TransferRequest r = new TransferRequest();
		r.sortCode = SORT_CODE;
		r.fromAccountNumber = from;
		r.toAccountNumber = to;
		r.amount = amount;
		r.taskNumber = 1L;
		return r;
	}

	static CustomerRequest customer(String dob)
	{
		CustomerRequest r = new CustomerRequest();
		r.sortCode = SORT_CODE;
		r.customerNumber = "7";
		r.customerName = "Ann";
		r.dateOfBirth = dob;
		r.taskNumber = 1L;
		return r;
	}

	static ProctranWriteRequest proctran(String type, String desc)
	{
		ProctranWriteRequest r = new ProctranWriteRequest();
		r.sortCode = SORT_CODE;
		r.accountNumber = "1";
		r.type = type;
		r.description = desc;
		r.amount = "0";
		r.taskNumber = 1L;
		return r;
	}

	private static MovementResponse body(DataServiceResult r)
	{
		return (MovementResponse) r.getEntity();
	}

	@Test
	void debitCreditEndpointReturnsCommareaFieldsAndTheProctranRow()
			throws SQLException
	{
		insertAccount("00000001", "CURRENT", "100.00", "100.00");
		DataServiceResult r = MovementRequestHandler.debitCredit(conn,
				FIXED_CLOCK, debitCredit("1", "-0.10", null));
		assertEquals(200, r.getStatus());
		MovementResponse b = body(r);
		assertEquals("Y", b.success);
		assertEquals("0", b.failCode);
		assertNull(b.abendCode);
		assertEquals("99.90", b.availableBalance);
		assertEquals("99.90", b.actualBalance);
		assertEquals("DEB", b.proctran.type);
		assertEquals("000000000012", b.proctran.reference);
		assertEquals("2026-10-29", b.proctran.date);
		assertEquals("140509", b.proctran.time);
		assertEquals("-0.10", b.proctran.amount);
	}

	@Test
	void debitCreditInsufficientFundsMapsToHttp400FailCode3()
			throws SQLException
	{
		insertAccount("00000001", "CURRENT", "1.00", "1.00");
		DataServiceResult r = MovementRequestHandler.debitCredit(conn,
				FIXED_CLOCK, debitCredit("1", "-2", 496));
		assertEquals(400, r.getStatus());
		assertEquals("N", body(r).success);
		assertEquals("3", body(r).failCode);
	}

	@Test
	void unknownAccountMapsToHttp404FailCode1()
	{
		DataServiceResult r = MovementRequestHandler.debitCredit(conn,
				FIXED_CLOCK, debitCredit("5", "1", 0));
		assertEquals(404, r.getStatus());
		assertEquals("1", body(r).failCode);
	}

	@Test
	void missingOrMalformedFieldsAreRejectedWith400FailCodeV()
	{
		assertInvalid(
				MovementRequestHandler.debitCredit(conn, FIXED_CLOCK, null));
		assertInvalid(MovementRequestHandler.debitCredit(conn, FIXED_CLOCK,
				debitCredit(null, "1", 0)));
		assertInvalid(MovementRequestHandler.debitCredit(conn, FIXED_CLOCK,
				debitCredit("1", "abc", 0)));
		assertInvalid(MovementRequestHandler.debitCredit(conn, FIXED_CLOCK,
				debitCredit("1", "0.001", 0)));
		assertInvalid(MovementRequestHandler.debitCredit(conn, FIXED_CLOCK,
				debitCredit("123456789", "1", 0)));
		DebitCreditRequest noTask = debitCredit("1", "1", 0);
		noTask.taskNumber = null;
		assertInvalid(
				MovementRequestHandler.debitCredit(conn, FIXED_CLOCK, noTask));
		assertInvalid(MovementRequestHandler.transfer(conn, FIXED_CLOCK,
				transfer("1", "ABC", "1")));
		assertInvalid(MovementRequestHandler.createCustomer(conn, FIXED_CLOCK,
				customer("1/1/2000")));
	}

	@Test
	void transferEndpointReturnsFromAndToBalancesAndAbendCodeForSameAccount()
			throws SQLException
	{
		insertAccount("00000001", "CURRENT", "10.00", "10.00");
		insertAccount("00000002", "CURRENT", "0.00", "0.00");
		DataServiceResult ok = MovementRequestHandler.transfer(conn,
				FIXED_CLOCK, transfer("1", "2", "4.00"));
		assertEquals(200, ok.getStatus());
		assertEquals("6.00", body(ok).fromAvailableBalance);
		assertEquals("6.00", body(ok).fromActualBalance);
		assertEquals("4.00", body(ok).toAvailableBalance);
		assertEquals("4.00", body(ok).toActualBalance);
		assertEquals("TFR", body(ok).proctran.type);

		DataServiceResult same = MovementRequestHandler.transfer(conn,
				FIXED_CLOCK, transfer("1", "1", "4.00"));
		assertEquals(400, same.getStatus());
		assertEquals("SAME", body(same).abendCode);
	}

	@Test
	void genericWriterRejectsTypeCodesOutsideTheProctranAllowListAndLongDescriptions()
			throws SQLException
	{
		assertInvalid(MovementRequestHandler.writeProctran(conn, FIXED_CLOCK,
				proctran("ZZZ", "x")));
		assertInvalid(MovementRequestHandler.writeProctran(conn, FIXED_CLOCK,
				proctran("CRE", "01234567890123456789012345678901234567890")));
		assertEquals(0, proctranCount());

		DataServiceResult ok = MovementRequestHandler.writeProctran(conn,
				FIXED_CLOCK, proctran("OCS", "SODD"));
		assertEquals(200, ok.getStatus());
		assertEquals("OCS", body(ok).proctran.type);
		assertEquals(1, proctranCount());
	}

	@Test
	void customerEndpointsWriteOccAndOdc() throws SQLException
	{
		assertEquals(200, MovementRequestHandler
				.createCustomer(conn, FIXED_CLOCK, customer("01022003"))
				.getStatus());
		DataServiceResult d = MovementRequestHandler.deleteCustomer(conn,
				FIXED_CLOCK, customer("01022003"));
		assertEquals("ODC", body(d).proctran.type);
		assertEquals("00000000", body(d).proctran.accountNumber);
		assertEquals(2, proctranCount());
	}

	@Test
	void readerEndpointDefaultsAndValidatesLimitAndOffset()
	{
		DataServiceResult empty = MovementRequestHandler.listProctran(conn,
				SORT_CODE, null, null);
		assertEquals(200, empty.getStatus());
		assertEquals(0,
				((ProctranListResponse) empty.getEntity()).records.size());
		assertInvalidList(
				MovementRequestHandler.listProctran(conn, SORT_CODE, 0, 0));
		assertInvalidList(
				MovementRequestHandler.listProctran(conn, SORT_CODE, 1, -1));
		assertInvalidList(
				MovementRequestHandler.listProctran(conn, "ABC", 1, 0));
	}

	@Test
	void genericWriterRejectsStructuredTypesWhoseDescriptionTheHistoryDecoderCannotParse()
			throws SQLException
	{
		assertInvalid(MovementRequestHandler.writeProctran(conn, FIXED_CLOCK,
				proctran("OCC", "hello")));
		assertInvalid(MovementRequestHandler.writeProctran(conn, FIXED_CLOCK,
				proctran("IDA", "hello")));
		assertInvalid(MovementRequestHandler.writeProctran(conn, FIXED_CLOCK,
				proctran("TFR", "TRANSFER")));
		assertInvalid(MovementRequestHandler.writeProctran(conn, FIXED_CLOCK,
				proctran("ICC", "9876540000000001Ann           31/02/2003")));
		assertEquals(0, proctranCount());

		assertEquals(200, MovementRequestHandler.writeProctran(conn,
				FIXED_CLOCK,
				proctran("ICC", "9876540000000001Ann           28/02/2003"))
				.getStatus());
		assertEquals(200, MovementRequestHandler.writeProctran(conn,
				FIXED_CLOCK,
				proctran("OCA", "0000000042ISA     3101202429022024CREATE"))
				.getStatus());
		assertEquals(200, MovementRequestHandler.writeProctran(conn,
				FIXED_CLOCK,
				proctran("TFR", ProctranFormat.transferDescription("1", "2")))
				.getStatus());
		assertEquals(3, proctranCount());
	}

	private static void assertInvalidList(DataServiceResult r)
	{
		assertEquals(400, r.getStatus());
		ProctranListResponse b = (ProctranListResponse) r.getEntity();
		assertEquals("N", b.success);
		assertEquals("V", b.failCode);
	}

	@Test
	void readerEndpointReturnsBlankPaddedRecords()
	{
		MovementRequestHandler.createCustomer(conn, FIXED_CLOCK,
				customer("01022003"));
		ProctranListResponse r = (ProctranListResponse) MovementRequestHandler
				.listProctran(conn, SORT_CODE, 10, 0).getEntity();
		assertEquals("Y", r.success);
		assertEquals(1, r.numberOfRecords.intValue());
		assertEquals("OCC", r.records.get(0).type);
		assertEquals(40, r.records.get(0).description.length());
		assertEquals("PRTR", r.records.get(0).eyecatcher);
	}

	private static void assertInvalid(DataServiceResult r)
	{
		assertEquals(400, r.getStatus());
		assertEquals("V", body(r).failCode);
	}
}
