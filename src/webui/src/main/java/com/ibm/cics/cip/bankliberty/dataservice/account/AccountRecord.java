/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One ACCOUNT row (ACCOUNT.cpy / ACCDB2.cpy). Key and text columns are held
 * in their fixed-width CHAR form (zero-padded numbers, blank-padded type).
 */
public class AccountRecord
{

	public static final String EYECATCHER = "ACCT";

	private String eyecatcher = EYECATCHER;

	private String customerNumber;

	private String sortcode;

	private String accountNumber;

	private String accountType;

	private BigDecimal interestRate = BigDecimal.ZERO;

	private LocalDate opened;

	private long overdraftLimit;

	private LocalDate lastStatement;

	private LocalDate nextStatement;

	private BigDecimal availableBalance = BigDecimal.ZERO;

	private BigDecimal actualBalance = BigDecimal.ZERO;


	public String getEyecatcher()
	{
		return eyecatcher;
	}


	public void setEyecatcher(String eyecatcher)
	{
		this.eyecatcher = eyecatcher;
	}


	public String getCustomerNumber()
	{
		return customerNumber;
	}


	public void setCustomerNumber(String customerNumber)
	{
		this.customerNumber = customerNumber;
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


	public String getAccountType()
	{
		return accountType;
	}


	public void setAccountType(String accountType)
	{
		this.accountType = accountType;
	}


	public BigDecimal getInterestRate()
	{
		return interestRate;
	}


	public void setInterestRate(BigDecimal interestRate)
	{
		this.interestRate = interestRate;
	}


	public LocalDate getOpened()
	{
		return opened;
	}


	public void setOpened(LocalDate opened)
	{
		this.opened = opened;
	}


	public long getOverdraftLimit()
	{
		return overdraftLimit;
	}


	public void setOverdraftLimit(long overdraftLimit)
	{
		this.overdraftLimit = overdraftLimit;
	}


	public LocalDate getLastStatement()
	{
		return lastStatement;
	}


	public void setLastStatement(LocalDate lastStatement)
	{
		this.lastStatement = lastStatement;
	}


	public LocalDate getNextStatement()
	{
		return nextStatement;
	}


	public void setNextStatement(LocalDate nextStatement)
	{
		this.nextStatement = nextStatement;
	}


	public BigDecimal getAvailableBalance()
	{
		return availableBalance;
	}


	public void setAvailableBalance(BigDecimal availableBalance)
	{
		this.availableBalance = availableBalance;
	}


	public BigDecimal getActualBalance()
	{
		return actualBalance;
	}


	public void setActualBalance(BigDecimal actualBalance)
	{
		this.actualBalance = actualBalance;
	}


	/**
	 * COMMAREA-shaped JSON: fixed-width strings for keys and type, DDMMYYYY
	 * dates, decimals as strings with exactly two places.
	 */
	public Map<String, Object> toJson()
	{
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("eyeCatcher", eyecatcher);
		json.put("customerNumber", customerNumber);
		json.put("sortcode", sortcode);
		json.put("accountNumber", accountNumber);
		json.put("accountType",
				CobolFormat.blankPad(accountType, CobolFormat.ACCOUNT_TYPE_LENGTH));
		json.put("interestRate", money(interestRate));
		json.put("opened", CobolFormat.ddmmyyyy(opened));
		json.put("overdraftLimit", Long.valueOf(overdraftLimit));
		json.put("lastStatementDate", CobolFormat.ddmmyyyy(lastStatement));
		json.put("nextStatementDate", CobolFormat.ddmmyyyy(nextStatement));
		json.put("availableBalance", money(availableBalance));
		json.put("actualBalance", money(actualBalance));
		return json;
	}


	static String money(BigDecimal value)
	{
		return (value == null ? BigDecimal.ZERO : value)
				.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}
}
