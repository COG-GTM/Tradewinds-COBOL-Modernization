/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.movement;

import java.util.List;

/**
 * JSON request/response bodies for the movement and proctran data service,
 * bound by the JAX-RS JSON provider (public fields, null fields omitted). See
 * API.md for the contract.
 */
public final class MovementJson
{

	private MovementJson()
	{
	}

	/** POST /data/movement/debitCredit (DBCRFUN COMMAREA PAYDBCR.cpy). */
	public static class DebitCreditRequest
	{

		public String sortCode;

		public String accountNumber;

		/** Decimal string or number; negative = debit. */
		public String amount;

		/** COMM-FACILTYPE; 496 = payment interface. Defaults to 0. */
		public Integer facilityType;

		/** COMM-APPLID. */
		public String applId;

		/** COMM-USERID. */
		public String userId;

		/** EIBTASKN. */
		public Long taskNumber;
	}

	/** POST /data/movement/transfer (XFRFUN COMMAREA XFRFUN.cpy). */
	public static class TransferRequest
	{

		public String sortCode;

		public String fromAccountNumber;

		public String toAccountNumber;

		public String amount;

		public Long taskNumber;
	}

	/** POST /data/proctran/createCustomer and /deleteCustomer. */
	public static class CustomerRequest
	{

		public String sortCode;

		public String customerNumber;

		public String customerName;

		/** CUSTOMER-DATE-OF-BIRTH, PIC 9(8) DDMMYYYY. */
		public String dateOfBirth;

		public Long taskNumber;
	}

	/** POST /data/proctran (generic writer). */
	public static class ProctranWriteRequest
	{

		public String sortCode;

		public String accountNumber;

		public String type;

		public String description;

		public String amount;

		public Long taskNumber;
	}

	/** One PROCTRAN row as stored (CHAR columns keep their blank padding). */
	public static class ProctranRow
	{

		public String eyecatcher;

		public String sortCode;

		public String accountNumber;

		/** ISO yyyy-MM-dd. */
		public String date;

		/** HHMMSS. */
		public String time;

		public String reference;

		public String type;

		public String description;

		public String amount;
	}

	/** Response for every POST endpoint. */
	public static class MovementResponse
	{

		public String success;

		public String failCode;

		public String abendCode;

		public String errorMessage;

		/** debitCredit: COMM-AV-BAL. */
		public String availableBalance;

		/** debitCredit: COMM-ACT-BAL. */
		public String actualBalance;

		/** transfer: COMM-FAVBAL. */
		public String fromAvailableBalance;

		/** transfer: COMM-FACTBAL. */
		public String fromActualBalance;

		/** transfer: COMM-TAVBAL. */
		public String toAvailableBalance;

		/** transfer: COMM-TACTBAL. */
		public String toActualBalance;

		public ProctranRow proctran;
	}

	/** Response for GET /data/proctran/{sortcode}. */
	public static class ProctranListResponse
	{

		public String success;

		public String failCode;

		public String errorMessage;

		public Integer numberOfRecords;

		public List<ProctranRow> records;
	}
}
