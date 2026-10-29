/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.account;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Fixed-width formatting helpers that reproduce COBOL MOVE semantics for the
 * key and text columns (zero-padded PIC 9 fields, blank-padded PIC X fields).
 */
public final class CobolFormat
{

	public static final int SORTCODE_LENGTH = 6;

	public static final int ACCOUNT_NUMBER_LENGTH = 8;

	public static final int CUSTOMER_NUMBER_LENGTH = 10;

	public static final int ACCOUNT_TYPE_LENGTH = 8;

	public static final int TASK_REF_LENGTH = 12;

	private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter
			.ofPattern("ddMMyyyy");

	private static final DateTimeFormatter HHMMSS = DateTimeFormatter
			.ofPattern("HHmmss");


	private CobolFormat()
	{
	}


	/**
	 * Validates that the value is 1..width decimal digits and left-pads it
	 * with zeros, as a COBOL MOVE into PIC 9(width) would.
	 */
	public static String digits(String value, int width, String fieldName)
			throws DataServiceException
	{
		if (value == null)
		{
			throw DataServiceException.validation("V",
					fieldName + " is required");
		}
		String trimmed = value.trim();
		if (trimmed.isEmpty() || trimmed.length() > width
				|| !trimmed.chars().allMatch(c -> c >= '0' && c <= '9'))
		{
			throw DataServiceException.validation("V", fieldName
					+ " must be 1 to " + width + " decimal digits");
		}
		return zeroPad(Long.parseLong(trimmed), width);
	}


	public static String zeroPad(long value, int width)
	{
		return String.format("%0" + width + "d", value);
	}


	/** Blank-pads or truncates to width, as a COBOL MOVE into PIC X(width). */
	public static String blankPad(String value, int width)
	{
		String v = value == null ? "" : value;
		if (v.length() >= width)
		{
			return v.substring(0, width);
		}
		StringBuilder sb = new StringBuilder(v);
		while (sb.length() < width)
		{
			sb.append(' ');
		}
		return sb.toString();
	}


	/** DDMMYYYY, the layout of the COMMAREA date groups; zeros when null. */
	public static String ddmmyyyy(LocalDate date)
	{
		return date == null ? "00000000" : DDMMYYYY.format(date);
	}


	/** HHMMSS, the layout of PROCTRAN_TIME. */
	public static String hhmmss(LocalTime time)
	{
		return HHMMSS.format(time);
	}
}
