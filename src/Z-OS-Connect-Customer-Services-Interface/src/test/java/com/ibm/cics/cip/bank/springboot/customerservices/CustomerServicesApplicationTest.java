package com.ibm.cics.cip.bank.springboot.customerservices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CustomerServicesApplicationTest
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
		assertThat(connectionInfo.getSortCode()).isEqualTo("987654");
	}


	@Test
	void formsRender() throws Exception
	{
		for (String page : new String[]
		{ "/", "/enqacct", "/enqcust", "/listacc", "/createacc",
				"/createcust", "/updateacc", "/updatecust", "/delacct",
				"/delcust" })
		{
			mockMvc.perform(get(page)).andExpect(status().isOk());
		}
	}
}

@SpringBootTest(properties = "cbsa.core.base-url=http://core.example:9090/")
class CustomerServicesBaseUrlOverrideTest
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
