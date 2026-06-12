/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.ibm.cics.cip.bank.springboot.transferfunds.domain.Account;
import com.ibm.cics.cip.bank.springboot.transferfunds.domain.AccountKey;
import com.ibm.cics.cip.bank.springboot.transferfunds.domain.ProcessedTransaction;
import com.ibm.cics.cip.bank.springboot.transferfunds.dto.TransferRequest;
import com.ibm.cics.cip.bank.springboot.transferfunds.dto.TransferResponse;
import com.ibm.cics.cip.bank.springboot.transferfunds.exception.SameAccountTransferException;
import com.ibm.cics.cip.bank.springboot.transferfunds.exception.TransferProcessingException;
import com.ibm.cics.cip.bank.springboot.transferfunds.repository.AccountRepository;
import com.ibm.cics.cip.bank.springboot.transferfunds.repository.ProcessedTransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Modernized implementation of the XFRFUN (Transfer Funds) CICS/COBOL program.
 *
 * <p>
 * This service reproduces the business behaviour of {@code XFRFUN.cbl}:
 * </p>
 *
 * <ol>
 * <li>Reject a zero or negative amount with fail code {@code '4'}
 * ({@code A010}).</li>
 * <li>Reject a transfer to the same account ({@code UAD010}); the COBOL program
 * abends with code {@code SAME}, which is surfaced here as
 * {@link SameAccountTransferException}.</li>
 * <li>Debit the FROM account and credit the TO account, locking the two rows in
 * ascending account-number order to avoid deadlocks &mdash; the purpose of the
 * {@code COMM-FACCNO < COMM-TACCNO} branch in {@code UPDATE-ACCOUNT-DB2}.</li>
 * <li>Return fail code {@code '1'} if the FROM account is missing
 * ({@code UPDATE-ACCOUNT-DB2-FROM}) or {@code '2'} if the TO account is missing
 * ({@code UPDATE-ACCOUNT-DB2-TO}); either case rolls back the unit of work.</li>
 * <li>On success, append a {@code TFR} record to PROCTRAN
 * ({@code WRITE-TO-PROCTRAN-DB2}) and return the four updated balances.</li>
 * </ol>
 *
 * <p>
 * The whole operation runs in a single {@link Transactional} unit of work, which
 * is the JPA equivalent of the CICS logical unit of work bounded by SYNCPOINT /
 * ROLLBACK in the original program.
 * </p>
 */
@Service
public class TransferFundsService
{

	private static final Logger LOG = LoggerFactory
			.getLogger(TransferFundsService.class);

	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
			.ofPattern("dd.MM.yyyy");

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
			.ofPattern("HHmmss");

	private final AccountRepository accountRepository;

	private final ProcessedTransactionRepository processedTransactionRepository;

	private final Clock clock;

	/**
	 * Bank sort code. XFRFUN forces both the FROM and TO sort codes to the
	 * compile-time {@code SORTCODE} constant (987654) at {@code A010}.
	 */
	private final String bankSortCode;

	/** Stand-in for the CICS EIBTASKN task number used as the PROCTRAN ref. */
	private final AtomicLong referenceSequence = new AtomicLong(1L);

	public TransferFundsService(AccountRepository accountRepository,
			ProcessedTransactionRepository processedTransactionRepository,
			Clock clock,
			@Value("${cbsa.bank.sort-code:987654}") String bankSortCode)
	{
		this.accountRepository = accountRepository;
		this.processedTransactionRepository = processedTransactionRepository;
		this.clock = clock;
		this.bankSortCode = bankSortCode;
	}

	@Transactional
	public TransferResponse transfer(TransferRequest request)
	{
		String sortCode = bankSortCode;
		String fromAccountNumber = leftPad(request.getFromAccountNumber(), 8);
		String toAccountNumber = leftPad(request.getToAccountNumber(), 8);
		BigDecimal amount = request.getAmount();

		// A010: if the amount being transferred is negative (or zero), flag this
		// as a failure and finish.
		if (amount == null || amount.signum() <= 0)
		{
			LOG.info("Transfer rejected: non-positive amount {}", amount);
			return TransferResponse.failure(
					TransferFailureReason.NON_POSITIVE_AMOUNT, sortCode,
					fromAccountNumber, sortCode, toAccountNumber, amount);
		}

		// UAD010: cannot transfer to the same account (COBOL abends 'SAME').
		if (fromAccountNumber.equals(toAccountNumber))
		{
			LOG.info("Transfer rejected: same account {}", fromAccountNumber);
			throw new SameAccountTransferException();
		}

		AccountKey fromKey = new AccountKey(sortCode, fromAccountNumber);
		AccountKey toKey = new AccountKey(sortCode, toAccountNumber);

		// UPDATE-ACCOUNT-DB2: lock the two rows in ascending account-number
		// order so concurrent, opposite-direction transfers cannot deadlock.
		Account fromAccount;
		Account toAccount;
		if (fromAccountNumber.compareTo(toAccountNumber) < 0)
		{
			fromAccount = lockAccount(fromKey);
			if (fromAccount == null)
			{
				return notFound(TransferFailureReason.FROM_ACCOUNT_NOT_FOUND,
						sortCode, fromAccountNumber, toAccountNumber, amount);
			}
			toAccount = lockAccount(toKey);
			if (toAccount == null)
			{
				return notFound(TransferFailureReason.TO_ACCOUNT_NOT_FOUND,
						sortCode, fromAccountNumber, toAccountNumber, amount);
			}
		}
		else
		{
			toAccount = lockAccount(toKey);
			if (toAccount == null)
			{
				return notFound(TransferFailureReason.TO_ACCOUNT_NOT_FOUND,
						sortCode, fromAccountNumber, toAccountNumber, amount);
			}
			fromAccount = lockAccount(fromKey);
			if (fromAccount == null)
			{
				return notFound(TransferFailureReason.FROM_ACCOUNT_NOT_FOUND,
						sortCode, fromAccountNumber, toAccountNumber, amount);
			}
		}

		// UPDATE-ACCOUNT-DB2-FROM: debit the FROM account (available + actual).
		fromAccount.setAvailableBalance(
				fromAccount.getAvailableBalance().subtract(amount));
		fromAccount.setActualBalance(
				fromAccount.getActualBalance().subtract(amount));

		// UPDATE-ACCOUNT-DB2-TO: credit the TO account (available + actual).
		toAccount.setAvailableBalance(
				toAccount.getAvailableBalance().add(amount));
		toAccount.setActualBalance(toAccount.getActualBalance().add(amount));

		accountRepository.save(fromAccount);
		accountRepository.save(toAccount);

		// WRITE-TO-PROCTRAN-DB2: record the successful transfer.
		writeProcessedTransaction(sortCode, fromAccountNumber, toAccountNumber,
				amount);

		LOG.info("Transfer of {} from {} to {} successful", amount,
				fromAccountNumber, toAccountNumber);

		return success(sortCode, fromAccount, toAccount, amount);
	}

	private Account lockAccount(AccountKey key)
	{
		try
		{
			Optional<Account> account = accountRepository.findByKeyForUpdate(key);
			return account.orElse(null);
		}
		catch (DataAccessException e)
		{
			// XFRFUN sets COMM-FAIL-CODE '3' / abends on any other Db2 error.
			throw new TransferProcessingException(
					"Datastore error reading account " + key, e);
		}
	}

	private TransferResponse notFound(TransferFailureReason reason,
			String sortCode, String fromAccountNumber, String toAccountNumber,
			BigDecimal amount)
	{
		// COBOL issues SYNCPOINT ROLLBACK before returning the fail code; the
		// JPA equivalent is to mark the surrounding transaction rollback-only
		// while still returning the populated commarea to the caller.
		TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
		LOG.info("Transfer rejected: {} (from {} to {})", reason.name(),
				fromAccountNumber, toAccountNumber);
		return TransferResponse.failure(reason, sortCode, fromAccountNumber,
				sortCode, toAccountNumber, amount);
	}

	private void writeProcessedTransaction(String sortCode,
			String fromAccountNumber, String toAccountNumber, BigDecimal amount)
	{
		LocalDateTime now = LocalDateTime.now(clock);

		ProcessedTransaction proctran = new ProcessedTransaction();
		proctran.setEyeCatcher(ProcessedTransaction.EYECATCHER);
		// PROCTRAN is keyed on the FROM account, as in the COBOL program.
		proctran.setSortCode(sortCode);
		proctran.setAccountNumber(fromAccountNumber);
		proctran.setDate(now.format(DATE_FORMAT));
		proctran.setTime(now.format(TIME_FORMAT));
		proctran.setReference(
				leftPad(Long.toString(referenceSequence.getAndIncrement()), 12));
		proctran.setType(ProcessedTransaction.TYPE_TRANSFER);
		proctran.setDescription(buildTransferDescription(sortCode,
				toAccountNumber));
		proctran.setAmount(amount);

		try
		{
			processedTransactionRepository.save(proctran);
		}
		catch (DataAccessException e)
		{
			// WTPD010: abend 'WPCD' when the PROCTRAN insert fails after the
			// accounts were already updated -> roll the whole unit of work back.
			throw new TransferProcessingException(
					"Unable to write to PROCTRAN datastore", e);
		}
	}

	/**
	 * Builds the 40-character PROCTRAN description for a transfer:
	 * {@code 'TRANSFER'} padded to 26 characters, followed by the destination
	 * sort code (6) and account number (8). Mirrors the COBOL
	 * {@code PROC-TRAN-DESC-XFR} redefinition.
	 */
	private String buildTransferDescription(String toSortCode,
			String toAccountNumber)
	{
		return rightPad(ProcessedTransaction.DESC_TRANSFER_HEADER, 26)
				+ leftPad(toSortCode, 6) + leftPad(toAccountNumber, 8);
	}

	private TransferResponse success(String sortCode, Account fromAccount,
			Account toAccount, BigDecimal amount)
	{
		TransferResponse response = new TransferResponse();
		response.setSuccess(true);
		response.setCommSuccess("Y");
		response.setCommFailCode(TransferFailureReason.NONE.getCode());
		response.setFailureReason(TransferFailureReason.NONE.name());
		response.setMessage("Transfer successful.");
		response.setFromSortCode(sortCode);
		response.setFromAccountNumber(fromAccount.getKey().getAccountNumber());
		response.setToSortCode(sortCode);
		response.setToAccountNumber(toAccount.getKey().getAccountNumber());
		response.setAmount(amount);
		response.setFromAvailableBalance(fromAccount.getAvailableBalance());
		response.setFromActualBalance(fromAccount.getActualBalance());
		response.setToAvailableBalance(toAccount.getAvailableBalance());
		response.setToActualBalance(toAccount.getActualBalance());
		return response;
	}

	private static String leftPad(String value, int length)
	{
		String trimmed = value == null ? "" : value.trim();
		if (trimmed.length() >= length)
		{
			return trimmed;
		}
		return "0".repeat(length - trimmed.length()) + trimmed;
	}

	private static String rightPad(String value, int length)
	{
		String safe = value == null ? "" : value;
		if (safe.length() >= length)
		{
			return safe.substring(0, length);
		}
		return safe + " ".repeat(length - safe.length());
	}
}
