/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.listaccounts;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.ibm.cics.cip.bank.springboot.customerservices.JsonPropertyNamingStrategy;
import com.ibm.cics.cip.bank.springboot.customerservices.OutputFormatUtils;

@JsonNaming(JsonPropertyNamingStrategy.class)
public class AccountDetails
{



	private static final String FLOAT_FORMAT = "%.02f";

	@JsonProperty("CommActualBal")
	private double commActualBalance;

	@JsonProperty("CommAvailBal")
	private double commAvailableBalance;

	// @JsonProperty("COMM_SCODE")
	// private int commSortcode;

	@JsonProperty("CommIntRate")
	private double commInterestRate;

	@JsonProperty("CommEye")
	private String commEye;

	@JsonProperty("CommOpened")
	private int commOpened;

	@JsonProperty("CommCustno")
	private long commCustno;

	@JsonProperty("CommNextStmtDt")
	private int commNextStatementDate;

	@JsonProperty("CommAccType")
	private String commAccType;

	@JsonProperty("CommOverdraft")
	private int commOverdraft;

	@JsonProperty("CommAccno")
	private int commAccno;

	@JsonProperty("CommLastStmtDt")
	private int commLastStatementDate;


	public double getCommActualBalance()
	{
		return commActualBalance;
	}


	public void setCommActualBalance(double commActualBalanceIn)
	{
		commActualBalance = commActualBalanceIn;
	}


	public double getCommAvailableBalance()
	{
		return commAvailableBalance;
	}


	public void setCommAvailableBalance(double commAvailableBalanceIn)
	{
		commAvailableBalance = commAvailableBalanceIn;
	}


	// public int getCommSortcode()
	// {
	// 	return commSortcode;
	// }


	// public void setCommSortcode(int commSortcodeIn)
	// {
	// 	commSortcode = commSortcodeIn;
	// }


	public double getCommInterestRate()
	{
		return commInterestRate;
	}


	public void setCommInterestRate(double commInterestRateIn)
	{
		commInterestRate = commInterestRateIn;
	}


	public String getCommEye()
	{
		return commEye;
	}


	public void setCommEye(String commEyeIn)
	{
		commEye = commEyeIn;
	}


	public int getCommOpened()
	{
		return commOpened;
	}


	public void setCommOpened(int commOpenedIn)
	{
		commOpened = commOpenedIn;
	}


	public long getCommCustno()
	{
		return commCustno;
	}


	public void setCommCustno(long commCustnoIn)
	{
		commCustno = commCustnoIn;
	}


	public int getCommNextStatementDate()
	{
		return commNextStatementDate;
	}


	public void setCommNextStatementDate(int commNextStatementDateIn)
	{
		commNextStatementDate = commNextStatementDateIn;
	}


	public String getCommAccType()
	{
		return commAccType;
	}


	public void setCommAccType(String commAccTypeIn)
	{
		commAccType = commAccTypeIn;
	}


	public int getCommOverdraft()
	{
		return commOverdraft;
	}


	public void setCommOverdraft(int commOverdraftIn)
	{
		commOverdraft = commOverdraftIn;
	}


	public int getCommAccno()
	{
		return commAccno;
	}


	public void setCommAccno(int commAccnoIn)
	{
		commAccno = commAccnoIn;
	}


	public int getCommLastStatementDate()
	{
		return commLastStatementDate;
	}


	public void setCommLastStatementDate(int commLastStatementDateIn)
	{
		commLastStatementDate = commLastStatementDateIn;
	}


	@Override
	public String toString()
	{
		return "AccountDetails [CommAccno=" + commAccno + ", CommAccType="
				+ commAccType + ", CommActualBal=" + commActualBalance
				+ ", CommCustno=" + commCustno + ", CommEye=" + commEye
				+ ", CommIntRate=" + commInterestRate + ", CommLastStmtDt="
				+ commLastStatementDate + ", CommNextStmtDt="
				+ commNextStatementDate + ", CommOpened=" + commOpened
				+ ", CommOverdraft=" + commOverdraft;
				//  + ", CommScode="
				// + commSortcode + "]";
	}


	public String toPrettyString()
	{
		String output = "";
		output += "Account Number:       "
				+ OutputFormatUtils.leadingZeroes(8, commAccno) + "\n"
				// + "Sort Code:            " + String.format("%06d", commSortcode)
				+ "\n" + "Customer Number:      "
				+ OutputFormatUtils.leadingZeroes(10, commCustno) + "\n"
				+ "Account Type:         " + commAccType + "\n"
				+ "Available Balance:    "
				+ String.format(FLOAT_FORMAT, commAvailableBalance) + "\n"
				+ "Actual Balance:       "
				+ String.format(FLOAT_FORMAT, commActualBalance) + "\n"
				+ "Interest Rate:        "
				+ String.format(FLOAT_FORMAT, commInterestRate) + "\n"
				+ "Overdraft:            " + commOverdraft + "\n"
				+ "Account Opened: " + OutputFormatUtils.date(commOpened) + "\n"
				+ "Next Statement Date:  "
				+ OutputFormatUtils.date(commNextStatementDate) + "\n"
				+ "Last Statement Date:  "
				+ OutputFormatUtils.date(commLastStatementDate) + "\n";
		return output;
	}

}
