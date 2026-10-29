/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.account;

import java.time.Clock;
import java.util.function.Function;
import java.util.logging.Logger;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.ibm.cics.cip.bankliberty.api.json.BankRuntime;
import com.ibm.cics.cip.bankliberty.api.json.HBankDataAccess;
import com.ibm.cics.server.InvalidRequestException;

/**
 * Account lifecycle and inquiry data service replacing the embedded SQL of
 * INQACC, INQACCCU, CREACC, UPDACC and DELACC. Contract: API.md.
 */
@Path("/data/account")
@Produces(MediaType.APPLICATION_JSON)
public class AccountDataResource extends HBankDataAccess
{

	private static final Logger LOGGER = Logger
			.getLogger(AccountDataResource.class.getName());


	@GET
	@Path("/{sortcode}/last")
	public Response inquireLast(@PathParam("sortcode") String sortcode)
	{
		return run(s -> s.inquireLast(sortcode), false);
	}


	@GET
	@Path("/{sortcode}/{number}")
	public Response inquire(@PathParam("sortcode") String sortcode,
			@PathParam("number") String number)
	{
		return run(s -> s.inquire(sortcode, number), false);
	}


	@GET
	@Path("/{sortcode}")
	public Response inquireByCustomer(@PathParam("sortcode") String sortcode,
			@QueryParam("customerNumber") String customerNumber)
	{
		return run(s -> s.inquireByCustomer(sortcode, customerNumber), false);
	}


	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public Response create(String body)
	{
		return run(s -> s.create(body), true);
	}


	@PUT
	@Path("/{sortcode}/{number}")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response update(@PathParam("sortcode") String sortcode,
			@PathParam("number") String number, String body)
	{
		return run(s -> s.update(sortcode, number, body), true);
	}


	@DELETE
	@Path("/{sortcode}/{number}")
	public Response delete(@PathParam("sortcode") String sortcode,
			@PathParam("number") String number,
			@QueryParam("taskRef") String taskRef)
	{
		return run(s -> s.delete(sortcode, number, taskRef), true);
	}


	static String currentTaskRef()
	{
		return Integer.toString(BankRuntime.taskNumber());
	}


	/**
	 * A failed write has already been rolled back on the JDBC connection by
	 * the DAO; this also backs out the CICS unit of work, as the legacy
	 * programs' SYNCPOINT ROLLBACK / abend did.
	 */
	static void rollbackOnFailure(ServiceResult result)
	{
		if (result.getStatus() >= 400)
		{
			try
			{
				BankRuntime.rollback();
			}
			catch (InvalidRequestException e)
			{
				LOGGER.warning("CICS rollback failed: " + e.getMessage());
			}
		}
	}


	private Response run(Function<AccountDataService, ServiceResult> call,
			boolean write)
	{
		try
		{
			openConnection();
			ServiceResult result = call.apply(new AccountDataService(this.conn,
					Clock.systemDefaultZone(),
					AccountDataResource::currentTaskRef));
			if (write)
			{
				rollbackOnFailure(result);
			}
			return Response.status(result.getStatus())
					.entity(result.bodyAsJson())
					.type(MediaType.APPLICATION_JSON).build();
		}
		finally
		{
			terminate();
		}
	}
}
