package com.ibm.cics.cip.bank.springboot.customerservices;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;

import com.ibm.cics.cip.bank.springboot.customerservices.controllers.WebController;
import com.ibm.cics.cip.bank.springboot.customerservices.core.CbsaCoreClient;
import java.net.ConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class CustomerServicesAdapterTest
{

	static final String BASE = "http://core.test:8080";

	static final String CUSTOMER_URL = BASE + "/webui-1.0/banking/customer";

	static final String ACCOUNT_URL = BASE + "/webui-1.0/banking/account";

	static final String CUSTOMER_JSON = """
			{"id":"0000000001","sortCode":"987654","customerName":"Mr Ab Smith",
			"customerAddress":"1 High St, Leeds","dateOfBirth":"1980-02-03",
			"customerCreditScore":"500","customerCreditScoreReviewDate":"2026-02-01"}""";

	static final String ACCOUNT_JSON = """
			{"id":"00000001","sortCode":"987654","customerNumber":"0000000001",
			"accountType":"ISA","interestRate":2.10,"overdraft":0,
			"availableBalance":100.00,"actualBalance":100.00,
			"dateOpened":"2020-01-01","lastStatementDate":"2025-12-01",
			"nextStatementDate":"2026-01-01"}""";

	static final String ERROR_MSG = "There was an error processing the request; Please try again later or check logs for more info.";

	static final String CONNECTION_ERROR_MSG = "Connection refused or failed to resolve; Are you using the right address and port? Is the server running?";

	private MockRestServiceServer server;

	private MockMvc mockMvc;


	@BeforeEach
	void setUp()
	{
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		CbsaCoreClient client = new CbsaCoreClient(builder,
				new ConnectionInfo(BASE, "/webui-1.0", "987654"));
		mockMvc = MockMvcBuilders.standaloneSetup(new WebController(client))
				.build();
	}


	static String error(String message, String failCode)
	{
		return failCode == null ? "{\"errorMessage\":\"" + message + "\"}"
				: "{\"errorMessage\":\"" + message + "\",\"failCode\":\""
						+ failCode + "\"}";
	}


	// INQACC
	@Test
	void enquireAccountCallsGetAccount() throws Exception
	{
		server.expect(requestTo(ACCOUNT_URL + "/00000001"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(ACCOUNT_JSON, MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/enqacct").param("acctNumber", "1"))
				.andExpect(status().isOk())
				.andExpect(model().attribute("largeText", "Account Details:"))
				.andExpect(model().attribute("smallText",
						containsString("ISA")))
				.andExpect(model().attribute("smallText",
						containsString("01/01/2020")));
		server.verify();
	}


	@Test
	void enquireAccountNotFound() throws Exception
	{
		server.expect(requestTo(ACCOUNT_URL + "/00000099"))
				.andRespond(withResourceNotFound()
						.body(error("Account number 99 not found.", null))
						.contentType(MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/enqacct").param("acctNumber", "99"))
				.andExpect(model().attribute("largeText", "Request Error"))
				.andExpect(model().attribute("smallText",
						"The account you searched for could not be found; Try a different account number."));
		server.verify();
	}


	@Test
	void enquireAccountConnectionRefused() throws Exception
	{
		server.expect(requestTo(ACCOUNT_URL + "/00000001"))
				.andRespond(withException(new ConnectException("refused")));
		mockMvc.perform(post("/enqacct").param("acctNumber", "1"))
				.andExpect(model().attribute("smallText", CONNECTION_ERROR_MSG));
	}


	@Test
	void enquireCustomerKeepsTenDigitNumbersAboveIntRange() throws Exception
	{
		server.expect(requestTo(CUSTOMER_URL + "/3000000000"))
				.andRespond(withSuccess(
						CUSTOMER_JSON.replace("0000000001", "3000000000"),
						MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/enqcust").param("custNumber", "3000000000"))
				.andExpect(model().attribute("smallText",
						containsString("3000000000")));
		server.verify();
	}


	// INQCUST
	@Test
	void enquireCustomerCallsGetCustomer() throws Exception
	{
		server.expect(requestTo(CUSTOMER_URL + "/0000000001"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(CUSTOMER_JSON, MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/enqcust").param("custNumber", "1"))
				.andExpect(model().attribute("largeText", "Customer Details"))
				.andExpect(model().attribute("smallText",
						containsString("Mr Ab Smith")));
		server.verify();
	}


	@Test
	void enquireCustomerNotFound() throws Exception
	{
		server.expect(requestTo(CUSTOMER_URL + "/0000000077"))
				.andRespond(withResourceNotFound()
						.body(error("Customer number 77 not found.", "1"))
						.contentType(MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/enqcust").param("custNumber", "77"))
				.andExpect(model().attribute("smallText",
						"The customer you searched for could not be found; Try a different customer number."));
		server.verify();
	}


	@Test
	void enquireCustomerServerErrorShowsGenericMessage() throws Exception
	{
		server.expect(requestTo(CUSTOMER_URL + "/0000000001"))
				.andRespond(withServerError());
		mockMvc.perform(post("/enqcust").param("custNumber", "1"))
				.andExpect(model().attribute("smallText", ERROR_MSG));
	}


	// INQACCCU
	@Test
	void listAccountsCallsRetrieveByCustomerNumber() throws Exception
	{
		server.expect(requestTo(
				ACCOUNT_URL + "/retrieveByCustomerNumber/0000000001"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(
						"{\"customerFound\":true,\"numberOfAccounts\":1,\"accounts\":["
								+ ACCOUNT_JSON + "]}",
						MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/listacc").param("custNumber", "1"))
				.andExpect(model().attribute("largeText",
						"Accounts belonging to customer 1:"))
				.andExpect(model().attribute("accounts", hasSize(1)));
		server.verify();
	}


	@Test
	void listAccountsCustomerNotFound() throws Exception
	{
		server.expect(requestTo(
				ACCOUNT_URL + "/retrieveByCustomerNumber/0000000005"))
				.andRespond(withResourceNotFound()
						.body(error("Customer number 5 not found.", "1"))
						.contentType(MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/listacc").param("custNumber", "5"))
				.andExpect(model().attribute("smallText",
						"The customer you searched for could not be found; Try a different customer number."));
		server.verify();
	}


	// CREACC
	@Test
	void createAccountPostsAccount() throws Exception
	{
		server.expect(requestTo(ACCOUNT_URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.customerNumber").value("0000000001"))
				.andExpect(jsonPath("$.accountType").value("ISA"))
				.andExpect(jsonPath("$.interestRate").value(2.1))
				.andExpect(jsonPath("$.overdraft").value(0))
				.andExpect(jsonPath("$.sortCode").value("987654"))
				.andRespond(withStatus(HttpStatus.CREATED).body(ACCOUNT_JSON)
						.contentType(MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/createacc").param("custNumber", "1")
				.param("accountType", "ISA").param("overdraftLimit", "0")
				.param("interestRate", "2.1"))
				.andExpect(model().attribute("largeText",
						"Account creation successful"));
		server.verify();
	}


	@Test
	void createAccountTooManyAccounts() throws Exception
	{
		server.expect(requestTo(ACCOUNT_URL)).andRespond(withBadRequest()
				.body(error("Customer number 1 cannot have more than ten accounts.", "8"))
				.contentType(MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/createacc").param("custNumber", "1")
				.param("accountType", "ISA").param("overdraftLimit", "0")
				.param("interestRate", "2.1"))
				.andExpect(model().attribute("largeText", "Account Error"))
				.andExpect(model().attribute("smallText",
						"Too many accounts for customer number 1; Try deleting an account first."));
		server.verify();
	}


	@Test
	void createAccountCustomerNotFound() throws Exception
	{
		server.expect(requestTo(ACCOUNT_URL)).andRespond(withResourceNotFound()
				.body(error("Customer number 9 not found.", "1"))
				.contentType(MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/createacc").param("custNumber", "9")
				.param("accountType", "ISA").param("overdraftLimit", "0")
				.param("interestRate", "2.1"))
				.andExpect(model().attribute("largeText", "Account Error"))
				.andExpect(model().attribute("smallText",
						"The customer you searched for could not be found; Try a different customer number."));
		server.verify();
	}


	// CRECUST
	@Test
	void createCustomerPostsCustomerWithIsoDob() throws Exception
	{
		server.expect(requestTo(CUSTOMER_URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.customerName").value("Mr Ab Smith"))
				.andExpect(jsonPath("$.customerAddress").value("1 High St, Leeds"))
				.andExpect(jsonPath("$.dateOfBirth").value("1980-02-03"))
				.andExpect(jsonPath("$.sortCode").value("987654"))
				.andRespond(withStatus(HttpStatus.CREATED).body(CUSTOMER_JSON)
						.contentType(MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/createcust").param("custName", "Mr Ab Smith")
				.param("custAddress", "1 High St, Leeds")
				.param("custDob", "1980-02-03"))
				.andExpect(model().attribute("largeText",
						"Customer creation successful"))
				.andExpect(model().attribute("smallText",
						containsString("0000000001")));
		server.verify();
	}


	@Test
	void createCustomerInvalidTitle() throws Exception
	{
		server.expect(requestTo(CUSTOMER_URL)).andRespond(withBadRequest()
				.body(error("Customer title Xx is not valid", "T"))
				.contentType(MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/createcust").param("custName", "Mr Ab Smith")
				.param("custAddress", "1 High St, Leeds")
				.param("custDob", "1980-02-03"))
				.andExpect(model().attribute("largeText", "Create Error"))
				.andExpect(model().attribute("smallText",
						"Invalid title; Valid titles are: Professor, Mr, Mrs, Miss, Ms, Dr, Drs, Lord, Sir or Lady."));
		server.verify();
	}


	// UPDACC
	@Test
	void updateAccountPutsAccount() throws Exception
	{
		server.expect(requestTo(ACCOUNT_URL + "/00000001"))
				.andExpect(method(HttpMethod.PUT))
				.andExpect(jsonPath("$.id").value("00000001"))
				.andExpect(jsonPath("$.accountType").value("SAVING"))
				.andExpect(jsonPath("$.interestRate").value(1.5))
				.andExpect(jsonPath("$.overdraft").value(100))
				.andExpect(jsonPath("$.sortCode").value("987654"))
				.andRespond(withSuccess(ACCOUNT_JSON.replace("ISA", "SAVING"),
						MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/updateacc").param("acctNumber", "1")
				.param("acctType", "SAVING").param("acctInterestRate", "1.5")
				.param("acctOverdraft", "100"))
				.andExpect(model().attribute("smallText",
						containsString("SAVING")));
		server.verify();
	}


	@Test
	void updateAccountNotFound() throws Exception
	{
		server.expect(requestTo(ACCOUNT_URL + "/00000042"))
				.andRespond(withResourceNotFound()
						.body(error("Account number 42 not found.", null))
						.contentType(MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/updateacc").param("acctNumber", "42")
				.param("acctType", "SAVING").param("acctInterestRate", "1.5")
				.param("acctOverdraft", "100"))
				.andExpect(model().attribute("largeText", "Update Error"))
				.andExpect(model().attribute("smallText",
						"The account you searched for could not be found; Try a different account number."));
		server.verify();
	}


	// UPDCUST
	@Test
	void updateCustomerPutsCustomer() throws Exception
	{
		server.expect(requestTo(CUSTOMER_URL + "/0000000001"))
				.andExpect(method(HttpMethod.PUT))
				.andExpect(jsonPath("$.customerName").value("Mrs Ab Smith"))
				.andExpect(jsonPath("$.customerAddress").value("2 Low St"))
				.andExpect(jsonPath("$.sortCode").value("987654"))
				.andRespond(withSuccess(
						CUSTOMER_JSON.replace("Mr Ab Smith", "Mrs Ab Smith"),
						MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/updatecust").param("custNumber", "1")
				.param("custName", "Mrs Ab Smith")
				.param("custAddress", "2 Low St"))
				.andExpect(model().attribute("largeText", "Customer updated"))
				.andExpect(model().attribute("smallText",
						containsString("Mrs Ab Smith")));
		server.verify();
	}


	@Test
	void updateCustomerNoNameNoAddress() throws Exception
	{
		server.expect(requestTo(CUSTOMER_URL + "/0000000001"))
				.andRespond(withBadRequest()
						.body(error("No name and no address supplied", "4"))
						.contentType(MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/updatecust").param("custNumber", "1")
				.param("custName", " ").param("custAddress", " "))
				.andExpect(model().attribute("largeText", "Update Error"))
				.andExpect(model().attribute("smallText",
						"No name and no address supplied. (Are there spaces before both the name and the address?)"));
		server.verify();
	}


	@Test
	void updateCustomerNotFound() throws Exception
	{
		server.expect(requestTo(CUSTOMER_URL + "/0000000003"))
				.andRespond(withResourceNotFound()
						.body(error("Customer number 3 not found.", "1"))
						.contentType(MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/updatecust").param("custNumber", "3")
				.param("custName", "Mr X").param("custAddress", "Y"))
				.andExpect(model().attribute("smallText",
						"The customer you searched for could not be found; Try a different customer number."));
		server.verify();
	}


	// DELACC
	@Test
	void deleteAccountCallsDelete() throws Exception
	{
		server.expect(requestTo(ACCOUNT_URL + "/00000001"))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withSuccess(ACCOUNT_JSON, MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/delacct").param("acctNumber", "1"))
				.andExpect(model().attribute("largeText", "Account Deleted"));
		server.verify();
	}


	@Test
	void deleteAccountNotFound() throws Exception
	{
		server.expect(requestTo(ACCOUNT_URL + "/00000002"))
				.andRespond(withResourceNotFound()
						.body(error("Account number 2 not found.", "1"))
						.contentType(MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/delacct").param("acctNumber", "2"))
				.andExpect(model().attribute("smallText",
						"The account you searched for could not be found; Try a different account number."));
		server.verify();
	}


	// DELCUS
	@Test
	void deleteCustomerCallsDelete() throws Exception
	{
		server.expect(requestTo(CUSTOMER_URL + "/0000000001"))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withSuccess(CUSTOMER_JSON, MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/delcust").param("custNumber", "1"))
				.andExpect(model().attribute("largeText",
						"Customer and associated accounts Deleted"));
		server.verify();
	}


	@Test
	void deleteCustomerNotFound() throws Exception
	{
		server.expect(requestTo(CUSTOMER_URL + "/0000000004"))
				.andRespond(withResourceNotFound()
						.body(error("Customer number 4 not found.", "1"))
						.contentType(MediaType.APPLICATION_JSON));
		mockMvc.perform(post("/delcust").param("custNumber", "4"))
				.andExpect(model().attribute("smallText",
						"The customer you searched for could not be found; Try a different customer number."));
		server.verify();
	}
}
