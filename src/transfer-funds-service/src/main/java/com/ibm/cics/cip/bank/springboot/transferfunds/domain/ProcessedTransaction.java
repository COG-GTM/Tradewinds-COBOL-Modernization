/*
 *
 *    Copyright IBM Corp. 2023
 *
 */
package com.ibm.cics.cip.bank.springboot.transferfunds.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA mapping of the Db2 PROCTRAN (processed transaction) audit table,
 * mirroring HOST-PROCTRAN-ROW in XFRFUN. A surrogate id replaces the
 * CICS task number (EIBTASKN) as the uniqueness mechanism; the task
 * number itself maps to the reference column.
 */
@Entity
@Table(name = "PROCTRAN")
public class ProcessedTransaction
{
	/** PROC-TRAN-TYPE value for a transfer (88 PROC-TY-TRANSFER 'TFR'). */
	public static final String TYPE_TRANSFER = "TFR";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "PROCTRAN_ID")
	private Long id;

	@Column(name = "PROCTRAN_EYECATCHER", length = 4)
	private String eyecatcher = "PRTR";

	@Column(name = "PROCTRAN_SORTCODE", length = 6)
	private String sortCode;

	@Column(name = "PROCTRAN_NUMBER", length = 8)
	private String accountNumber;

	@Column(name = "PROCTRAN_DATE", length = 10)
	private String date;

	@Column(name = "PROCTRAN_TIME", length = 6)
	private String time;

	@Column(name = "PROCTRAN_REF", length = 12)
	private String reference;

	@Column(name = "PROCTRAN_TYPE", length = 3)
	private String type;

	@Column(name = "PROCTRAN_DESC", length = 40)
	private String description;

	@Column(name = "PROCTRAN_AMOUNT", precision = 12, scale = 2)
	private BigDecimal amount;

	public String getSortCode()
	{
		return sortCode;
	}

	public void setSortCode(String sortCode)
	{
		this.sortCode = sortCode;
	}

	public String getAccountNumber()
	{
		return accountNumber;
	}

	public void setAccountNumber(String accountNumber)
	{
		this.accountNumber = accountNumber;
	}

	public String getDate()
	{
		return date;
	}

	public void setDate(String date)
	{
		this.date = date;
	}

	public String getTime()
	{
		return time;
	}

	public void setTime(String time)
	{
		this.time = time;
	}

	public String getReference()
	{
		return reference;
	}

	public void setReference(String reference)
	{
		this.reference = reference;
	}

	public String getType()
	{
		return type;
	}

	public void setType(String type)
	{
		this.type = type;
	}

	public String getDescription()
	{
		return description;
	}

	public void setDescription(String description)
	{
		this.description = description;
	}

	public BigDecimal getAmount()
	{
		return amount;
	}

	public void setAmount(BigDecimal amount)
	{
		this.amount = amount;
	}
}
