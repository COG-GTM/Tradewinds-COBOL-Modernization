/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.service;

/**
 * Thrown when a transfer is attempted from and to the same account.
 *
 * COBOL equivalent: EXEC CICS ABEND ABCODE('SAME') in UAD010.
 */
public class SameAccountTransferException extends RuntimeException
{


	private static final long serialVersionUID = 1L;


	public SameAccountTransferException(String sortCode, String accountNumber)
	{
		super("Cannot transfer to the same account: " + sortCode + "/"
				+ accountNumber);
	}
}
