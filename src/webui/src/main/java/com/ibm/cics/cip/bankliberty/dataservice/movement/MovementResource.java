/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.movement;

import java.time.Clock;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.ibm.cics.cip.bankliberty.api.json.BankRuntime;
import com.ibm.cics.cip.bankliberty.api.json.HBankDataAccess;
import com.ibm.cics.server.InvalidRequestException;

/**
 * Data service replacing the embedded SQL of DBCRFUN and XFRFUN. See API.md.
 */
@Path("/data/movement")
public class MovementResource extends HBankDataAccess
{

	private static final Logger logger = Logger
			.getLogger("com.ibm.cics.cip.bankliberty.dataservice.movement");

	@POST
	@Path("/debitCredit")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response debitCredit(MovementJson.DebitCreditRequest request)
	{
		openConnection();
		try
		{
			return toResponse(MovementRequestHandler.debitCredit(this.conn,
					Clock.systemDefaultZone(), request));
		}
		finally
		{
			terminate();
		}
	}

	@POST
	@Path("/transfer")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response transfer(MovementJson.TransferRequest request)
	{
		openConnection();
		try
		{
			return toResponse(MovementRequestHandler.transfer(this.conn,
					Clock.systemDefaultZone(), request));
		}
		finally
		{
			terminate();
		}
	}

	/**
	 * The DAO has already committed or rolled back its JDBC unit of work; on
	 * failure also back out the surrounding CICS unit of work (EXEC CICS
	 * SYNCPOINT ROLLBACK) before terminate() syncpoints.
	 */
	static Response toResponse(DataServiceResult result)
	{
		if (result.getStatus() >= 400)
		{
			try
			{
				BankRuntime.rollback();
			}
			catch (InvalidRequestException e)
			{
				logger.log(Level.SEVERE, "Unit of work rollback failed", e);
			}
		}
		return Response.status(result.getStatus())
				.entity(result.getEntity())
				.type(MediaType.APPLICATION_JSON).build();
	}
}
