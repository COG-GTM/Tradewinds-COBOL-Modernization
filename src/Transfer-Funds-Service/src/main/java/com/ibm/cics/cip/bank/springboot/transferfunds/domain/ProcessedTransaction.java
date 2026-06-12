/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity for the {@code PROCTRAN} (Processed Transaction) datastore.
 *
 * <p>
 * Modernizes the COBOL {@code PROCTRAN} copybook and the {@code INSERT INTO
 * PROCTRAN} statement in XFRFUN section {@code WRITE-TO-PROCTRAN-DB2}. PROCTRAN
 * is an append-only audit log of successful transactions, so this entity adds a
 * generated surrogate primary key ({@code PROCTRAN_ID}) rather than reusing the
 * COBOL logical key (sort code + number), which is not unique per row.
 * </p>
 */
@Entity
@Table(name = "PROCTRAN")
public class ProcessedTransaction
{

	/** COBOL {@code PROC-TRAN-VALID} eyecatcher. */
	public static final String EYECATCHER = "PRTR";

	/** COBOL {@code PROC-TY-TRANSFER} transaction type. */
	public static final String TYPE_TRANSFER = "TFR";

	/** COBOL {@code PROC-TRAN-DESC-XFR-FLAG} description header (26 chars). */
	public static final String DESC_TRANSFER_HEADER = "TRANSFER";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "PROCTRAN_ID")
	private Long id;

	@Column(name = "PROCTRAN_EYECATCHER", length = 4)
	private String eyeCatcher = EYECATCHER;

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

	public ProcessedTransaction()
	{
	}

	public Long getId()
	{
		return id;
	}

	public void setId(Long id)
	{
		this.id = id;
	}

	public String getEyeCatcher()
	{
		return eyeCatcher;
	}

	public void setEyeCatcher(String eyeCatcher)
	{
		this.eyeCatcher = eyeCatcher;
	}

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

	@Override
	public String toString()
	{
		return "ProcessedTransaction [type=" + type + ", sortCode=" + sortCode
				+ ", accountNumber=" + accountNumber + ", amount=" + amount
				+ ", description=" + description + "]";
	}
}
