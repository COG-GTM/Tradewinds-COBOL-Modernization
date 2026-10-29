/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.movement;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Fixed-width field formatting for PROCTRAN rows, mirroring the COBOL host
 * variables in PROCDB2.cpy / the PROCTRAN.cpy record layout.
 */
public final class ProctranFormat
{

	public static final String EYECATCHER = "PRTR";

	public static final int SORT_CODE_LEN = 6;

	public static final int ACCOUNT_NUMBER_LEN = 8;

	public static final int CUSTOMER_NUMBER_LEN = 10;

	public static final int REF_LEN = 12;

	public static final int DESC_LEN = 40;

	public static final int TYPE_LEN = 3;

	/** COMM-FACILTYPE value used by the payment interface (DBCRFUN). */
	public static final int FACILITY_TYPE_PAYMENT = 496;

	public static final String TYPE_CREDIT = "CRE";

	public static final String TYPE_DEBIT = "DEB";

	public static final String TYPE_PAYMENT_CREDIT = "PCR";

	public static final String TYPE_PAYMENT_DEBIT = "PDR";

	public static final String TYPE_TRANSFER = "TFR";

	public static final String TYPE_BRANCH_CREATE_CUSTOMER = "OCC";

	public static final String TYPE_BRANCH_DELETE_CUSTOMER = "ODC";

	/** DBCRFUN WTPD010: teller debit description. */
	public static final String DESC_COUNTER_WITHDRAW = "COUNTER WTHDRW";

	/** DBCRFUN WTPD010: teller credit description. */
	public static final String DESC_COUNTER_RECEIVED = "COUNTER RECVED";

	/** PROCTRAN.cpy PROC-TRAN-DESC-XFR-FLAG. */
	public static final String DESC_TRANSFER_FLAG = "TRANSFER";

	/** PROCTRAN.cpy PROC-TRAN-DESC-XFR-HEADER is PIC X(26). */
	public static final int DESC_TRANSFER_HEADER_LEN = 26;

	/** Largest value of PIC S9(10)V99. */
	public static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

	private static final long MAX_REF = 999_999_999_999L;

	private static final DateTimeFormatter HHMMSS = DateTimeFormatter
			.ofPattern("HHmmss");

	/** Every 88-level PROC-TY-* value in PROCTRAN.cpy. */
	public static final Set<String> ALLOWED_TYPES;

	static
	{
		Set<String> types = new LinkedHashSet<>();
		Collections.addAll(types, "CHA", "CHF", "CHI", "CHO", "CRE", "DEB",
				"ICA", "ICC", "IDA", "IDC", "OCA", "OCC", "ODA", "ODC", "OCS",
				"PCR", "PDR", "TFR");
		ALLOWED_TYPES = Collections.unmodifiableSet(types);
	}

	private ProctranFormat()
	{
	}

	public static String sortCode(String value)
	{
		return zeroPadDigits("sortCode", value, SORT_CODE_LEN);
	}

	public static String accountNumber(String value)
	{
		return zeroPadDigits("accountNumber", value, ACCOUNT_NUMBER_LEN);
	}

	public static String customerNumber(String value)
	{
		return zeroPadDigits("customerNumber", value, CUSTOMER_NUMBER_LEN);
	}

	/**
	 * MOVE EIBTASKN TO WS-EIBTASKN12 (PIC 9(12)); MOVE WS-EIBTASKN12 TO
	 * HV-PROCTRAN-REF.
	 */
	public static String reference(long taskNumber)
	{
		if (taskNumber < 0 || taskNumber > MAX_REF)
		{
			throw new IllegalArgumentException(
					"taskNumber must be between 0 and " + MAX_REF);
		}
		return String.format("%012d", taskNumber);
	}

	/** EXEC CICS FORMATTIME TIME(HV-PROCTRAN-TIME): HHMMSS. */
	public static String time(LocalTime time)
	{
		return time.format(HHMMSS);
	}

	/** COBOL alphanumeric MOVE: left-justify, truncate or blank-pad. */
	public static String fixed(String value, int length)
	{
		String v = value == null ? "" : value;
		if (v.length() >= length)
		{
			return v.substring(0, length);
		}
		StringBuilder sb = new StringBuilder(length).append(v);
		while (sb.length() < length)
		{
			sb.append(' ');
		}
		return sb.toString();
	}

	/**
	 * DBCRFUN WTPD010. Teller: 'COUNTER WTHDRW' / 'COUNTER RECVED'. Payment
	 * interface (facility type 496): COMM-ORIGIN(1:14), i.e. COMM-APPLID
	 * (8) followed by the first 6 bytes of COMM-USERID.
	 */
	public static String debitCreditDescription(boolean debit,
			int facilityType, String applId, String userId)
	{
		if (facilityType == FACILITY_TYPE_PAYMENT)
		{
			String origin = fixed(applId, 8) + fixed(userId, 8);
			return fixed(origin.substring(0, 14), DESC_LEN);
		}
		return fixed(debit ? DESC_COUNTER_WITHDRAW : DESC_COUNTER_RECEIVED,
				DESC_LEN);
	}

	public static String debitCreditType(boolean debit, int facilityType)
	{
		if (facilityType == FACILITY_TYPE_PAYMENT)
		{
			return debit ? TYPE_PAYMENT_DEBIT : TYPE_PAYMENT_CREDIT;
		}
		return debit ? TYPE_DEBIT : TYPE_CREDIT;
	}

	/**
	 * XFRFUN WTPD010 / PROC-TRAN-DESC-XFR: 'TRANSFER' padded to 26, then
	 * target sort code PIC 9(6) and target account PIC 9(8).
	 */
	public static String transferDescription(String targetSortCode,
			String targetAccount)
	{
		return fixed(DESC_TRANSFER_FLAG, DESC_TRANSFER_HEADER_LEN)
				+ sortCode(targetSortCode) + accountNumber(targetAccount);
	}

	/**
	 * CRECUST WPD010 / DELCUS WPCD010: DESC(1:6) sort code, DESC(7:10)
	 * customer number, DESC(17:14) name, DESC(31:10) DOB as DD/MM/YYYY.
	 *
	 * @param dateOfBirth CUSTOMER-DATE-OF-BIRTH, PIC 9(8) DDMMYYYY
	 */
	public static String customerDescription(String sortCode,
			String customerNumber, String customerName, String dateOfBirth)
	{
		String dob = dateOfBirth == null ? "" : dateOfBirth;
		if (!dob.matches("\\d{8}"))
		{
			throw new IllegalArgumentException(
					"dateOfBirth must be 8 digits DDMMYYYY");
		}
		String storedDob = dob.substring(0, 2) + "/" + dob.substring(2, 4) + "/"
				+ dob.substring(4, 8);
		return sortCode(sortCode) + customerNumber(customerNumber)
				+ fixed(customerName, 14) + storedDob;
	}

	public static BigDecimal amount(BigDecimal value)
	{
		if (value == null)
		{
			throw new IllegalArgumentException("amount is required");
		}
		BigDecimal stripped = value.stripTrailingZeros();
		if (stripped.scale() > 2)
		{
			throw new IllegalArgumentException(
					"amount must have at most 2 decimal places");
		}
		if (value.abs().compareTo(MAX_AMOUNT) > 0)
		{
			throw new IllegalArgumentException(
					"amount exceeds PIC S9(10)V99");
		}
		return value.setScale(2);
	}

	public static String type(String value)
	{
		if (value == null || !ALLOWED_TYPES.contains(value))
		{
			throw new IllegalArgumentException(
					"type must be one of " + ALLOWED_TYPES);
		}
		return value;
	}

	public static String description(String value)
	{
		if (value != null && value.length() > DESC_LEN)
		{
			throw new IllegalArgumentException(
					"description must be at most " + DESC_LEN + " characters");
		}
		return fixed(value, DESC_LEN);
	}

	private static String zeroPadDigits(String name, String value, int length)
	{
		String v = value == null ? "" : value.trim();
		if (v.isEmpty() || v.length() > length || !v.matches("\\d+"))
		{
			throw new IllegalArgumentException(
					name + " must be 1-" + length + " digits");
		}
		StringBuilder sb = new StringBuilder(length);
		for (int i = v.length(); i < length; i++)
		{
			sb.append('0');
		}
		return sb.append(v).toString();
	}
}
