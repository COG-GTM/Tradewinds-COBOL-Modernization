/*
 *
 *    Copyright IBM Corp. 2023
 *
 *
 */
package com.ibm.cics.cip.bank.springboot.customerservices.core;

import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.accountenquiry.AccountEnquiryJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.accountenquiry.InqaccJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.createaccount.CreateAccountJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.createaccount.CreaccJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.createaccount.CreaccKeyJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.createcustomer.CreateCustomerJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.createcustomer.CrecustJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.customerenquiry.CustomerEnquiryJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.customerenquiry.InqCustDob;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.customerenquiry.InqCustReviewDate;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.customerenquiry.InqCustZJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.deleteaccount.DelaccJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.deleteaccount.DeleteAccountJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.deletecustomer.DelcusJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.deletecustomer.DeleteCustomerJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.listaccounts.AccountDetails;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.listaccounts.InqAccczJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.listaccounts.ListAccJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.updateaccount.UpdaccJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.updateaccount.UpdateAccountJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.updatecustomer.UpdateCustomerJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.updatecustomer.UpdcustJson;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps cbsa-core-service JSON (zero-padded string ids, ISO dates, decimal
 * money) onto the legacy z/OS Connect COMMAREA JSON classes (numeric ids,
 * DDMMYYYY dates, float money) that the forms and templates display.
 */
public final class LegacyMapper
{

	private static final String YES = "Y";


	private LegacyMapper()
	{
	}


	static AccountEnquiryJson toAccountEnquiry(CoreAccount a)
	{
		InqaccJson inqacc = new InqaccJson();
		inqacc.setInqaccEyecatcher("ACCT");
		inqacc.setInqaccCustno(toLong(a.customerNumber()));
		inqacc.setInqaccSortcode(toInt(a.sortCode()));
		inqacc.setInqaccAccno(toInt(a.id()));
		inqacc.setInqaccAccType(trim(a.accountType()));
		inqacc.setInqaccInterestRate(toDouble(a.interestRate()));
		inqacc.setInqaccOpened(toDdmmyyyy(a.dateOpened()));
		inqacc.setInqaccOverdraft(toWhole(a.overdraft()));
		inqacc.setInqaccLastStatementDate(toDdmmyyyy(a.lastStatementDate()));
		inqacc.setInqaccNextStatementDate(toDdmmyyyy(a.nextStatementDate()));
		inqacc.setInqaccAvailableBalance(toDouble(a.availableBalance()));
		inqacc.setInqaccActualBalance(toDouble(a.actualBalance()));
		inqacc.setInqaccSuccess(YES);
		AccountEnquiryJson json = new AccountEnquiryJson();
		json.setInqaccCommarea(inqacc);
		return json;
	}


	static CustomerEnquiryJson toCustomerEnquiry(CoreCustomer c)
	{
		InqCustZJson inqcust = new InqCustZJson();
		inqcust.setInqcustEye("CUST");
		inqcust.setInqcustSortcode(toInt(c.sortCode()));
		inqcust.setInqcustCustno(toLong(c.id()));
		inqcust.setInqcustName(c.customerName());
		inqcust.setInqcustAddress(c.customerAddress());
		int[] dob = dateParts(c.dateOfBirth());
		InqCustDob inqDob = new InqCustDob();
		inqDob.setInqcustDobDd(dob[0]);
		inqDob.setInqcustDobMm(dob[1]);
		inqDob.setInqcustDobYyyy(dob[2]);
		inqcust.setInqcustDob(inqDob);
		inqcust.setInqcustCreditScore(toInt(c.customerCreditScore()));
		int[] review = dateParts(c.customerCreditScoreReviewDate());
		InqCustReviewDate inqReview = new InqCustReviewDate();
		inqReview.setInqcustCsReviewDd(review[0]);
		inqReview.setInqcustCsReviewMm(review[1]);
		inqReview.setInqcustCsReviewYyyy(review[2]);
		inqcust.setInqcustCsReviewDate(inqReview);
		inqcust.setInqcustInqSuccess(YES);
		inqcust.setInqcustInqFailCode(0);
		CustomerEnquiryJson json = new CustomerEnquiryJson();
		json.setInqCustZ(inqcust);
		return json;
	}


	static ListAccJson toListAccounts(String customerNumber, CoreAccountList l)
	{
		InqAccczJson inqacccz = new InqAccczJson();
		inqacccz.setCustomerNumber(toLong(customerNumber));
		boolean found = l != null && !Boolean.FALSE.equals(l.customerFound());
		inqacccz.setCustomerFound(found ? YES : "N");
		inqacccz.setCommSuccess(found ? YES : "N");
		inqacccz.setCommFailCode(found ? 0 : 1);
		List<AccountDetails> details = new ArrayList<>();
		if (l != null && l.accounts() != null)
		{
			for (CoreAccount a : l.accounts())
			{
				details.add(toAccountDetails(a));
			}
		}
		inqacccz.setAccountDetails(details);
		ListAccJson json = new ListAccJson();
		json.setInqacccz(inqacccz);
		return json;
	}


	static AccountDetails toAccountDetails(CoreAccount a)
	{
		AccountDetails d = new AccountDetails();
		d.setCommEye("ACCT");
		d.setCommCustno(toLong(a.customerNumber()));
		d.setCommAccno(toInt(a.id()));
		d.setCommAccType(trim(a.accountType()));
		d.setCommInterestRate(toDouble(a.interestRate()));
		d.setCommOpened(toDdmmyyyy(a.dateOpened()));
		d.setCommOverdraft(toWhole(a.overdraft()));
		d.setCommLastStatementDate(toDdmmyyyy(a.lastStatementDate()));
		d.setCommNextStatementDate(toDdmmyyyy(a.nextStatementDate()));
		d.setCommAvailableBalance(toDouble(a.availableBalance()));
		d.setCommActualBalance(toDouble(a.actualBalance()));
		return d;
	}


	static CreateAccountJson toCreateAccount(CoreAccount a)
	{
		CreaccJson creacc = new CreaccJson();
		creacc.setCommEyecatcher("ACCT");
		creacc.setCommCustno(padCustomer(a.customerNumber()));
		CreaccKeyJson key = new CreaccKeyJson();
		key.setCommSortcode(toInt(a.sortCode()));
		key.setCommNumber(toInt(a.id()));
		creacc.setCommKey(key);
		creacc.setCommAccType(trim(a.accountType()));
		creacc.setCommInterestRate(toDouble(a.interestRate()));
		creacc.setCommOpened(toDdmmyyyy(a.dateOpened()));
		creacc.setCommOverdraftLimit(toDouble(a.overdraft()));
		creacc.setCommLastStatementDate(toDdmmyyyy(a.lastStatementDate()));
		creacc.setCommNextStatementDate(toDdmmyyyy(a.nextStatementDate()));
		creacc.setCommAvailableBalance(toDouble(a.availableBalance()));
		creacc.setCommActualBalance(toDouble(a.actualBalance()));
		creacc.setCommSuccess(YES);
		creacc.setCommFailCode("");
		CreateAccountJson json = new CreateAccountJson();
		json.setCreAcc(creacc);
		return json;
	}


	static CreateCustomerJson toCreateCustomer(CoreCustomer c)
	{
		CrecustJson crecust = new CrecustJson();
		crecust.setCommEyecatcher("CUST");
		CreaccKeyJson key = new CreaccKeyJson();
		key.setCommSortcode(toInt(c.sortCode()));
		key.setCommNumber(toInt(c.id()));
		crecust.setCommKey(key);
		crecust.setCommName(c.customerName());
		crecust.setCommAddress(c.customerAddress());
		crecust.setCommDateOfBirth(toDdmmyyyyString(c.dateOfBirth()));
		crecust.setCommCreditScore(toInt(c.customerCreditScore()));
		crecust.setCommCsReviewDate(
				toDdmmyyyyString(c.customerCreditScoreReviewDate()));
		crecust.setCommSuccess(YES);
		crecust.setCommFailCode("");
		CreateCustomerJson json = new CreateCustomerJson();
		json.setCreCust(crecust);
		return json;
	}


	static UpdateAccountJson toUpdateAccount(CoreAccount a)
	{
		UpdaccJson updacc = new UpdaccJson();
		updacc.setCommEye("ACCT");
		updacc.setCommCustNo(padCustomer(a.customerNumber()));
		updacc.setCommSortcode(a.sortCode());
		updacc.setCommAccno(toInt(a.id()));
		updacc.setCommAccountType(trim(a.accountType()));
		updacc.setCommInterestRate(toDouble(a.interestRate()));
		updacc.setCommOpened(toDdmmyyyyString(a.dateOpened()));
		updacc.setCommOverdraft(toWhole(a.overdraft()));
		updacc.setCommLastStatementDate(
				toDdmmyyyyString(a.lastStatementDate()));
		updacc.setCommNextStatementDate(
				toDdmmyyyyString(a.nextStatementDate()));
		updacc.setCommAvailableBalance(toDouble(a.availableBalance()));
		updacc.setCommActualBalance(toDouble(a.actualBalance()));
		updacc.setCommSuccess(YES);
		UpdateAccountJson json = new UpdateAccountJson();
		json.setUpdacc(updacc);
		return json;
	}


	static UpdateCustomerJson toUpdateCustomer(CoreCustomer c)
	{
		UpdcustJson updcust = new UpdcustJson();
		updcust.setCommEye("CUST");
		updcust.setCommSortcode(c.sortCode());
		updcust.setCommCustno(padCustomer(c.id()));
		updcust.setCommName(c.customerName());
		updcust.setCommAddress(c.customerAddress());
		updcust.setCommDateOfBirth(toDdmmyyyy(c.dateOfBirth()));
		updcust.setCommCreditScore(toInt(c.customerCreditScore()));
		updcust.setCommCreditScoreReviewDate(
				toDdmmyyyy(c.customerCreditScoreReviewDate()));
		updcust.setCommUpdateSuccess(YES);
		updcust.setCommUpdateFailCode("");
		UpdateCustomerJson json = new UpdateCustomerJson();
		json.setUpdcust(updcust);
		return json;
	}


	static DeleteAccountJson toDeleteAccount(CoreAccount a)
	{
		DelaccJson delacc = new DelaccJson();
		delacc.setDelaccEye("ACCT");
		delacc.setDelaccCustno(padCustomer(a.customerNumber()));
		delacc.setDelaccSortcode(a.sortCode());
		delacc.setDelaccAccno(toInt(a.id()));
		delacc.setDelaccAccType(trim(a.accountType()));
		delacc.setDelaccInterestRate(toDouble(a.interestRate()));
		delacc.setDelaccOpened(toDdmmyyyyString(a.dateOpened()));
		delacc.setDelaccOverdraft(toWhole(a.overdraft()));
		delacc.setDelAccLastStatementDate(
				toDdmmyyyyString(a.lastStatementDate()));
		delacc.setDelaccNextStatementDate(
				toDdmmyyyyString(a.nextStatementDate()));
		delacc.setDelaccAvailableBalance(toDouble(a.availableBalance()));
		delacc.setDelaccActualBalance(toDouble(a.actualBalance()));
		delacc.setDelaccDelSuccess(YES);
		delacc.delaccDelFailCode(0);
		DeleteAccountJson json = new DeleteAccountJson();
		json.setDelaccCommarea(delacc);
		return json;
	}


	static DeleteCustomerJson toDeleteCustomer(CoreCustomer c)
	{
		DelcusJson delcus = new DelcusJson();
		delcus.setCommEye("CUST");
		delcus.setCommSortcode(toInt(c.sortCode()));
		delcus.setCommCustno(toLong(c.id()));
		delcus.setCommName(c.customerName());
		delcus.setCommAddress(c.customerAddress());
		delcus.setCommDateOfBirth(toDdmmyyyyString(c.dateOfBirth()));
		delcus.setCommCreditScore(toInt(c.customerCreditScore()));
		delcus.setCommCsReviewDate(
				toDdmmyyyyString(c.customerCreditScoreReviewDate()));
		delcus.setCommDelSuccess(YES);
		delcus.setCommDelFailCode(0);
		DeleteCustomerJson json = new DeleteCustomerJson();
		json.setDelcus(delcus);
		return json;
	}


	/** Zero-pads a numeric account number to the 8-digit contract form. */
	public static String padAccount(String number)
	{
		return pad(number, 8);
	}


	/** Zero-pads a numeric customer number to the 10-digit contract form. */
	public static String padCustomer(String number)
	{
		return pad(number, 10);
	}


	private static String pad(String number, int width)
	{
		if (number == null)
		{
			return null;
		}
		String trimmed = number.trim();
		if (!trimmed.matches("\\d+") || trimmed.length() >= width)
		{
			return trimmed;
		}
		return "0".repeat(width - trimmed.length()) + trimmed;
	}


	/**
	 * Form dates are held as DDMMYYYY (CreateCustomerForm.setCustDob); the
	 * contract wants yyyy-MM-dd.
	 */
	public static String toIsoDate(String date)
	{
		if (date == null)
		{
			return null;
		}
		String d = date.trim();
		if (d.matches("\\d{8}"))
		{
			return d.substring(4, 8) + "-" + d.substring(2, 4) + "-"
					+ d.substring(0, 2);
		}
		return d;
	}


	/** yyyy-MM-dd to the legacy PIC 9(8) DDMMYYYY value (0 when absent). */
	public static int toDdmmyyyy(String isoDate)
	{
		int[] p = dateParts(isoDate);
		return p[0] * 1_000_000 + p[1] * 10_000 + p[2];
	}


	static String toDdmmyyyyString(String isoDate)
	{
		return String.format("%08d", toDdmmyyyy(isoDate));
	}


	/** {dd, mm, yyyy}; zeros when the date is absent or unparseable. */
	static int[] dateParts(String isoDate)
	{
		if (isoDate == null || !isoDate.trim().matches("\\d{4}-\\d{2}-\\d{2}"))
		{
			return new int[]
			{ 0, 0, 0 };
		}
		String d = isoDate.trim();
		return new int[]
		{ Integer.parseInt(d.substring(8, 10)),
				Integer.parseInt(d.substring(5, 7)),
				Integer.parseInt(d.substring(0, 4)) };
	}


	static long toLong(String value)
	{
		if (value == null || value.isBlank())
		{
			return 0L;
		}
		try
		{
			return Long.parseLong(value.trim());
		}
		catch (NumberFormatException e)
		{
			return 0L;
		}
	}


	static int toInt(String value)
	{
		if (value == null || value.isBlank())
		{
			return 0;
		}
		try
		{
			return Integer.parseInt(value.trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}


	private static double toDouble(BigDecimal value)
	{
		return value == null ? 0d : value.doubleValue();
	}


	private static int toWhole(BigDecimal value)
	{
		return value == null ? 0 : value.intValue();
	}


	private static String trim(String value)
	{
		return value == null ? null : value.trim();
	}
}
