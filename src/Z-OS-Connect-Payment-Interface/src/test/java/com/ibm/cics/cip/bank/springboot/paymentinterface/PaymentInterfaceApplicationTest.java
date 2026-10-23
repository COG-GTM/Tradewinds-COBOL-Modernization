package com.ibm.cics.cip.bank.springboot.paymentinterface;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.hamcrest.Matchers;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentInterfaceApplicationTest
{

	@Autowired
	private ConnectionInfo connectionInfo;

	@Autowired
	private MockMvc mockMvc;


	@Test
	void defaultBaseUrlPointsAtLocalCoreService()
	{
		assertThat(connectionInfo.getAddressAndPort())
				.isEqualTo("http://localhost:8080/webui-1.0");
	}


	@Test
	void paymentFormRenders() throws Exception
	{
		mockMvc.perform(get("/")).andExpect(status().isOk())
				.andExpect(content().string(
						Matchers.containsString("action=\"/paydbcr\"")));
	}
}

@SpringBootTest(properties = "cbsa.core.base-url=http://core.example:9090")
class PaymentInterfaceBaseUrlOverrideTest
{

	@Autowired
	private ConnectionInfo connectionInfo;


	@Test
	void baseUrlIsConfigurable()
	{
		assertThat(connectionInfo.getAddressAndPort())
				.isEqualTo("http://core.example:9090/webui-1.0");
	}
}
