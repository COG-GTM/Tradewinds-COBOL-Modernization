/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.controllers;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cics.cip.bank.springboot.transferfunds.jsonclasses.transferfunds.TransferFundsForm;
import com.ibm.cics.cip.bank.springboot.transferfunds.model.AccountEntity;
import com.ibm.cics.cip.bank.springboot.transferfunds.model.AccountRepository;
import com.ibm.cics.cip.bank.springboot.transferfunds.model.ProcessedTransactionRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST-layer characterization tests.
 *
 * These assert that the COBOL COMMAREA failure contract is preserved through
 * the HTTP boundary — i.e. failures come back as a structured
 * TransferFundsResponse with COMM-SUCCESS='N' and the specific COMM-FAIL-CODE,
 * not as a generic Spring validation error. They guard against regressions
 * where bean validation (e.g. @Positive) or a generic rollback exception would
 * short-circuit the COBOL-equivalent response.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransferFundsControllerTest
{


	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private ProcessedTransactionRepository proctranRepository;


	@BeforeEach
	void cleanDatabase()
	{
		proctranRepository.deleteAll();
		accountRepository.deleteAll();
	}


	private void createAccount(String sortcode, String accountNumber,
			BigDecimal balance)
	{
		AccountEntity account = new AccountEntity();
		account.setEyecatcher("ACCT");
		account.setCustomerNumber("0000000001");
		account.setSortcode(sortcode);
		account.setAccountNumber(accountNumber);
		account.setAccountType("ISA     ");
		account.setInterestRate(new BigDecimal("2.50"));
		account.setOpened(LocalDate.of(2023, 1, 15));
		account.setOverdraftLimit(0);
		account.setLastStatement(LocalDate.of(2023, 6, 1));
		account.setNextStatement(LocalDate.of(2023, 9, 1));
		account.setAvailableBalance(balance);
		account.setActualBalance(balance);
		accountRepository.save(account);
	}


	private String json(TransferFundsForm form) throws Exception
	{
		return objectMapper.writeValueAsString(form);
	}


	@Test
	@DisplayName("Happy path -> HTTP 200 with success 'Y'")
	void happyPathReturnsOk() throws Exception
	{
		createAccount("987654", "00000001", new BigDecimal("1000.00"));
		createAccount("987654", "00000002", new BigDecimal("500.00"));

		TransferFundsForm form = new TransferFundsForm("00000001", "987654",
				"00000002", "987654", new BigDecimal("100.00"));

		mockMvc.perform(post("/api/v1/transfers")
				.contentType(MediaType.APPLICATION_JSON).content(json(form)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value("Y"))
				.andExpect(jsonPath("$.failCode").value("0"));
	}


	@Test
	@DisplayName("Zero amount -> HTTP 422 with fail code '4' (COBOL contract, not a 400 validation error)")
	void zeroAmountReturnsStructuredFailure() throws Exception
	{
		createAccount("987654", "00000001", new BigDecimal("1000.00"));
		createAccount("987654", "00000002", new BigDecimal("500.00"));

		TransferFundsForm form = new TransferFundsForm("00000001", "987654",
				"00000002", "987654", BigDecimal.ZERO);

		mockMvc.perform(post("/api/v1/transfers")
				.contentType(MediaType.APPLICATION_JSON).content(json(form)))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.success").value("N"))
				.andExpect(jsonPath("$.failCode").value("4"));
	}


	@Test
	@DisplayName("Missing TO account -> HTTP 422 with fail code '2' (specific code preserved, not generic ROLLBACK)")
	void missingToAccountReturnsFailCodeTwo() throws Exception
	{
		// FROM(00000001) < TO(99999999): FROM debited first, TO missing ->
		// rollback, but the specific fail code '2' must survive to the client.
		createAccount("987654", "00000001", new BigDecimal("1000.00"));

		TransferFundsForm form = new TransferFundsForm("00000001", "987654",
				"99999999", "987654", new BigDecimal("100.00"));

		mockMvc.perform(post("/api/v1/transfers")
				.contentType(MediaType.APPLICATION_JSON).content(json(form)))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.success").value("N"))
				.andExpect(jsonPath("$.failCode").value("2"));
	}
}
