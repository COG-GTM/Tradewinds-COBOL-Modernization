/*
 *
 *    Copyright IBM Corp. 2023
 *
 */
package com.ibm.cics.cip.bank.springboot.transferfunds;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.ibm.cics.cip.bank.springboot.transferfunds.domain.Account;
import com.ibm.cics.cip.bank.springboot.transferfunds.domain.AccountId;
import com.ibm.cics.cip.bank.springboot.transferfunds.domain.ProcessedTransaction;
import com.ibm.cics.cip.bank.springboot.transferfunds.dto.FailCode;
import com.ibm.cics.cip.bank.springboot.transferfunds.dto.TransferRequest;
import com.ibm.cics.cip.bank.springboot.transferfunds.dto.TransferResponse;
import com.ibm.cics.cip.bank.springboot.transferfunds.repository.AccountRepository;
import com.ibm.cics.cip.bank.springboot.transferfunds.repository.ProcessedTransactionRepository;
import com.ibm.cics.cip.bank.springboot.transferfunds.service.TransferFundsService;

@SpringBootTest
class TransferFundsServiceTest
{
	private static final String SORT_CODE = "987654";

	@Autowired
	private TransferFundsService service;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private ProcessedTransactionRepository processedTransactionRepository;

	private TransferRequest request(String fromAcc, String toAcc,
			String amount)
	{
		TransferRequest request = new TransferRequest();
		request.setFromSortCode(SORT_CODE);
		request.setFromAccountNumber(fromAcc);
		request.setToSortCode(SORT_CODE);
		request.setToAccountNumber(toAcc);
		request.setAmount(new BigDecimal(amount));
		return request;
	}

	private BigDecimal balance(String accountNumber)
	{
		Account account = accountRepository
				.findById(new AccountId(SORT_CODE, accountNumber))
				.orElseThrow();
		return account.getActualBalance();
	}

	@Test
	void successfulTransferMovesFundsAndWritesProctran()
	{
		BigDecimal fromBefore = balance("00000001");
		BigDecimal toBefore = balance("00000002");
		long proctranBefore = processedTransactionRepository.count();

		TransferResponse response = service
				.transfer(request("00000001", "00000002", "100.00"));

		assertThat(response.isSuccess()).isTrue();
		assertThat(response.getFromActualBalance())
				.isEqualByComparingTo(
						fromBefore.subtract(new BigDecimal("100.00")));
		assertThat(response.getToActualBalance())
				.isEqualByComparingTo(toBefore.add(new BigDecimal("100.00")));
		assertThat(balance("00000001"))
				.isEqualByComparingTo(response.getFromActualBalance());
		assertThat(balance("00000002"))
				.isEqualByComparingTo(response.getToActualBalance());

		List<ProcessedTransaction> proctrans = processedTransactionRepository
				.findAll();
		assertThat(proctrans).hasSize((int) proctranBefore + 1);
		ProcessedTransaction last = proctrans.get(proctrans.size() - 1);
		assertThat(last.getType())
				.isEqualTo(ProcessedTransaction.TYPE_TRANSFER);
		assertThat(last.getSortCode()).isEqualTo(SORT_CODE);
		assertThat(last.getAccountNumber()).isEqualTo("00000001");
		assertThat(last.getDescription()).startsWith("TRANSFER")
				.endsWith(SORT_CODE + "00000002");
		assertThat(last.getAmount())
				.isEqualByComparingTo(new BigDecimal("100.00"));
	}

	@Test
	void nonPositiveAmountFailsWithCode4()
	{
		TransferResponse zero = service
				.transfer(request("00000001", "00000002", "0"));
		TransferResponse negative = service
				.transfer(request("00000001", "00000002", "-5.00"));

		assertThat(zero.isSuccess()).isFalse();
		assertThat(zero.getFailCode())
				.isEqualTo(FailCode.NON_POSITIVE_AMOUNT);
		assertThat(negative.isSuccess()).isFalse();
		assertThat(negative.getFailCode())
				.isEqualTo(FailCode.NON_POSITIVE_AMOUNT);
	}

	@Test
	void sameAccountTransferIsRejected()
	{
		TransferResponse response = service
				.transfer(request("00000001", "00000001", "10.00"));

		assertThat(response.isSuccess()).isFalse();
		assertThat(response.getFailCode()).isEqualTo(FailCode.SAME_ACCOUNT);
	}

	@Test
	void missingFromAccountFailsWithCode1()
	{
		TransferResponse response = service
				.transfer(request("00099999", "00000002", "10.00"));

		assertThat(response.isSuccess()).isFalse();
		assertThat(response.getFailCode())
				.isEqualTo(FailCode.FROM_ACCOUNT_NOT_FOUND);
	}

	@Test
	void missingToAccountFailsWithCode2()
	{
		TransferResponse response = service
				.transfer(request("00000001", "00099999", "10.00"));

		assertThat(response.isSuccess()).isFalse();
		assertThat(response.getFailCode())
				.isEqualTo(FailCode.TO_ACCOUNT_NOT_FOUND);
	}

	@Test
	void noOverdraftCheckIsPerformed()
	{
		// XFRFUN explicitly performs no overdraft-limit checking
		TransferResponse response = service
				.transfer(request("00000002", "00000001", "999999.00"));

		assertThat(response.isSuccess()).isTrue();
		assertThat(response.getFromActualBalance())
				.isLessThan(BigDecimal.ZERO);
	}

	@Test
	void shortInputsAreZeroPaddedLikeCobolNumericMoves()
	{
		TransferResponse response = service
				.transfer(request("1", "2", "5.00"));

		assertThat(response.isSuccess()).isTrue();
	}
}
