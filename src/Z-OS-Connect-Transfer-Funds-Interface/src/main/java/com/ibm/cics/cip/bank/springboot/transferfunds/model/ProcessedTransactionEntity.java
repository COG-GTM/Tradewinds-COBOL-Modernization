/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity mapping the Db2 PROCTRAN (Processed Transaction) table.
 *
 * COBOL source: PROCDB2.cpy / HOST-PROCTRAN-ROW in XFRFUN.cbl
 *
 * Key COBOL types preserved:
 *   PROCTRAN_AMOUNT  DECIMAL(12,2) / PIC S9(10)V99 COMP-3  -> BigDecimal
 *   PROCTRAN_TYPE    CHAR(3) — 'TFR' for transfers          -> String
 */
@Entity
@Table(name = "PROCTRAN")
public class ProcessedTransactionEntity
{


	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "PROCTRAN_EYECATCHER", length = 4)
	private String eyecatcher;

	@Column(name = "PROCTRAN_SORTCODE", length = 6, nullable = false)
	private String sortcode;

	@Column(name = "PROCTRAN_NUMBER", length = 8, nullable = false)
	private String accountNumber;

	@Column(name = "PROCTRAN_DATE", length = 10)
	private String transactionDate;

	@Column(name = "PROCTRAN_TIME", length = 6)
	private String transactionTime;

	@Column(name = "PROCTRAN_REF", length = 12)
	private String reference;

	@Column(name = "PROCTRAN_TYPE", length = 3)
	private String type;

	@Column(name = "PROCTRAN_DESC", length = 40)
	private String description;

	@Column(name = "PROCTRAN_AMOUNT", precision = 12, scale = 2)
	private BigDecimal amount;


	public ProcessedTransactionEntity()
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


	public String getEyecatcher()
	{
		return eyecatcher;
	}


	public void setEyecatcher(String eyecatcher)
	{
		this.eyecatcher = eyecatcher;
	}


	public String getSortcode()
	{
		return sortcode;
	}


	public void setSortcode(String sortcode)
	{
		this.sortcode = sortcode;
	}


	public String getAccountNumber()
	{
		return accountNumber;
	}


	public void setAccountNumber(String accountNumber)
	{
		this.accountNumber = accountNumber;
	}


	public String getTransactionDate()
	{
		return transactionDate;
	}


	public void setTransactionDate(String transactionDate)
	{
		this.transactionDate = transactionDate;
	}


	public String getTransactionTime()
	{
		return transactionTime;
	}


	public void setTransactionTime(String transactionTime)
	{
		this.transactionTime = transactionTime;
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
