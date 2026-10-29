/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ibm.cics.cip.bankliberty.api.json.BankRuntime;
import com.ibm.cics.cip.bankliberty.dataservice.movement.MovementJson.MovementResponse;
import com.ibm.cics.cip.bankliberty.dataservice.movement.MovementJson.ProctranListResponse;

import jakarta.ws.rs.core.Response;

/**
 * Drives the JAX-RS resource classes end to end (openConnection, DAO,
 * BankRuntime.rollback on failure, terminate) in BankRuntime standalone mode.
 */
class ResourceStandaloneTest extends PostgresTestSupport
{

	@BeforeAll
	static void standalone()
	{
		System.setProperty(BankRuntime.JDBC_URL, POSTGRES.getJdbcUrl());
		System.setProperty(BankRuntime.JDBC_USER, POSTGRES.getUsername());
		System.setProperty(BankRuntime.JDBC_PASSWORD, POSTGRES.getPassword());
	}

	@AfterAll
	static void clear()
	{
		System.clearProperty(BankRuntime.JDBC_URL);
		System.clearProperty(BankRuntime.JDBC_USER);
		System.clearProperty(BankRuntime.JDBC_PASSWORD);
	}

	@Test
	void debitCreditResourceCommitsAccountAndProctranTogether()
			throws SQLException
	{
		insertAccount("00000001", "CURRENT", "100.00", "100.00");
		Response r = new MovementResource().debitCredit(
				MovementRequestHandlerTest.debitCredit("1", "-25.00", 0));
		assertEquals(200, r.getStatus());
		assertEquals("75.00",
				((MovementResponse) r.getEntity()).availableBalance);
		assertEquals("75.00", balances("00000001")[0].toPlainString());
		assertEquals(1, proctranCount());
	}

	@Test
	void transferResourceRollsBackBothAccountsWhenProctranFails()
			throws SQLException
	{
		insertAccount("00000001", "CURRENT", "100.00", "100.00");
		insertAccount("00000002", "CURRENT", "0.00", "0.00");
		failProctranInserts();
		Response r = new MovementResource().transfer(
				MovementRequestHandlerTest.transfer("1", "2", "10.00"));
		assertEquals(500, r.getStatus());
		assertEquals("WPCD", ((MovementResponse) r.getEntity()).abendCode);
		assertEquals("100.00", balances("00000001")[0].toPlainString());
		assertEquals("0.00", balances("00000002")[0].toPlainString());
		assertEquals(0, proctranCount());
	}

	@Test
	void proctranResourcesWriteAndReadBack()
	{
		ProctranResource resource = new ProctranResource();
		assertEquals(200, resource
				.createCustomer(MovementRequestHandlerTest.customer("01022003"))
				.getStatus());
		assertEquals(200, new ProctranResource()
				.deleteCustomer(MovementRequestHandlerTest.customer("01022003"))
				.getStatus());
		assertEquals(200, new ProctranResource()
				.write(MovementRequestHandlerTest.proctran("CHA", "CHEQUE"))
				.getStatus());
		Response list = new ProctranResource().list(SORT_CODE, 2, 1);
		assertEquals(200, list.getStatus());
		ProctranListResponse body = (ProctranListResponse) list.getEntity();
		assertEquals(2, body.numberOfRecords.intValue());
		assertEquals(400,
				new ProctranResource().list(SORT_CODE, 0, 0).getStatus());
	}
}
