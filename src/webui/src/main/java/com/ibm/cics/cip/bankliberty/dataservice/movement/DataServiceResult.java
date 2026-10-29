/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.movement;

/** HTTP status plus JSON entity, independent of the JAX-RS runtime. */
public class DataServiceResult
{

	private final int status;

	private final Object entity;

	public DataServiceResult(int status, Object entity)
	{
		this.status = status;
		this.entity = entity;
	}

	public int getStatus()
	{
		return status;
	}

	public Object getEntity()
	{
		return entity;
	}
}
