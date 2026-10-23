/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.paymentinterface.controllers;

import com.ibm.cics.cip.bank.springboot.paymentinterface.CbsaCoreClient;
import com.ibm.cics.cip.bank.springboot.paymentinterface.jsonclasses.paymentinterface.PaymentInterfaceJson;
import com.ibm.cics.cip.bank.springboot.paymentinterface.jsonclasses.paymentinterface.TransferForm;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Payment interface form. Sends a DBCRFUN PAYDBCR COMMAREA to
 * cbsa-core-service ({@code PUT /webui-1.0/makepayment/dbcr}) and maps
 * COMM-FAIL-CODE 1/3/4 to the legacy rejection messages.
 */
@Controller
public class WebController implements WebMvcConfigurer
{

	private static final Logger log = LoggerFactory
			.getLogger(WebController.class);

	private static final String FORM_NAME = "paymentInterfaceForm";

	private static final String LARGE_TEXT = "largeText";

	private static final String SMALL_TEXT = "smallText";

	private static final String PAYMENT_ERROR = "Payment Error";

	private final CbsaCoreClient cbsaCoreClient;


	public WebController(CbsaCoreClient cbsaCoreClient)
	{
		this.cbsaCoreClient = cbsaCoreClient;
	}


	// Payment Interface
	@GetMapping(value =
	{ "/", "" })
	public String showForm(TransferForm personForm)
	{
		return FORM_NAME;
	}


	@PostMapping("/paydbcr")
	public String checkPersonInfo(@Valid TransferForm transferForm,
			BindingResult bindingResult, Model model)
	{
		// The same page is returned, this time with the errors given as an
		// object.
		if (bindingResult.hasErrors())
		{
			return FORM_NAME;
		}

		PaymentInterfaceJson transferjson = new PaymentInterfaceJson(
				transferForm);
		log.info("{}", transferjson);

		try
		{
			PaymentInterfaceJson responseObj = cbsaCoreClient
					.makePayment(transferjson);
			log.info("{}", responseObj);

			// Throws out different exceptions depending on the contents
			checkIfResponseValidDbcr(responseObj);

			// If successful...
			if (transferForm.isDebit())
			{
				model.addAttribute(LARGE_TEXT, "Payment Successful");
			}
			else
			{
				model.addAttribute(LARGE_TEXT, "Credit Successful");
			}
			model.addAttribute(SMALL_TEXT,
					("Value: " + responseObj.getPAYDBCR().getCommAmt()));
		}
		catch (AccountNotFoundException | InsufficientFundsException
				| InvalidAccountTypeException e)
		{
			log.info(e.toString());
			model.addAttribute(LARGE_TEXT, PAYMENT_ERROR);
			model.addAttribute(SMALL_TEXT, e.getMessage());
		}
		catch (ResourceAccessException e)
		{
			log.info(e.toString());
			model.addAttribute(LARGE_TEXT, PAYMENT_ERROR);
			model.addAttribute(SMALL_TEXT,
					"Connection refused or failed to resolve; Are you using the right address and port? Is the server running?");
		}
		catch (Exception e)
		{
			log.info(e.toString());
			model.addAttribute(LARGE_TEXT, PAYMENT_ERROR);
			model.addAttribute(SMALL_TEXT,
					"There was an error processing the request; Please try again later or check logs for more info.");
		}

		// The HTML template includes a clause to show the box for the results
		// if this is set to true(the page is otherwise the same)
		model.addAttribute("results", true);
		return FORM_NAME;
	}


	/**
	 * DBCRFUN COMM-FAIL-CODE: 1 account not found, 3 insufficient funds, 4
	 * MORTGAGE/LOAN cannot take payments; anything else is treated as success
	 * (as in the z/OS Connect version).
	 */
	public static void checkIfResponseValidDbcr(PaymentInterfaceJson response)
			throws InsufficientFundsException, InvalidAccountTypeException,
			AccountNotFoundException
	{
		String failCode = response.getPAYDBCR().getCommFailCode();
		switch (failCode == null ? "" : failCode.trim())
		{
		case "1":
			throw new AccountNotFoundException();
		case "3":
			throw new InsufficientFundsException();
		case "4":
			throw new InvalidAccountTypeException();
		default:
			break;
		}
	}
}


class InsufficientFundsException extends Exception
{



	/**
	 *
	 */
	private static final long serialVersionUID = 1L;


	public InsufficientFundsException()
	{
		super("Payment rejected: Insufficient funds.");
	}
}

class InvalidAccountTypeException extends Exception
{



	/**
	 *
	 */
	private static final long serialVersionUID = 1L;


	public InvalidAccountTypeException()
	{
		super("Payment rejected: Invalid account type.");
	}
}

class AccountNotFoundException extends Exception
{



	/**
	 *
	 */
	private static final long serialVersionUID = 1L;


	public AccountNotFoundException()
	{
		super("Payment rejected: Account does not exist.");
	}
}

class TooManyAccountsException extends Exception
{



	/**
	 *
	 */
	private static final long serialVersionUID = 1L;


	public TooManyAccountsException(int customerNumber)
	{
		super("Too many accounts for customer number " + customerNumber
				+ "; Try deleting an account first.");
	}
}

class ItemNotFoundException extends Exception
{


	/**
	 *
	 */
	private static final long serialVersionUID = 1L;


	public ItemNotFoundException(String item)
	{
		super("The " + item
				+ " you searched for could not be found; Try a different "
				+ item + " number.");
	}
}
