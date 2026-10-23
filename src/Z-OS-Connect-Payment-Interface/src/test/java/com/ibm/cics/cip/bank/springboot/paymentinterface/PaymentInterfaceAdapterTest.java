package com.ibm.cics.cip.bank.springboot.paymentinterface;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.ibm.cics.cip.bank.springboot.paymentinterface.controllers.ParamsController;
import com.ibm.cics.cip.bank.springboot.paymentinterface.controllers.WebController;
import java.io.IOException;
import java.net.ConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class PaymentInterfaceAdapterTest
{

	static final String BASE = "http://core.test:8080";

	static final String DBCR_URL = BASE + "/webui-1.0/makepayment/dbcr";

	private MockRestServiceServer server;

	private MockMvc mockMvc;


	@BeforeEach
	void setUp()
	{
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		CbsaCoreClient client = new CbsaCoreClient(builder,
				new ConnectionInfo(BASE + "/", "/webui-1.0"));
		mockMvc = MockMvcBuilders
				.standaloneSetup(new WebController(client),
						new ParamsController(client))
				.build();
	}


	static String paydbcr(String accno, String amt, String success,
			String failCode)
	{
		return """
				{"PAYDBCR":{"CommAccno":"%s","CommAmt":%s,"mSortC":987654,
				"CommAvBal":0,"CommActBal":0,
				"CommOrigin":{"CommApplid":"PAYAPP","CommUserid":"ACME",
				"CommFacilityName":"        ","CommNetwrkId":"        ",
				"CommFaciltype":496,"Fill0":"    "},
				"CommSuccess":"%s","CommFailCode":"%s"}}"""
				.formatted(accno, amt, success, failCode);
	}


	@Test
	void debitFormPutsPaydbcrToCoreServiceAndReportsSuccess() throws Exception
	{
		server.expect(requestTo(DBCR_URL))
				.andExpect(method(HttpMethod.PUT))
				.andExpect(header("Content-Type",
						MediaType.APPLICATION_JSON_VALUE))
				.andExpect(jsonPath("$.PAYDBCR.CommAccno").value("00000001"))
				.andExpect(jsonPath("$.PAYDBCR.CommAmt").value(-25.0))
				.andExpect(jsonPath("$.PAYDBCR.CommOrigin.CommApplid")
						.value("PAYAPP  "))
				.andExpect(jsonPath("$.PAYDBCR.CommOrigin.CommUserid")
						.value("ACME    "))
				.andExpect(jsonPath("$.PAYDBCR.CommOrigin.CommFaciltype")
						.value("0496"))
				.andRespond(withSuccess(paydbcr("00000001", "-25.00", "Y", "0"),
						MediaType.APPLICATION_JSON));

		mockMvc.perform(post("/paydbcr").param("acctNumber", "1")
				.param("debit", "true").param("amount", "25")
				.param("organisation", "PAYAPP  ACME"))
				.andExpect(status().isOk())
				.andExpect(view().name("paymentInterfaceForm"))
				.andExpect(model().attribute("largeText", "Payment Successful"))
				.andExpect(model().attribute("smallText", "Value: -25.0"))
				.andExpect(model().attribute("results", true));
		server.verify();
	}


	@Test
	void creditFormReportsCreditSuccessful() throws Exception
	{
		server.expect(requestTo(DBCR_URL))
				.andExpect(jsonPath("$.PAYDBCR.CommAmt").value(40.5))
				.andRespond(withSuccess(paydbcr("00000002", "40.50", "Y", "0"),
						MediaType.APPLICATION_JSON));

		mockMvc.perform(post("/paydbcr").param("acctNumber", "2")
				.param("debit", "false").param("amount", "40.5")
				.param("organisation", "PAYAPP  ACME"))
				.andExpect(model().attribute("largeText", "Credit Successful"))
				.andExpect(model().attribute("smallText", "Value: 40.5"));
		server.verify();
	}


	@ParameterizedTest
	@CsvSource(
	{ "1,Payment rejected: Account does not exist.",
			"3,Payment rejected: Insufficient funds.",
			"4,Payment rejected: Invalid account type." })
	void failCodesMapToLegacyMessages(String failCode, String message)
			throws Exception
	{
		server.expect(requestTo(DBCR_URL))
				.andRespond(withSuccess(
						paydbcr("00000009", "-10.00", "N", failCode),
						MediaType.APPLICATION_JSON));

		mockMvc.perform(post("/paydbcr").param("acctNumber", "9")
				.param("debit", "true").param("amount", "10")
				.param("organisation", "PAYAPP  ACME"))
				.andExpect(model().attribute("largeText", "Payment Error"))
				.andExpect(model().attribute("smallText", message));
		server.verify();
	}


	@Test
	void unreachableCoreServiceShowsConnectionMessage() throws Exception
	{
		server.expect(requestTo(DBCR_URL)).andRespond(
				withException(new ConnectException("Connection refused")));

		mockMvc.perform(post("/paydbcr").param("acctNumber", "1")
				.param("debit", "true").param("amount", "1")
				.param("organisation", "PAYAPP  ACME"))
				.andExpect(model().attribute("largeText", "Payment Error"))
				.andExpect(model().attribute("smallText",
						"Connection refused or failed to resolve; Are you using the right address and port? Is the server running?"));
	}


	@Test
	void serverErrorShowsGenericMessage() throws Exception
	{
		server.expect(requestTo(DBCR_URL)).andRespond(withServerError()
				.body("{\"errorMessage\":\"boom\"}")
				.contentType(MediaType.APPLICATION_JSON));

		mockMvc.perform(post("/paydbcr").param("acctNumber", "1")
				.param("debit", "true").param("amount", "1")
				.param("organisation", "PAYAPP  ACME"))
				.andExpect(model().attribute("smallText",
						"There was an error processing the request; Please try again later or check logs for more info."));
	}


	@Test
	void invalidFormDoesNotCallCoreService() throws Exception
	{
		mockMvc.perform(post("/paydbcr").param("acctNumber", "123456789")
				.param("debit", "true").param("amount", "1")
				.param("organisation", "PAYAPP  ACME"))
				.andExpect(view().name("paymentInterfaceForm"))
				.andExpect(model().attributeDoesNotExist("results"));
		server.verify();
	}


	@Test
	void submitEndpointPutsDebitAndReturnsResponseCommarea() throws Exception
	{
		server.expect(requestTo(DBCR_URL))
				.andExpect(method(HttpMethod.PUT))
				.andExpect(jsonPath("$.PAYDBCR.CommAccno").value("00000003"))
				// /submit has always posted a debit (TransferForm defaults debit=true)
				.andExpect(jsonPath("$.PAYDBCR.CommAmt").value(-12.5))
				.andRespond(withSuccess(paydbcr("00000003", "-12.50", "Y", "0"),
						MediaType.APPLICATION_JSON));

		MvcResult result = mockMvc
				.perform(post("/submit").param("acctnum", "3")
						.param("amount", "12.5")
						.param("organisation", "PAYAPP  ACME"))
				.andExpect(status().isOk()).andReturn();
		assertThat(result.getResponse().getContentAsString())
				.contains("\"CommSuccess\":\"Y\"")
				.contains("\"CommFailCode\":\"0\"");
		server.verify();
	}


	@Test
	void submitEndpointReturnsEmptyBodyWhenCoreUnavailable() throws Exception
	{
		server.expect(requestTo(DBCR_URL))
				.andRespond(withException(new IOException("down")));

		MvcResult result = mockMvc
				.perform(post("/submit").param("acctnum", "3")
						.param("amount", "1")
						.param("organisation", "PAYAPP  ACME"))
				.andExpect(status().isOk()).andReturn();
		assertThat(result.getResponse().getContentAsString()).isEmpty();
	}


	@Test
	void connectionInfoNormalisesBaseUrl()
	{
		assertThat(new ConnectionInfo("http://h:1/", "webui-1.0/")
				.getAddressAndPort()).isEqualTo("http://h:1/webui-1.0");
		assertThat(new ConnectionInfo("http://localhost:8080", "/webui-1.0")
				.getAddressAndPort())
				.isEqualTo("http://localhost:8080/webui-1.0");
	}
}
