/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.movement;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One PROCTRAN row (PROCDB2.cpy host variables). */
public class ProctranRecord
{

	private final String eyecatcher;

	private final String sortCode;

	private final String accountNumber;

	private final LocalDate date;

	private final String time;

	private final String reference;

	private final String type;

	private final String description;

	private final BigDecimal amount;

	public ProctranRecord(String eyecatcher, String sortCode,
			String accountNumber, LocalDate date, String time, String reference,
			String type, String description, BigDecimal amount)
	{
		this.eyecatcher = eyecatcher;
		this.sortCode = sortCode;
		this.accountNumber = accountNumber;
		this.date = date;
		this.time = time;
		this.reference = reference;
		this.type = type;
		this.description = description;
		this.amount = amount;
	}

	public String getEyecatcher()
	{
		return eyecatcher;
	}

	public String getSortCode()
	{
		return sortCode;
	}

	public String getAccountNumber()
	{
		return accountNumber;
	}

	public LocalDate getDate()
	{
		return date;
	}

	public String getTime()
	{
		return time;
	}

	public String getReference()
	{
		return reference;
	}

	public String getType()
	{
		return type;
	}

	public String getDescription()
	{
		return description;
	}

	public BigDecimal getAmount()
	{
		return amount;
	}
}
