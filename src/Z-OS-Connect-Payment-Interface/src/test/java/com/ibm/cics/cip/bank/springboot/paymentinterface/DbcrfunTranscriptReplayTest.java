package com.ibm.cics.cip.bank.springboot.paymentinterface;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.cics.cip.bank.springboot.paymentinterface.controllers.ParamsController;
import com.ibm.cics.cip.bank.springboot.paymentinterface.controllers.WebController;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

/**
 * Replays every baseline/transcripts/DBCRFUN/*.json through the payment
 * adapter: the transcript's COMMAREA input drives the form, the mocked
 * cbsa-core-service answers with the transcript's COMMAREA output, and the
 * adapter must send the same account/amount and render the message the legacy
 * z/OS Connect adapter rendered for that COMM-SUCCESS/COMM-FAIL-CODE. DB deltas
 * belong to cbsa-core-service (the adapter has no datastore).
 */
class DbcrfunTranscriptReplayTest
{

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String BASE = "http://core.test:8080";


	static Path transcriptDir()
	{
		Path dir = Paths.get("..", "..", "baseline", "transcripts", "DBCRFUN")
				.toAbsolutePath().normalize();
		return dir;
	}


	static Stream<Path> transcripts() throws IOException
	{
		try (Stream<Path> files = Files.list(transcriptDir()))
		{
			List<Path> list = files
					.filter(p -> p.toString().endsWith(".json")).sorted()
					.toList();
			return list.stream();
		}
	}


	@Test
	void allDbcrfunTranscriptsArePresent() throws IOException
	{
		assertThat(transcripts().count()).isGreaterThanOrEqualTo(12);
	}


	@ParameterizedTest(name = "{0}")
	@MethodSource("transcripts")
	void replay(Path transcript) throws Exception
	{
		JsonNode t = MAPPER.readTree(transcript.toFile());
		JsonNode in = t.get("commarea_in");
		JsonNode out = t.get("commarea_out");

		String accno = in.get("COMM-ACCNO").asText();
		BigDecimal amt = in.get("COMM-AMT").decimalValue();
		boolean debit = amt.signum() < 0;

		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
				.build();
		CbsaCoreClient client = new CbsaCoreClient(builder,
				new ConnectionInfo(BASE, "/webui-1.0"));
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new WebController(client),
						new ParamsController(client))
				.build();

		server.expect(requestTo(BASE + "/webui-1.0/makepayment/dbcr"))
				.andExpect(method(HttpMethod.PUT))
				.andExpect(jsonPath("$.PAYDBCR.CommAccno").value(accno))
				.andExpect(jsonPath("$.PAYDBCR.CommAmt")
						.value(amt.doubleValue()))
				.andRespond(withSuccess(toPaydbcr(out).toString(),
						MediaType.APPLICATION_JSON));

		String success = out.get("COMM-SUCCESS").asText();
		String failCode = out.get("COMM-FAIL-CODE").asText();
		String[] expected = expectedMessages(success, failCode, debit,
				out.get("COMM-AMT").floatValue());

		mockMvc.perform(post("/paydbcr")
				.param("acctNumber", Integer.toString(Integer.parseInt(accno)))
				.param("debit", Boolean.toString(debit))
				.param("amount", amt.abs().toPlainString())
				.param("organisation", "PAYAPP  ACME"))
				.andExpect(model().attribute("largeText", expected[0]))
				.andExpect(model().attribute("smallText", expected[1]));
		server.verify();
	}


	/** Legacy WebController.checkIfResponseValidDbcr message table. */
	static String[] expectedMessages(String success, String failCode,
			boolean debit, float amt)
	{
		switch (failCode.trim())
		{
		case "1":
			return new String[]
			{ "Payment Error", "Payment rejected: Account does not exist." };
		case "3":
			return new String[]
			{ "Payment Error", "Payment rejected: Insufficient funds." };
		case "4":
			return new String[]
			{ "Payment Error", "Payment rejected: Invalid account type." };
		default:
			return new String[]
			{ debit ? "Payment Successful" : "Credit Successful",
					"Value: " + amt };
		}
	}


	static ObjectNode toPaydbcr(JsonNode out)
	{
		ObjectNode origin = MAPPER.createObjectNode();
		JsonNode o = out.get("COMM-ORIGIN");
		origin.put("CommApplid", o.path("COMM-APPLID").asText());
		origin.put("CommUserid", o.path("COMM-USERID").asText());
		origin.put("CommFacilityName", o.path("COMM-FACILITY-NAME").asText());
		origin.put("CommNetwrkId", o.path("COMM-NETWRK-ID").asText());
		origin.put("CommFaciltype", o.path("COMM-FACILTYPE").asInt());
		origin.put("Fill0", "    ");
		ObjectNode dbcr = MAPPER.createObjectNode();
		dbcr.put("CommAccno", out.get("COMM-ACCNO").asText());
		dbcr.set("CommAmt", out.get("COMM-AMT"));
		dbcr.set("mSortC", out.get("COMM-SORTC"));
		dbcr.set("CommAvBal", out.get("COMM-AV-BAL"));
		dbcr.set("CommActBal", out.get("COMM-ACT-BAL"));
		dbcr.set("CommOrigin", origin);
		dbcr.put("CommSuccess", out.get("COMM-SUCCESS").asText());
		dbcr.put("CommFailCode", out.get("COMM-FAIL-CODE").asText());
		ObjectNode root = MAPPER.createObjectNode();
		root.set("PAYDBCR", dbcr);
		return root;
	}
}
