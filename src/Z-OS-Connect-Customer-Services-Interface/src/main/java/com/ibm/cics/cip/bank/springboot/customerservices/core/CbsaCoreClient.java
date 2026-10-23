/*
 *
 *    Copyright IBM Corp. 2023
 *
 *
 */
package com.ibm.cics.cip.bank.springboot.customerservices.core;

import com.ibm.cics.cip.bank.springboot.customerservices.ConnectionInfo;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.accountenquiry.AccountEnquiryJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.accountenquiry.InqaccJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.createaccount.CreateAccountForm;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.createaccount.CreateAccountJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.createaccount.CreaccJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.createcustomer.CreateCustomerForm;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.createcustomer.CreateCustomerJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.createcustomer.CrecustJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.customerenquiry.CustomerEnquiryJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.customerenquiry.InqCustZJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.deleteaccount.DelaccJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.deleteaccount.DeleteAccountJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.deletecustomer.DelcusJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.deletecustomer.DeleteCustomerJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.listaccounts.InqAccczJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.listaccounts.ListAccJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.updateaccount.UpdaccJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.updateaccount.UpdateAccountForm;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.updateaccount.UpdateAccountJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.updatecustomer.UpdateCustomerForm;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.updatecustomer.UpdateCustomerJson;
import com.ibm.cics.cip.bank.springboot.customerservices.jsonclasses.updatecustomer.UpdcustJson;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Client for the cbsa-core-service {@code /banking/customer} and
 * {@code /banking/account} endpoints, replacing the legacy z/OS Connect APIs
 * ({@code /inqcustz}, {@code /inqaccz}, {@code /inqacccz}, {@code /crecust},
 * {@code /creacc}, {@code /updcust}, {@code /updacc}, {@code /delcus},
 * {@code /delacc}).
 * <p>
 * Each method returns the legacy COMMAREA JSON class the controller already
 * understands ({@link LegacyMapper}). Business errors that the legacy program
 * reported in the COMMAREA (4xx with the matching fail code) are turned back
 * into a COMMAREA with success flag {@code N}, so the controller's existing
 * fail-code checks and messages are unchanged. Anything else propagates:
 * {@link org.springframework.web.client.ResourceAccessException} when the
 * service is unreachable, {@link org.springframework.web.client.RestClientResponseException}
 * for other HTTP errors.
 */
@Component
public class CbsaCoreClient
{

	static final String CUSTOMER = "/banking/customer";

	static final String ACCOUNT = "/banking/account";

	private static final Logger log = LoggerFactory
			.getLogger(CbsaCoreClient.class);

	private final RestClient restClient;

	private final String sortCode;


	public CbsaCoreClient(RestClient.Builder builder,
			ConnectionInfo connectionInfo)
	{
		this.restClient = builder.baseUrl(connectionInfo.getAddressAndPort())
				.defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
				.build();
		this.sortCode = connectionInfo.getSortCode();
	}


	/** INQACC: GET /banking/account/{id}. */
	public AccountEnquiryJson inquireAccount(String accountNumber)
	{
		String id = LegacyMapper.padAccount(accountNumber);
		try
		{
			CoreAccount account = restClient.get().uri(ACCOUNT + "/{id}", id)
					.retrieve().body(CoreAccount.class);
			return LegacyMapper.toAccountEnquiry(account);
		}
		catch (HttpClientErrorException e)
		{
			rethrowUnless(e, HttpStatus.NOT_FOUND);
			AccountEnquiryJson json = new AccountEnquiryJson();
			InqaccJson inqacc = new InqaccJson();
			inqacc.setInqaccSuccess("N");
			inqacc.setInqaccCustno(0);
			json.setInqaccCommarea(inqacc);
			return json;
		}
	}


	/** INQCUST: GET /banking/customer/{id}. */
	public CustomerEnquiryJson inquireCustomer(String customerNumber)
	{
		String id = LegacyMapper.padCustomer(customerNumber);
		try
		{
			CoreCustomer customer = restClient.get()
					.uri(CUSTOMER + "/{id}", id).retrieve()
					.body(CoreCustomer.class);
			return LegacyMapper.toCustomerEnquiry(customer);
		}
		catch (HttpClientErrorException e)
		{
			rethrowUnless(e, HttpStatus.NOT_FOUND);
			CustomerEnquiryJson json = new CustomerEnquiryJson();
			InqCustZJson inqcust = new InqCustZJson();
			inqcust.setInqcustInqSuccess("N");
			inqcust.setInqcustInqFailCode(1);
			json.setInqCustZ(inqcust);
			return json;
		}
	}


	/** INQACCCU: GET /banking/account/retrieveByCustomerNumber/{id}. */
	public ListAccJson listAccounts(String customerNumber)
	{
		String id = LegacyMapper.padCustomer(customerNumber);
		try
		{
			CoreAccountList list = restClient.get()
					.uri(ACCOUNT + "/retrieveByCustomerNumber/{id}", id)
					.retrieve().body(CoreAccountList.class);
			return LegacyMapper.toListAccounts(id, list);
		}
		catch (HttpClientErrorException e)
		{
			rethrowUnless(e, HttpStatus.NOT_FOUND);
			ListAccJson json = new ListAccJson();
			InqAccczJson inqacccz = new InqAccczJson();
			inqacccz.setCustomerNumber(LegacyMapper.toInt(id));
			inqacccz.setCustomerFound("N");
			inqacccz.setCommSuccess("N");
			inqacccz.setCommFailCode(1);
			json.setInqacccz(inqacccz);
			return json;
		}
	}


	/** CREACC: POST /banking/account. */
	public CreateAccountJson createAccount(CreateAccountForm form)
	{
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("customerNumber",
				LegacyMapper.padCustomer(form.getCustNumber()));
		body.put("accountType", form.getAccountType() == null ? null
				: form.getAccountType().name());
		body.put("interestRate",
				new BigDecimal(Float.toString(form.getInterestRate())));
		body.put("overdraft", form.getOverdraftLimit());
		body.put("sortCode", sortCode);
		log.info("POST {} {}", ACCOUNT, body);
		try
		{
			CoreAccount account = restClient.post().uri(ACCOUNT)
					.contentType(MediaType.APPLICATION_JSON).body(body)
					.retrieve().body(CoreAccount.class);
			return LegacyMapper.toCreateAccount(account);
		}
		catch (HttpClientErrorException e)
		{
			String failCode = failCode(e);
			if (!Set.of("1", "8", "A").contains(failCode))
			{
				throw e;
			}
			CreateAccountJson json = new CreateAccountJson();
			CreaccJson creacc = new CreaccJson();
			creacc.setCommCustno(
					LegacyMapper.padCustomer(form.getCustNumber()));
			creacc.setCommSuccess("N");
			creacc.setCommFailCode(failCode);
			json.setCreAcc(creacc);
			return json;
		}
	}


	/** CRECUST: POST /banking/customer. */
	public CreateCustomerJson createCustomer(CreateCustomerForm form)
	{
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("customerName", form.getCustName());
		body.put("customerAddress", form.getCustAddress());
		body.put("dateOfBirth", LegacyMapper.toIsoDate(form.getCustDob()));
		body.put("sortCode", sortCode);
		log.info("POST {} {}", CUSTOMER, body);
		try
		{
			CoreCustomer customer = restClient.post().uri(CUSTOMER)
					.contentType(MediaType.APPLICATION_JSON).body(body)
					.retrieve().body(CoreCustomer.class);
			return LegacyMapper.toCreateCustomer(customer);
		}
		catch (HttpClientErrorException e)
		{
			String failCode = failCode(e);
			if (failCode.isEmpty())
			{
				throw e;
			}
			CreateCustomerJson json = new CreateCustomerJson();
			CrecustJson crecust = new CrecustJson();
			crecust.setCommSuccess("N");
			crecust.setCommFailCode(failCode);
			json.setCreCust(crecust);
			return json;
		}
	}


	/** UPDACC: PUT /banking/account/{id}. */
	public UpdateAccountJson updateAccount(UpdateAccountForm form)
	{
		String id = LegacyMapper
				.padAccount(Integer.toString(form.getAcctNumber()));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", id);
		body.put("customerNumber", form.getCustNumber() == null
				|| form.getCustNumber().isBlank() ? null
						: LegacyMapper.padCustomer(form.getCustNumber()));
		body.put("sortCode", sortCode);
		body.put("accountType", form.getAcctType() == null ? null
				: form.getAcctType().name());
		body.put("interestRate",
				new BigDecimal(Float.toString(form.getAcctInterestRateFloat())));
		body.put("overdraft", form.getAcctOverdraftInt());
		log.info("PUT {}/{} {}", ACCOUNT, id, body);
		try
		{
			CoreAccount account = restClient.put().uri(ACCOUNT + "/{id}", id)
					.contentType(MediaType.APPLICATION_JSON).body(body)
					.retrieve().body(CoreAccount.class);
			return LegacyMapper.toUpdateAccount(account);
		}
		catch (HttpClientErrorException e)
		{
			// UPDACC reported every rejection as COMM-SUCCESS 'N'
			UpdateAccountJson json = new UpdateAccountJson();
			UpdaccJson updacc = new UpdaccJson();
			updacc.setCommAccno(form.getAcctNumber());
			updacc.setCommSuccess("N");
			json.setUpdacc(updacc);
			return json;
		}
	}


	/** UPDCUST: PUT /banking/customer/{id}. */
	public UpdateCustomerJson updateCustomer(UpdateCustomerForm form)
	{
		String id = LegacyMapper.padCustomer(form.getCustNumber());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("customerName",
				form.getCustName() == null ? "" : form.getCustName());
		body.put("customerAddress",
				form.getCustAddress() == null ? "" : form.getCustAddress());
		body.put("sortCode", sortCode);
		log.info("PUT {}/{} {}", CUSTOMER, id, body);
		try
		{
			CoreCustomer customer = restClient.put()
					.uri(CUSTOMER + "/{id}", id)
					.contentType(MediaType.APPLICATION_JSON).body(body)
					.retrieve().body(CoreCustomer.class);
			return LegacyMapper.toUpdateCustomer(customer);
		}
		catch (HttpClientErrorException e)
		{
			UpdateCustomerJson json = new UpdateCustomerJson();
			UpdcustJson updcust = new UpdcustJson();
			updcust.setCommCustno(id);
			updcust.setCommUpdateSuccess("N");
			updcust.setCommUpdateFailCode(failCode(e));
			json.setUpdcust(updcust);
			return json;
		}
	}


	/** DELACC: DELETE /banking/account/{id}. */
	public DeleteAccountJson deleteAccount(String accountNumber)
	{
		String id = LegacyMapper.padAccount(accountNumber);
		try
		{
			CoreAccount account = restClient.delete()
					.uri(ACCOUNT + "/{id}", id).retrieve()
					.body(CoreAccount.class);
			return LegacyMapper.toDeleteAccount(account);
		}
		catch (HttpClientErrorException e)
		{
			rethrowUnless(e, HttpStatus.NOT_FOUND);
			DeleteAccountJson json = new DeleteAccountJson();
			DelaccJson delacc = new DelaccJson();
			delacc.setDelaccAccno(LegacyMapper.toInt(id));
			delacc.setDelaccDelSuccess("N");
			delacc.delaccDelFailCode(1);
			delacc.setDelaccFailCode(1);
			json.setDelaccCommarea(delacc);
			return json;
		}
	}


	/** DELCUS: DELETE /banking/customer/{id} (accounts are deleted first). */
	public DeleteCustomerJson deleteCustomer(String customerNumber)
	{
		String id = LegacyMapper.padCustomer(customerNumber);
		try
		{
			CoreCustomer customer = restClient.delete()
					.uri(CUSTOMER + "/{id}", id).retrieve()
					.body(CoreCustomer.class);
			return LegacyMapper.toDeleteCustomer(customer);
		}
		catch (HttpClientErrorException e)
		{
			rethrowUnless(e, HttpStatus.NOT_FOUND);
			DeleteCustomerJson json = new DeleteCustomerJson();
			DelcusJson delcus = new DelcusJson();
			delcus.setCommCustno(LegacyMapper.toInt(id));
			delcus.setCommDelSuccess("N");
			delcus.setCommDelFailCode(1);
			json.setDelcus(delcus);
			return json;
		}
	}


	private static void rethrowUnless(HttpClientErrorException e,
			HttpStatus expected)
	{
		if (e.getStatusCode().value() != expected.value())
		{
			throw e;
		}
	}


	private static String failCode(HttpClientErrorException e)
	{
		CoreError error;
		try
		{
			error = e.getResponseBodyAs(CoreError.class);
		}
		catch (RuntimeException parseFailure)
		{
			error = null;
		}
		String code = error == null ? "" : error.failCodeOrEmpty();
		if (code.isEmpty() && e.getStatusCode().value() == 404)
		{
			return "1";
		}
		return code;
	}
}
