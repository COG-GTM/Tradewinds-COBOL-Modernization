/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.createaccount;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.ibm.cics.cip.bank.springboot.customerservices.JsonPropertyNamingStrategy;

@JsonNaming(JsonPropertyNamingStrategy.class)
public class CreaccJson
{



	@JsonProperty("CommAccType")
	private String commAccType;

	@JsonProperty("CommCustno")
	private String commCustno;

	@JsonProperty("CommEyecatcher")
	private String commEyecatcher;

	@JsonProperty("CommKey")
	private CreaccKeyJson commKey;

	@JsonProperty("CommIntRt")
	private double commInterestRate;

	@JsonProperty("CommOpened")
	private int commOpened;

	@JsonProperty("CommOverdrLim")
	private double commOverdraftLimit;

	@JsonProperty("CommLastStmtDt")
	private int commLastStatementDate;

	@JsonProperty("CommNextStmtDt")
	private int commNextStatementDate;

	@JsonProperty("CommAvailBal")
	private double commAvailableBalance;

	@JsonProperty("CommActBal")
	private double commActualBalance;

	@JsonProperty("CommSuccess")
	private String commSuccess;

	@JsonProperty("CommFailCode")
	private String commFailCode;


	public CreaccJson(String accountType, String accountNumber,
			double overdraftLimit, double interestRate)
	{
		commAccType = String.format("%-8s", accountType);
		commCustno = String.format("%8s", accountNumber).replace(" ", "0");

		commOverdraftLimit = overdraftLimit;
		commInterestRate = interestRate;

		commEyecatcher = "    ";
		commKey = new CreaccKeyJson();

	}


	public CreaccJson()
	{

	}


	public String getCommEyecatcher()
	{
		return commEyecatcher;
	}


	public void setCommEyecatcher(String commEyecatcherIn)
	{
		commEyecatcher = commEyecatcherIn;
	}


	public String getCommCustno()
	{
		return commCustno;
	}


	public void setCommCustno(String commCustnoIn)
	{
		commCustno = commCustnoIn;
	}


	public CreaccKeyJson getCommKey()
	{
		return commKey;
	}


	public void setCommKey(CreaccKeyJson commKeyIn)
	{
		commKey = commKeyIn;
	}


	public String getCommAccType()
	{
		return commAccType;
	}


	public void setCommAccType(String commAccTypeIn)
	{
		commAccType = commAccTypeIn;
	}


	public double getCommInterestRate()
	{
		return commInterestRate;
	}


	public void setCommInterestRate(double commInterestRateIn)
	{
		commInterestRate = commInterestRateIn;
	}


	public int getCommOpened()
	{
		return commOpened;
	}


	public void setCommOpened(int commOpenedIn)
	{
		commOpened = commOpenedIn;
	}


	public double getCommOverdraftLimit()
	{
		return commOverdraftLimit;
	}


	public void setCommOverdraftLimit(double commOverdraftLimitIn)
	{
		commOverdraftLimit = commOverdraftLimitIn;
	}


	public int getCommLastStatementDate()
	{
		return commLastStatementDate;
	}


	public void setCommLastStatementDate(int setCommLastStatementDateIn)
	{
		commLastStatementDate = setCommLastStatementDateIn;
	}


	public int getCommNextStatementDate()
	{
		return commNextStatementDate;
	}


	public void setCommNextStatementDate(int commNextStatementDateIn)
	{
		commNextStatementDate = commNextStatementDateIn;
	}


	public double getCommAvailableBalance()
	{
		return commAvailableBalance;
	}


	public void setCommAvailableBalance(double commAvailableBalanceIn)
	{
		commAvailableBalance = commAvailableBalanceIn;
	}


	public double getCommActualBalance()
	{
		return commActualBalance;
	}


	public void setCommActualBalance(double commActualBalanceIn)
	{
		commActualBalance = commActualBalanceIn;
	}


	public String getCommSuccess()
	{
		return commSuccess;
	}


	public void setCommSuccess(String commSuccessIn)
	{
		commSuccess = commSuccessIn;
	}


	public String getCommFailCode()
	{
		return commFailCode;
	}


	public void setCommFailCode(String commFailCodeIn)
	{
		commFailCode = commFailCodeIn;
	}

}
