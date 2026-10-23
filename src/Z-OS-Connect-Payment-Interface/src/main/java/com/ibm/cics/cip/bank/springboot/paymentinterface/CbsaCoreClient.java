/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.paymentinterface;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cics.cip.bank.springboot.paymentinterface.jsonclasses.paymentinterface.PaymentInterfaceJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls the cbsa-core-service mirror of the z/OS Connect makepayment API
 * ({@code PUT {base}/webui-1.0/makepayment/dbcr}, DBCRFUN COMMAREA semantics:
 * HTTP 200 with CommSuccess/CommFailCode in the body).
 */
@Component
public class CbsaCoreClient
{

	static final String MAKEPAYMENT_DBCR = "/makepayment/dbcr";

	private static final Logger log = LoggerFactory
			.getLogger(CbsaCoreClient.class);

	private static final ObjectMapper WRITER = new ObjectMapper();

	private static final ObjectMapper READER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
					false);

	private final RestClient restClient;


	public CbsaCoreClient(RestClient.Builder builder,
			ConnectionInfo connectionInfo)
	{
		this.restClient = builder.baseUrl(connectionInfo.getAddressAndPort())
				.build();
	}


	/**
	 * Sends the PAYDBCR COMMAREA and returns the response COMMAREA.
	 *
	 * @throws org.springframework.web.client.ResourceAccessException
	 *             when cbsa-core-service cannot be reached
	 * @throws org.springframework.web.client.RestClientResponseException
	 *             on a non-2xx response
	 */
	public PaymentInterfaceJson makePayment(PaymentInterfaceJson request)
			throws JsonProcessingException
	{
		String jsonString = WRITER.writeValueAsString(request);
		log.info(jsonString);
		String responseBody = restClient.put().uri(MAKEPAYMENT_DBCR)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON).body(jsonString).retrieve()
				.body(String.class);
		log.info(responseBody);
		if (responseBody == null || responseBody.isBlank())
		{
			throw new IllegalStateException(
					"Empty response from cbsa-core-service");
		}
		return READER.readValue(responseBody, PaymentInterfaceJson.class);
	}
}
