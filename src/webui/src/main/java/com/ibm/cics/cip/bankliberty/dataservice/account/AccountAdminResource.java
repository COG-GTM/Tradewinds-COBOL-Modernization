/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.account;

import java.time.Clock;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.ibm.cics.cip.bankliberty.api.json.HBankDataAccess;

/**
 * Bulk account load replacing BANKDATA's ACCOUNT / CONTROL SQL. Contract:
 * API.md.
 */
@Path("/data/admin/account")
@Produces(MediaType.APPLICATION_JSON)
public class AccountAdminResource extends HBankDataAccess
{

	@POST
	@Path("/reset")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response reset(@QueryParam("sortcode") String sortcode,
			@QueryParam("lastAccountNumber") String lastAccountNumber,
			@QueryParam("numberOfAccounts") String numberOfAccounts,
			String body)
	{
		try
		{
			openConnection();
			ServiceResult result = new AccountDataService(this.conn,
					Clock.systemDefaultZone(),
					AccountDataResource::currentTaskRef).reset(sortcode, body,
							lastAccountNumber, numberOfAccounts);
			AccountDataResource.rollbackOnFailure(result);
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
