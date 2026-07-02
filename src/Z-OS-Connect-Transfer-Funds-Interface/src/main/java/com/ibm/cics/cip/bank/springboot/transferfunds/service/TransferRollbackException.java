/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.service;

/**
 * Thrown when one leg of the transfer succeeds but the second fails,
 * triggering a full transaction rollback.
 *
 * COBOL equivalent: EXEC CICS SYNCPOINT ROLLBACK + EXEC CICS ABEND
 * in the UPDATE-ACCOUNT-DB2 section when the second account update fails.
 *
 * Because the service method is @Transactional, throwing this RuntimeException
 * causes Spring to roll back the entire transaction — the same effect as
 * CICS SYNCPOINT ROLLBACK.
 */
public class TransferRollbackException extends RuntimeException
{


	private static final long serialVersionUID = 1L;


	public TransferRollbackException(String message)
	{
		super(message);
	}
}
