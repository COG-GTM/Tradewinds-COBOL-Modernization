/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.exception;

/**
 * Raised for unrecoverable datastore errors during a transfer.
 *
 * <p>
 * Corresponds to the XFRFUN paths that set {@code COMM-FAIL-CODE = '3'} and/or
 * link to the abend handler and issue {@code EXEC CICS ABEND} (for example
 * {@code WPCD} when the PROCTRAN insert fails after the accounts were already
 * updated). In the modernized service the surrounding transaction is rolled
 * back and this exception is thrown so no partial update is committed.
 * </p>
 */
public class TransferProcessingException extends RuntimeException
{

	private static final long serialVersionUID = 1L;

	public TransferProcessingException(String message)
	{
		super(message);
	}

	public TransferProcessingException(String message, Throwable cause)
	{
		super(message, cause);
	}
}
