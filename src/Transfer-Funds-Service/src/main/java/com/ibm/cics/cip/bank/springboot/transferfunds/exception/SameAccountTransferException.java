/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.exception;

/**
 * Raised when a transfer is requested with the same FROM and TO account.
 *
 * <p>
 * In XFRFUN ({@code UPDATE-ACCOUNT-DB2}, label {@code UAD010}) this condition
 * causes the program to link to the abend handler and issue
 * {@code EXEC CICS ABEND ABCODE('SAME')}. The modernized service surfaces it as
 * a checked business exception instead of abending the task.
 * </p>
 */
public class SameAccountTransferException extends RuntimeException
{

	private static final long serialVersionUID = 1L;

	/** COBOL abend code issued for this condition. */
	public static final String ABEND_CODE = "SAME";

	public SameAccountTransferException()
	{
		super("Transfer rejected: cannot transfer to the same account.");
	}
}
