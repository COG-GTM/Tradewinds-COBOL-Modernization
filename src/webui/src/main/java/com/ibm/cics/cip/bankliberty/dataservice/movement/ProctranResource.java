/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.movement;

import java.time.Clock;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.ibm.cics.cip.bankliberty.api.json.HBankDataAccess;

/**
 * Data service for PROCTRAN audit rows written by CRECUST and DELCUS, plus a
 * generic writer and reader. See API.md.
 */
@Path("/data/proctran")
public class ProctranResource extends HBankDataAccess
{

	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response write(MovementJson.ProctranWriteRequest request)
	{
		openConnection();
		try
		{
			return MovementResource.toResponse(MovementRequestHandler
					.writeProctran(this.conn, Clock.systemDefaultZone(), request));
		}
		finally
		{
			terminate();
		}
	}

	@POST
	@Path("/createCustomer")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response createCustomer(MovementJson.CustomerRequest request)
	{
		openConnection();
		try
		{
			return MovementResource.toResponse(MovementRequestHandler
					.createCustomer(this.conn, Clock.systemDefaultZone(), request));
		}
		finally
		{
			terminate();
		}
	}

	@POST
	@Path("/deleteCustomer")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response deleteCustomer(MovementJson.CustomerRequest request)
	{
		openConnection();
		try
		{
			return MovementResource.toResponse(MovementRequestHandler
					.deleteCustomer(this.conn, Clock.systemDefaultZone(), request));
		}
		finally
		{
			terminate();
		}
	}

	@GET
	@Path("/{sortcode}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response list(@PathParam("sortcode") String sortCode,
			@QueryParam("limit") Integer limit,
			@QueryParam("offset") Integer offset)
	{
		openConnection();
		try
		{
			return MovementResource.toResponse(MovementRequestHandler
					.listProctran(this.conn, sortCode, limit, offset));
		}
		finally
		{
			terminate();
		}
	}
}
