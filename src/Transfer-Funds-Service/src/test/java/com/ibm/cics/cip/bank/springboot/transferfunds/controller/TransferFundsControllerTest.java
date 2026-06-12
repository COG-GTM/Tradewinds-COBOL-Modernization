/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import com.ibm.cics.cip.bank.springboot.transferfunds.domain.Account;
import com.ibm.cics.cip.bank.springboot.transferfunds.domain.AccountKey;
import com.ibm.cics.cip.bank.springboot.transferfunds.repository.AccountRepository;
import com.ibm.cics.cip.bank.springboot.transferfunds.repository.ProcessedTransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TransferFundsControllerTest
{

	private static final String SORT_CODE = "987654";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private ProcessedTransactionRepository processedTransactionRepository;

	@BeforeEach
	void setUp()
	{
		processedTransactionRepository.deleteAll();
		accountRepository.deleteAll();
		accountRepository.save(account("00000001", "5000.00"));
		accountRepository.save(account("00000002", "250.00"));
	}

	private Account account(String number, String balance)
	{
		Account account = new Account();
		account.setKey(new AccountKey(SORT_CODE, number));
		account.setAccountType("CURRENT");
		account.setAvailableBalance(new BigDecimal(balance));
		account.setActualBalance(new BigDecimal(balance));
		return account;
	}

	@Test
	void transferEndpointReturnsUpdatedBalances() throws Exception
	{
		mockMvc.perform(post("/xfrfun/transfer")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fromAccountNumber\":\"1\",\"toAccountNumber\":\"2\",\"amount\":100.00}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.commFailCode").value(" "))
				.andExpect(jsonPath("$.fromAvailableBalance").value(4900.00))
				.andExpect(jsonPath("$.toAvailableBalance").value(350.00));
	}

	@Test
	void transferEndpointRejectsSameAccountWith422() throws Exception
	{
		mockMvc.perform(post("/xfrfun/transfer")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fromAccountNumber\":\"1\",\"toAccountNumber\":\"1\",\"amount\":100.00}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("SAME"));
	}

	@Test
	void transferEndpointRejectsInvalidAccountNumberWith400() throws Exception
	{
		mockMvc.perform(post("/xfrfun/transfer")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fromAccountNumber\":\"ABC\",\"toAccountNumber\":\"2\",\"amount\":100.00}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION"));
	}
}
