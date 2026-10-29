/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.account;

import java.time.LocalDate;

/**
 * Builds the 40-byte PROCTRAN_DESC values written by CREACC (type OCA) and
 * DELACC (type ODA). Layout (PROCTRAN.cpy PROC-TRAN-DESC-CREACC /
 * PROC-TRAN-DESC-DELACC), 1-based COBOL offsets:
 *
 * <pre>
 *  1-10  customer number   PIC 9(10)
 * 11-18  account type      PIC X(8)
 * 19-26  last statement    DDMMYYYY
 * 27-34  next statement    DDMMYYYY
 * 35-40  footer            CREACC: 6 spaces / DELACC: 'DELETE'
 * </pre>
 */
public final class AccountProctran
{

	public static final String EYECATCHER = "PRTR";

	public static final String TYPE_BRANCH_CREATE_ACCOUNT = "OCA";

	public static final String TYPE_BRANCH_DELETE_ACCOUNT = "ODA";

	public static final String DELETE_FOOTER = "DELETE";

	/** CREACC WPD010 moves SPACES to HV-PROCTRAN-DESC(35:6). */
	public static final String CREATE_FOOTER = "      ";

	public static final int DESC_LENGTH = 40;


	private AccountProctran()
	{
	}


	public static String createAccountDescription(String customerNumber,
			String accountType, LocalDate lastStatement,
			LocalDate nextStatement)
	{
		return description(customerNumber, accountType, lastStatement,
				nextStatement, CREATE_FOOTER);
	}


	public static String deleteAccountDescription(String customerNumber,
			String accountType, LocalDate lastStatement,
			LocalDate nextStatement)
	{
		return description(customerNumber, accountType, lastStatement,
				nextStatement, DELETE_FOOTER);
	}


	private static String description(String customerNumber,
			String accountType, LocalDate lastStatement,
			LocalDate nextStatement, String footer)
	{
		String desc = CobolFormat.zeroPad(
				Long.parseLong(customerNumber.trim()),
				CobolFormat.CUSTOMER_NUMBER_LENGTH)
				+ CobolFormat.blankPad(accountType,
						CobolFormat.ACCOUNT_TYPE_LENGTH)
				+ CobolFormat.ddmmyyyy(lastStatement)
				+ CobolFormat.ddmmyyyy(nextStatement) + footer;
		return CobolFormat.blankPad(desc, DESC_LENGTH);
	}
}
