/*
 *
 *    Copyright IBM Corp. 2023
 *
 *
 */
package com.ibm.cics.cip.bank.springboot.customerservices.controllers;

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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.ibm.cics.cip.bank.springboot.customerservices.core.CbsaCoreClient;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.accountenquiry.AccountEnquiryForm;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.accountenquiry.AccountEnquiryJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.createaccount.AccountType;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.createaccount.CreateAccountForm;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.createaccount.CreateAccountJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.createcustomer.CreateCustomerForm;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.createcustomer.CreateCustomerJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.customerenquiry.CustomerEnquiryForm;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.customerenquiry.CustomerEnquiryJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.deleteaccount.DeleteAccountJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.deletecustomer.DeleteCustomerJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.listaccounts.ListAccJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.updateaccount.UpdateAccountForm;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.updateaccount.UpdateAccountJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.updatecustomer.UpdateCustomerForm;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.updatecustomer.UpdateCustomerJson;

// Each action calls cbsa-core-service through CbsaCoreClient, which maps the
// /banking/customer and /banking/account JSON back onto the legacy COMMAREA
// classes, so the fail-code checks and messages below are unchanged.

@Controller
public class WebController implements WebMvcConfigurer
{

	static final String COPYRIGHT = "Copyright IBM Corp. 2022";

	private static final String ACCOUNT_ENQUIRY_FORM = "accountEnquiryForm";

	private static final String CUSTOMER_ENQUIRY_FORM = "customerEnquiryForm";

	private static final String LIST_ACCOUNTS_FORM = "listAccountsForm";

	private static final String CREATE_ACCOUNT_FORM = "createAccountForm";

	private static final String CREATE_CUSTOMER_FORM = "createCustomerForm";

	private static final String UPDATE_ACCOUNT_FORM = "updateAccountForm";

	private static final String UPDATE_CUSTOMER_FORM = "updateCustomerForm";

	private static final String DELETE_ACCOUNT_FORM = "deleteAccountForm";

	private static final String DELETE_CUSTOMER_FORM = "deleteCustomerForm";

	private static final String LARGE_TEXT = "largeText";

	private static final String SMALL_TEXT = "smallText";

	private static final String REQUEST_ERROR = "Request Error";

	private static final String CONNECTION_ERROR_MSG = "Connection refused or failed to resolve; Are you using the right address and port? Is the server running?";

	private static final String CONNECTION_ERROR = "Connection Error";

	private static final String ERROR_MSG = "There was an error processing the request; Please try again later or check logs for more info.";

	private static final String RESULTS = "results";

	private static final String ACCOUNT = "account";

	private static final String CUSTOMER = "customer";

	private static final String ACCOUNT_TYPES = "accountTypes";


	private static final Logger log = LoggerFactory
			.getLogger(WebController.class);

	private final CbsaCoreClient cbsaCoreClient;


	public WebController(CbsaCoreClient cbsaCoreClient)
	{
		this.cbsaCoreClient = cbsaCoreClient;
	}


	// Customer and account services screen
	@GetMapping(value =
	{ "","/services", "/" })
	public String showCustServices(Model model)
	{

		model.addAttribute("contextPath", "");
		return "customerServices";
	}



	// These are numbered based on their actions; Only the first one is
	// commented, as the rest follow the same format.

	// 1. Enquire account


	// Get request for when first navigating to the page
	@GetMapping("/enqacct")
	public String showAcctForm(AccountEnquiryForm accountEnquiryForm)
	{
		// String relates to the page template found in
		// /src/main/resources/templates
		return ACCOUNT_ENQUIRY_FORM;
	}


	// When the Submit button is pressed, a Post request to the same location is
	// made
	// This function gets its arguments created using magic and the form
	// submitted
	@PostMapping("/enqacct")
	public String returnAcct(@Valid AccountEnquiryForm accountEnquiryForm,
			BindingResult bindingResult, Model model)
			throws JsonProcessingException
	{

		// model is passed to the template - it's used to link objects to fields
		// using model.addAttribute()

		// bindingResult generated by trying to place the fields in the
		// templates in the accountEnquiryForm class
		// If it returns with errors, the same page is shown but as there are
		// errors, extra columns are shown with the error message if applicable
		if (!bindingResult.hasErrors())
		{

			

			try
			{
				AccountEnquiryJson responseObj = cbsaCoreClient
					.inquireAccount(accountEnquiryForm.getAcctNumber());
				log.info("{}", responseObj);

				// Run through the checks on error codes in the method shown
				// directly below this and every other response method
				// The method throws exceptions based on the error type
				checkIfResponseValidListAcc(responseObj);

				// Set the fields that will be shown in the template. Either the
				// details of the response, or the details of the error.
				model.addAttribute(LARGE_TEXT, "Account Details:");
				model.addAttribute(SMALL_TEXT, responseObj.toPrettyString());
				model.addAttribute("success", true);
			}
			catch (ItemNotFoundException e)
			{
				log.info(e.toString());
				model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
				model.addAttribute(SMALL_TEXT, e.getMessage());
			}
			catch (ResourceAccessException e)
			{
				log.info(e.toString());
				model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
				model.addAttribute(SMALL_TEXT, CONNECTION_ERROR_MSG);
			}
			catch (Exception e)
			{
				log.info(e.toString());
				model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
				model.addAttribute(SMALL_TEXT, ERROR_MSG);
			}

			// There's a hidden box on all templates that displays the results -
			// it
			// depends on the results field below.
			model.addAttribute(RESULTS, true);

			// Return the same page with results now, so new enquiries can be
			// performed without going back.
		}
		return ACCOUNT_ENQUIRY_FORM;
	}


	// this one is a nested if statement, however most are case blocks instead.
	public static void checkIfResponseValidListAcc(AccountEnquiryJson response)
			throws ItemNotFoundException
	{
		if (response.getInqaccCommarea().getInaccSuccess().equals("N")
				&& response.getInqaccCommarea().getInqaccCustno() == 0)
		{
			throw new ItemNotFoundException(ACCOUNT);
		}
	}


	// 2. Enquire Customer
	@GetMapping("/enqcust")
	public String showCustForm(CustomerEnquiryForm customerEnquiryForm)
	{
		return CUSTOMER_ENQUIRY_FORM;
	}


	@PostMapping("/enqcust")
	public String returnCust(@Valid CustomerEnquiryForm customerEnquiryForm,
			BindingResult bindingResult, Model model)
			throws JsonProcessingException
	{
		if (!bindingResult.hasErrors())
		{

			

			try
			{
				CustomerEnquiryJson responseObj = cbsaCoreClient
					.inquireCustomer(customerEnquiryForm.getCustNumber());
				checkIfResponseValidEnqCust(responseObj);
				model.addAttribute(LARGE_TEXT, "Customer Details");
				model.addAttribute(SMALL_TEXT, responseObj.toPrettyString());
			}
			catch (ItemNotFoundException e)
			{
				log.info(e.toString());
				model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
				model.addAttribute(SMALL_TEXT, e.getMessage());
			}
			catch (ResourceAccessException e)
			{
				log.info(e.toString());
				model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
				model.addAttribute(SMALL_TEXT, CONNECTION_ERROR_MSG);
			}
			catch (Exception e)
			{
				log.info(e.toString());
				model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
				model.addAttribute(SMALL_TEXT, ERROR_MSG);
			}

			model.addAttribute(RESULTS, true);
		}
		return CUSTOMER_ENQUIRY_FORM;
	}


	public static void checkIfResponseValidEnqCust(CustomerEnquiryJson response)
			throws ItemNotFoundException
	{
		if (response.getInqCustZ().getInqcustInqSuccess().equals("N"))
		{
			throw new ItemNotFoundException(CUSTOMER);
		}
	}


	// 3. List all accounts belonging to a customer
	// Similar form to enqCust since we're still only asking for a customer
	// number
	@GetMapping("/listacc")
	public String showListAccForm(CustomerEnquiryForm customerEnquiryForm)
	{
		return LIST_ACCOUNTS_FORM;
	}


	@PostMapping("/listacc")
	public String returnListAcc(@Valid CustomerEnquiryForm customerEnquiryForm,
			BindingResult bindingResult, Model model)
			throws JsonProcessingException
	{
		if (!bindingResult.hasErrors())
		{

			

			try
			{
				ListAccJson responseObj = cbsaCoreClient
					.listAccounts(customerEnquiryForm.getCustNumber());
				log.info("{}", responseObj);
				checkIfResponseValidListAcc(responseObj);
				model.addAttribute(LARGE_TEXT, "Accounts belonging to customer "
						+ responseObj.getInqacccz().getCustomerNumber() + ":");
				model.addAttribute("accounts",
						responseObj.getInqacccz().getAccountDetails());
			}
			catch (ItemNotFoundException e)
			{
				log.info(e.toString());
				model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
				model.addAttribute(SMALL_TEXT, e.getMessage());
			}
			catch (ResourceAccessException e)
			{
				log.info(e.toString());
				model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
				model.addAttribute(SMALL_TEXT, CONNECTION_ERROR_MSG);
			}
			catch (Exception e)
			{
				log.info(e.toString());
				model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
				model.addAttribute(SMALL_TEXT, ERROR_MSG);
			}

			model.addAttribute(RESULTS, true);
		}
		return LIST_ACCOUNTS_FORM;
	}


	public static void checkIfResponseValidListAcc(ListAccJson response)
			throws ItemNotFoundException
	{
		if (response.getInqacccz().getCustomerFound().equals("N"))
		{
			throw new ItemNotFoundException(CUSTOMER);
		}
	}


	// 4. Create an account
	@GetMapping("/createacc")
	public String showCreateAccForm(CreateAccountForm createAccForm,
			Model model)
	{
		model.addAttribute(ACCOUNT_TYPES, AccountType.values());
		return CREATE_ACCOUNT_FORM;
	}


	@PostMapping("/createacc")
	public String processCreateAcc(@Valid CreateAccountForm createAccForm,
			BindingResult bindingResult, Model model)
			throws JsonProcessingException
	{
		if (bindingResult.hasErrors())
		{
			model.addAttribute(ACCOUNT_TYPES, AccountType.values());
			return CREATE_ACCOUNT_FORM;
		}

		

		try
		{
			CreateAccountJson responseObj = cbsaCoreClient
					.createAccount(createAccForm);
			log.info("{}", responseObj);

			// Throws out different exceptions depending on the contents
			checkIfResponseValidCreateAcc(responseObj);

			// If successful...
			model.addAttribute(LARGE_TEXT, "Account creation successful");
			model.addAttribute(SMALL_TEXT,
					("Details: " + responseObj.toPrettyString()));

			// Otherwise...
		}
		catch (TooManyAccountsException | ItemNotFoundException e)
		{
			log.info(e.toString());
			model.addAttribute(LARGE_TEXT, "Account Error");
			model.addAttribute(SMALL_TEXT, e.getMessage());
		}
		catch (ResourceAccessException e)
		{
			log.info(e.toString());
			model.addAttribute(LARGE_TEXT, CONNECTION_ERROR);
			model.addAttribute(SMALL_TEXT, CONNECTION_ERROR_MSG);
		}
		catch (Exception e)
		{
			log.info(e.toString());
			model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
			model.addAttribute(SMALL_TEXT, ERROR_MSG);
		}

		model.addAttribute(RESULTS, true);

		// If this isn't here, the radio buttons don't show as they're generated
		// using this enum
		model.addAttribute(ACCOUNT_TYPES, AccountType.values());

		return CREATE_ACCOUNT_FORM;
	}


	public static void checkIfResponseValidCreateAcc(
			CreateAccountJson responseObj)
			throws TooManyAccountsException, ItemNotFoundException
	{
		if (responseObj.getCreAcc().getCommSuccess().equals("N"))
		{
			if (responseObj.getCreAcc().getCommFailCode().equals("1"))
			{
				throw new ItemNotFoundException(CUSTOMER);
			}
			if (responseObj.getCreAcc().getCommFailCode().equals("8"))
			{
				throw new TooManyAccountsException(Integer
						.parseInt(responseObj.getCreAcc().getCommCustno()));
			}
			if (responseObj.getCreAcc().getCommFailCode().equals("A"))
			{
				throw new IllegalArgumentException(
						"Invalid account type supplied.");
			}
		}
	}


	// 5. Create a customer
	@GetMapping("/createcust")
	public String showCreateCustForm(CreateCustomerForm createCustForm,
			Model model)
	{
		return CREATE_CUSTOMER_FORM;
	}


	@PostMapping("/createcust")
	public String processCreateCust(@Valid CreateCustomerForm createCustForm,
			BindingResult bindingResult, Model model)
			throws JsonProcessingException
	{
		if (bindingResult.hasErrors())
		{
			return CREATE_CUSTOMER_FORM;
		}
		

		try
		{
			CreateCustomerJson responseObj = cbsaCoreClient
					.createCustomer(createCustForm);
			log.info("Response Json:\n{}", responseObj);

			// Throws out different exceptions depending on the contents
			checkIfResponseValidCreateCust(responseObj);

			// If successful...
			model.addAttribute(LARGE_TEXT, "Customer creation successful");
			model.addAttribute(SMALL_TEXT, (responseObj.toPrettyString()));

			// Otherwise...
		}
		catch (IllegalArgumentException e)
		{
			log.info(e.toString());
			model.addAttribute(LARGE_TEXT, "Create Error");
			model.addAttribute(SMALL_TEXT, e.getMessage());
		}
		catch (ResourceAccessException e)
		{
			log.info(e.toString());
			model.addAttribute(LARGE_TEXT, CONNECTION_ERROR);
			model.addAttribute(SMALL_TEXT, CONNECTION_ERROR_MSG);
		}
		catch (Exception e)
		{
			log.info(e.toString());
			model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
			model.addAttribute(SMALL_TEXT, ERROR_MSG);
		}

		model.addAttribute(RESULTS, true);

		return CREATE_CUSTOMER_FORM;
	}


	public static void checkIfResponseValidCreateCust(
			CreateCustomerJson responseObj) throws InvalidCustomerException,
			NumberFormatException, IllegalArgumentException
	{
		if (!responseObj.getCreCust().getCommFailCode().equals(""))
		{
			if (responseObj.getCreCust().getCommFailCode().equals("T"))
			{
				throw new IllegalArgumentException(
						"Invalid title; Valid titles are: Professor, Mr, Mrs, Miss, Ms, Dr, Drs, Lord, Sir or Lady.");
			}

			throw new InvalidCustomerException("An unexpected error occured");
		}

	}


	// 6. Update an account
	@GetMapping("/updateacc")
	public String showUpdateAccountForm(UpdateAccountForm updateAccForm,
			Model model)
	{

		// This links the radio buttons on the template to the AccountType enum
		model.addAttribute(ACCOUNT_TYPES, AccountType.values());
		return UPDATE_ACCOUNT_FORM;
	}


	@PostMapping("/updateacc")
	public String processCreateAcc(@Valid UpdateAccountForm updateAccountForm,
			BindingResult bindingResult, Model model)
			throws JsonProcessingException
	{
		if (bindingResult.hasErrors())
		{

			// Must add the accountTypes enum here as well, otherwise the radio
			// buttons disappear on error
			model.addAttribute(ACCOUNT_TYPES, AccountType.values());
			return UPDATE_ACCOUNT_FORM;
		}
		

		try
		{
			UpdateAccountJson responseObj = cbsaCoreClient
					.updateAccount(updateAccountForm);
			log.info("{}", responseObj);

			// Throws out different exceptions depending on the contents
			checkIfResponseValidUpdateAcc(responseObj);

			// If successful...
			model.addAttribute(LARGE_TEXT, "");
			model.addAttribute(SMALL_TEXT, responseObj.toPrettyString());

			// Otherwise...
		}
		catch (ItemNotFoundException e)
		{
			log.info(e.toString());
			model.addAttribute(LARGE_TEXT, "Update Error");
			model.addAttribute(SMALL_TEXT, e.getMessage());
		}
		catch (ResourceAccessException e)
		{
			log.info(e.toString());
			model.addAttribute(LARGE_TEXT, CONNECTION_ERROR);
			model.addAttribute(SMALL_TEXT, CONNECTION_ERROR_MSG);
		}
		catch (Exception e)
		{
			log.info(e.toString());
			model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
			model.addAttribute(SMALL_TEXT, ERROR_MSG);
		}

		model.addAttribute(RESULTS, true);

		// If this isn't here, the radio buttons don't show as they're generated
		// using this enum
		model.addAttribute(ACCOUNT_TYPES, AccountType.values());

		return UPDATE_ACCOUNT_FORM;
	}


	public static void checkIfResponseValidUpdateAcc(
			UpdateAccountJson responseObj) throws ItemNotFoundException
	{
		if (responseObj.getUpdacc().getCommSuccess().equals("N"))
		{
			throw new ItemNotFoundException(ACCOUNT);
		}
	}


	// 6. Update a customer
	@GetMapping("/updatecust")
	public String showUpdateAccountForm(UpdateCustomerForm updateCustomerForm,
			Model model)
	{
		model.addAttribute(ACCOUNT_TYPES, AccountType.values());
		return UPDATE_CUSTOMER_FORM;
	}


	@PostMapping("/updatecust")
	public String processUpdateCust(
			@Valid UpdateCustomerForm updateCustomerForm,
			BindingResult bindingResult, Model model)
			throws JsonProcessingException
	{
		if (bindingResult.hasErrors())
		{
			return UPDATE_CUSTOMER_FORM;
		}
		

		try
		{
			UpdateCustomerJson responseObj = cbsaCoreClient
					.updateCustomer(updateCustomerForm);
			log.info("{}", responseObj);

			// Throws out different exceptions depending on the contents
			checkIfResponseValidUpdateCust(responseObj);

			// If successful...
			model.addAttribute(LARGE_TEXT, "Customer updated");
			model.addAttribute(SMALL_TEXT, responseObj.toPrettyString());

			// Otherwise...
		}
		catch (ItemNotFoundException | IllegalArgumentException e)
		{
			log.info(e.toString());
			model.addAttribute(LARGE_TEXT, "Update Error");
			model.addAttribute(SMALL_TEXT, e.getMessage());
		}
		catch (ResourceAccessException e)
		{
			log.info(e.toString());
			model.addAttribute(LARGE_TEXT, CONNECTION_ERROR);
			model.addAttribute(SMALL_TEXT, CONNECTION_ERROR_MSG);
		}
		catch (Exception e)
		{
			log.info(e.toString());
			model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
			model.addAttribute(SMALL_TEXT, ERROR_MSG);
		}

		model.addAttribute(RESULTS, true);

		return UPDATE_CUSTOMER_FORM;
	}


	public static void checkIfResponseValidUpdateCust(
			UpdateCustomerJson responseObj)
			throws ItemNotFoundException, IllegalArgumentException
	{
		if (responseObj.getUpdcust().getCommUpdateSuccess().equals("N"))
		{
			if (responseObj.getUpdcust().getCommUpdateFailCode().equals("4"))
			{
				throw new IllegalArgumentException(
						"No name and no address supplied. (Are there spaces before both the name and the address?)");
			}
			if (responseObj.getUpdcust().getCommUpdateFailCode().equals("T"))
			{
				throw new IllegalArgumentException(
						"Invalid title; Valid titles are: Professor, Mr, Mrs, Miss, Ms, Dr, Drs, Lord, Sir or Lady.");
			}
			throw new ItemNotFoundException(CUSTOMER);
		}
	}


	// 8. Delete an account
	@GetMapping("/delacct")
	public String showDelAcctForm(AccountEnquiryForm accountEnquiryForm)
	{
		return DELETE_ACCOUNT_FORM;
	}


	@PostMapping("/delacct")
	public String deleteAcct(@Valid AccountEnquiryForm accountEnquiryForm,
			BindingResult bindingResult, Model model)
			throws JsonProcessingException
	{
		if (!bindingResult.hasErrors())
		{
			

			try
			{
				DeleteAccountJson responseObj = cbsaCoreClient
					.deleteAccount(accountEnquiryForm.getAcctNumber());
				log.info("{}", responseObj);
				checkIfResponseValidDeleteAcc(responseObj);
				model.addAttribute(LARGE_TEXT, "Account Deleted");
				model.addAttribute(SMALL_TEXT, responseObj.toPrettyString());
			}
			catch (ItemNotFoundException e)
			{
				log.info(e.toString());
				model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
				model.addAttribute(SMALL_TEXT, e.getMessage());
			}
			catch (ResourceAccessException e)
			{
				log.info(e.toString());
				model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
				model.addAttribute(SMALL_TEXT, CONNECTION_ERROR_MSG);
			}
			catch (Exception e)
			{
				log.info(e.toString());
				model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
				model.addAttribute(SMALL_TEXT, ERROR_MSG);
			}

			model.addAttribute(RESULTS, true);
		}
		return DELETE_ACCOUNT_FORM;
	}


	public static void checkIfResponseValidDeleteAcc(
			DeleteAccountJson responseObj) throws ItemNotFoundException
	{
		if (responseObj.getDelaccCommarea().getDelaccDelFailCode() == 1)
		{
			throw new ItemNotFoundException(ACCOUNT);
		}
	}


	// 9. Delete a customer
	@GetMapping("/delcust")
	public String showDelCustForm(CustomerEnquiryForm customerEnquiryForm)
	{
		return DELETE_CUSTOMER_FORM;
	}


	@PostMapping("/delcust")
	public String deleteCust(@Valid CustomerEnquiryForm customerEnquiryForm,
			BindingResult bindingResult, Model model)
			throws JsonProcessingException
	{
		if (!bindingResult.hasErrors())
		{
			

			try
			{
				DeleteCustomerJson responseObj = cbsaCoreClient
					.deleteCustomer(customerEnquiryForm.getCustNumber());
				log.info("{}", responseObj);
				checkIfResponseValidDeleteCust(responseObj);
				model.addAttribute(LARGE_TEXT,
						"Customer and associated accounts Deleted");
				model.addAttribute(SMALL_TEXT, responseObj.toPrettyString());
			}
			catch (ItemNotFoundException e)
			{
				log.info(e.toString());
				model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
				model.addAttribute(SMALL_TEXT, e.getMessage());
			}
			catch (ResourceAccessException e)
			{
				log.info(e.toString());
				model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
				model.addAttribute(SMALL_TEXT, CONNECTION_ERROR_MSG);
			}
			catch (Exception e)
			{
				log.info(e.toString());
				model.addAttribute(LARGE_TEXT, REQUEST_ERROR);
				model.addAttribute(SMALL_TEXT, ERROR_MSG);
			}

			model.addAttribute(RESULTS, true);
		}
		return DELETE_CUSTOMER_FORM;
	}


	public static void checkIfResponseValidDeleteCust(
			DeleteCustomerJson responseObj) throws ItemNotFoundException
	{
		if (responseObj.getDelcus().getCommDelFailCode() == 1)
		{
			throw new ItemNotFoundException(CUSTOMER);
		}
	}
}

class InsufficientFundsException extends Exception
{

	/**
	 *
	 */
	private static final long serialVersionUID = 2916294528612553278L;

	static final String COPYRIGHT = "Copyright IBM Corp. 2022";


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
	private static final long serialVersionUID = -3342099995389507130L;

	static final String COPYRIGHT = "Copyright IBM Corp. 2022";


	public InvalidAccountTypeException()
	{

		super("Payment rejected: Invalid account type.");
	}
}

class TooManyAccountsException extends Exception
{

	/**
	 *
	 */
	private static final long serialVersionUID = -3421012321723845378L;

	static final String COPYRIGHT = "Copyright IBM Corp. 2022";


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
	private static final long serialVersionUID = -3570840021629249034L;

	static final String COPYRIGHT = "Copyright IBM Corp. 2022";


	public ItemNotFoundException(String item)
	{

		super("The " + item
				+ " you searched for could not be found; Try a different "
				+ item + " number.");
	}
}
