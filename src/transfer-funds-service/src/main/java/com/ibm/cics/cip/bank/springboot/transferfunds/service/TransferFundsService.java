/*
 *
 *    Copyright IBM Corp. 2023
 *
 */
package com.ibm.cics.cip.bank.springboot.transferfunds.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ibm.cics.cip.bank.springboot.transferfunds.domain.Account;
import com.ibm.cics.cip.bank.springboot.transferfunds.domain.ProcessedTransaction;
import com.ibm.cics.cip.bank.springboot.transferfunds.dto.FailCode;
import com.ibm.cics.cip.bank.springboot.transferfunds.dto.TransferRequest;
import com.ibm.cics.cip.bank.springboot.transferfunds.dto.TransferResponse;
import com.ibm.cics.cip.bank.springboot.transferfunds.repository.AccountRepository;
import com.ibm.cics.cip.bank.springboot.transferfunds.repository.ProcessedTransactionRepository;

/**
 * Java modernization of the XFRFUN COBOL/CICS program
 * (src/base/cobol_src/XFRFUN.cbl).
 *
 * Business rules preserved from the COBOL:
 * <ul>
 * <li>A non-positive amount is rejected with fail code '4' (PREMIERE
 * section).</li>
 * <li>Transfers between the same sort code + account number are rejected
 * (UAD010 'SAME' abend).</li>
 * <li>Accounts are updated in ascending account-number order to avoid
 * deadlocks (UPDATE-ACCOUNT-DB2 orders FROM/TO updates by account
 * number).</li>
 * <li>No overdraft-limit checking is performed on transfers.</li>
 * <li>Both balance updates and the PROCTRAN audit insert commit or roll
 * back as one unit of work (CICS SYNCPOINT semantics via
 * {@code @Transactional}).</li>
 * <li>Deadlocks are retried up to 5 times with a 1 second delay
 * (DB2-DEADLOCK-RETRY &lt; 6, EXEC CICS DELAY FOR SECONDS(1)).</li>
 * </ul>
 */
@Service
public class TransferFundsService
{
	private static final Logger LOG = LoggerFactory
			.getLogger(TransferFundsService.class);

	private static final DateTimeFormatter PROCTRAN_DATE = DateTimeFormatter
			.ofPattern("dd.MM.yyyy");

	private static final DateTimeFormatter PROCTRAN_TIME = DateTimeFormatter
			.ofPattern("HHmmss");

	private final AccountRepository accountRepository;

	private final ProcessedTransactionRepository processedTransactionRepository;

	public TransferFundsService(AccountRepository accountRepository,
			ProcessedTransactionRepository processedTransactionRepository)
	{
		this.accountRepository = accountRepository;
		this.processedTransactionRepository = processedTransactionRepository;
	}

	@Retryable(retryFor = CannotAcquireLockException.class, maxAttempts = 6, backoff = @Backoff(delay = 1000))
	@Transactional
	public TransferResponse transfer(TransferRequest request)
	{
		if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0)
		{
			return TransferResponse.failure(FailCode.NON_POSITIVE_AMOUNT);
		}

		String fromSortCode = normalizeSortCode(request.getFromSortCode());
		String toSortCode = normalizeSortCode(request.getToSortCode());
		String fromAccountNumber = normalizeAccountNumber(
				request.getFromAccountNumber());
		String toAccountNumber = normalizeAccountNumber(
				request.getToAccountNumber());

		if (fromAccountNumber.equals(toAccountNumber)
				&& fromSortCode.equals(toSortCode))
		{
			LOG.warn("Rejected same-account transfer");
			return TransferResponse.failure(FailCode.SAME_ACCOUNT);
		}

		Account from;
		Account to;

		if (fromAccountNumber.compareTo(toAccountNumber) < 0)
		{
			Optional<Account> fromOpt = lockAccount(fromSortCode,
					fromAccountNumber);
			if (fromOpt.isEmpty())
			{
				return TransferResponse
						.failure(FailCode.FROM_ACCOUNT_NOT_FOUND);
			}
			Optional<Account> toOpt = lockAccount(toSortCode, toAccountNumber);
			if (toOpt.isEmpty())
			{
				return TransferResponse.failure(FailCode.TO_ACCOUNT_NOT_FOUND);
			}
			from = fromOpt.get();
			to = toOpt.get();
		}
		else
		{
			Optional<Account> toOpt = lockAccount(toSortCode, toAccountNumber);
			if (toOpt.isEmpty())
			{
				return TransferResponse.failure(FailCode.TO_ACCOUNT_NOT_FOUND);
			}
			Optional<Account> fromOpt = lockAccount(fromSortCode,
					fromAccountNumber);
			if (fromOpt.isEmpty())
			{
				return TransferResponse
						.failure(FailCode.FROM_ACCOUNT_NOT_FOUND);
			}
			from = fromOpt.get();
			to = toOpt.get();
		}

		from.debit(request.getAmount());
		to.credit(request.getAmount());

		writeProcessedTransaction(from, to, request.getAmount());

		LOG.info("Transfer completed");

		return TransferResponse.ok(from.getAvailableBalance(),
				from.getActualBalance(), to.getAvailableBalance(),
				to.getActualBalance());
	}

	private Optional<Account> lockAccount(String sortCode,
			String accountNumber)
	{
		return accountRepository.findForUpdate(sortCode, accountNumber);
	}

	/**
	 * WRITE-TO-PROCTRAN-DB2: records the successful transfer against the
	 * FROM account, with a 'TRANSFER' description naming the TO account.
	 */
	private void writeProcessedTransaction(Account from, Account to,
			BigDecimal amount)
	{
		LocalDateTime now = LocalDateTime.now();

		ProcessedTransaction proctran = new ProcessedTransaction();
		proctran.setSortCode(from.getSortCode());
		proctran.setAccountNumber(from.getAccountNumber());
		proctran.setDate(now.format(PROCTRAN_DATE));
		proctran.setTime(now.format(PROCTRAN_TIME));
		proctran.setReference(String.format("%012d", now.getNano() % 1_000_000
				+ now.toLocalTime().toSecondOfDay() * 1_000_000L));
		proctran.setType(ProcessedTransaction.TYPE_TRANSFER);
		proctran.setDescription(String.format("%-26s%s%s", "TRANSFER",
				to.getSortCode(), to.getAccountNumber()));
		proctran.setAmount(amount);

		processedTransactionRepository.save(proctran);
	}

	/** Zero-pads to PIC 9(6), as COBOL numeric moves do. */
	private String normalizeSortCode(String sortCode)
	{
		return String.format("%6s", sortCode).replace(' ', '0');
	}

	/** Zero-pads to PIC 9(8), as COBOL numeric moves do. */
	private String normalizeAccountNumber(String accountNumber)
	{
		return String.format("%8s", accountNumber).replace(' ', '0');
	}
}
