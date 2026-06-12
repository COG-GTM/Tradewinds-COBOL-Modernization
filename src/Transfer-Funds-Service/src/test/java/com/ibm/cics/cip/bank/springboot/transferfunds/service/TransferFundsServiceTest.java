/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.ibm.cics.cip.bank.springboot.transferfunds.domain.Account;
import com.ibm.cics.cip.bank.springboot.transferfunds.domain.AccountKey;
import com.ibm.cics.cip.bank.springboot.transferfunds.domain.ProcessedTransaction;
import com.ibm.cics.cip.bank.springboot.transferfunds.dto.TransferRequest;
import com.ibm.cics.cip.bank.springboot.transferfunds.dto.TransferResponse;
import com.ibm.cics.cip.bank.springboot.transferfunds.exception.SameAccountTransferException;
import com.ibm.cics.cip.bank.springboot.transferfunds.repository.AccountRepository;
import com.ibm.cics.cip.bank.springboot.transferfunds.repository.ProcessedTransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootTest
class TransferFundsServiceTest
{

	private static final String SORT_CODE = "987654";

	@TestConfiguration
	static class FixedClockConfig
	{
		@Bean
		@Primary
		Clock testClock()
		{
			return Clock.fixed(Instant.parse("2023-06-15T09:30:45Z"),
					ZoneId.of("UTC"));
		}
	}

	@Autowired
	private TransferFundsService service;

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
		account.setCustomerNumber("0000000001");
		account.setAccountType("CURRENT");
		account.setInterestRate(new BigDecimal("0.00"));
		account.setOpened("01.01.2023");
		account.setOverdraftLimit(0);
		account.setLastStatement("01.01.2023");
		account.setNextStatement("01.02.2023");
		account.setAvailableBalance(new BigDecimal(balance));
		account.setActualBalance(new BigDecimal(balance));
		return account;
	}

	private TransferResponse transfer(String from, String to, String amount)
	{
		return service.transfer(
				new TransferRequest(from, to, new BigDecimal(amount)));
	}

	private BigDecimal availableOf(String number)
	{
		return accountRepository.findById(new AccountKey(SORT_CODE, number))
				.orElseThrow().getAvailableBalance();
	}

	@Test
	void successfulTransferDebitsFromAndCreditsToBothBalances()
	{
		TransferResponse response = transfer("00000001", "00000002", "100.00");

		assertThat(response.isSuccess()).isTrue();
		assertThat(response.getCommSuccess()).isEqualTo("Y");
		assertThat(response.getCommFailCode()).isEqualTo(" ");
		// FROM debited, TO credited on both available and actual balances.
		assertThat(response.getFromAvailableBalance())
				.isEqualByComparingTo("4900.00");
		assertThat(response.getFromActualBalance())
				.isEqualByComparingTo("4900.00");
		assertThat(response.getToAvailableBalance())
				.isEqualByComparingTo("350.00");
		assertThat(response.getToActualBalance())
				.isEqualByComparingTo("350.00");

		assertThat(availableOf("00000001")).isEqualByComparingTo("4900.00");
		assertThat(availableOf("00000002")).isEqualByComparingTo("350.00");
	}

	@Test
	void successfulTransferFromHigherToLowerAccountNumber()
	{
		// Exercises the COBOL COMM-FACCNO > COMM-TACCNO lock-ordering branch.
		TransferResponse response = transfer("00000002", "00000001", "50.00");

		assertThat(response.isSuccess()).isTrue();
		assertThat(availableOf("00000002")).isEqualByComparingTo("200.00");
		assertThat(availableOf("00000001")).isEqualByComparingTo("5050.00");
	}

	@Test
	void successfulTransferWritesTransferRecordToProctran()
	{
		transfer("00000001", "00000002", "100.00");

		List<ProcessedTransaction> proctran = processedTransactionRepository
				.findAll();
		assertThat(proctran).hasSize(1);
		ProcessedTransaction record = proctran.get(0);
		assertThat(record.getEyeCatcher()).isEqualTo("PRTR");
		assertThat(record.getType()).isEqualTo("TFR");
		// PROCTRAN is keyed on the FROM account.
		assertThat(record.getSortCode()).isEqualTo(SORT_CODE);
		assertThat(record.getAccountNumber()).isEqualTo("00000001");
		assertThat(record.getAmount()).isEqualByComparingTo("100.00");
		// Description: 'TRANSFER' padded to 26 + TO sort code (6) + TO acct (8).
		assertThat(record.getDescription())
				.isEqualTo("TRANSFER" + " ".repeat(18) + "987654" + "00000002");
		assertThat(record.getDate()).isEqualTo("15.06.2023");
		assertThat(record.getTime()).isEqualTo("093045");
	}

	@Test
	void nonPositiveAmountReturnsFailCodeFourAndChangesNothing()
	{
		TransferResponse zero = transfer("00000001", "00000002", "0.00");
		assertThat(zero.isSuccess()).isFalse();
		assertThat(zero.getCommFailCode()).isEqualTo("4");
		assertThat(zero.getFailureReason())
				.isEqualTo(TransferFailureReason.NON_POSITIVE_AMOUNT.name());

		TransferResponse negative = transfer("00000001", "00000002", "-5.00");
		assertThat(negative.getCommFailCode()).isEqualTo("4");

		assertThat(availableOf("00000001")).isEqualByComparingTo("5000.00");
		assertThat(availableOf("00000002")).isEqualByComparingTo("250.00");
		assertThat(processedTransactionRepository.findAll()).isEmpty();
	}

	@Test
	void sameAccountTransferThrowsSameAccountException()
	{
		assertThatThrownBy(() -> transfer("00000001", "00000001", "100.00"))
				.isInstanceOf(SameAccountTransferException.class);

		assertThat(availableOf("00000001")).isEqualByComparingTo("5000.00");
		assertThat(processedTransactionRepository.findAll()).isEmpty();
	}

	@Test
	void fromAccountNotFoundReturnsFailCodeOne()
	{
		TransferResponse response = transfer("00000099", "00000002", "100.00");

		assertThat(response.isSuccess()).isFalse();
		assertThat(response.getCommFailCode()).isEqualTo("1");
		assertThat(response.getFailureReason())
				.isEqualTo(TransferFailureReason.FROM_ACCOUNT_NOT_FOUND.name());
		// TO account untouched.
		assertThat(availableOf("00000002")).isEqualByComparingTo("250.00");
		assertThat(processedTransactionRepository.findAll()).isEmpty();
	}

	@Test
	void toAccountNotFoundReturnsFailCodeTwoAndRollsBack()
	{
		TransferResponse response = transfer("00000001", "00000099", "100.00");

		assertThat(response.isSuccess()).isFalse();
		assertThat(response.getCommFailCode()).isEqualTo("2");
		assertThat(response.getFailureReason())
				.isEqualTo(TransferFailureReason.TO_ACCOUNT_NOT_FOUND.name());
		// FROM account debit rolled back.
		assertThat(availableOf("00000001")).isEqualByComparingTo("5000.00");
		assertThat(processedTransactionRepository.findAll()).isEmpty();
	}
}
