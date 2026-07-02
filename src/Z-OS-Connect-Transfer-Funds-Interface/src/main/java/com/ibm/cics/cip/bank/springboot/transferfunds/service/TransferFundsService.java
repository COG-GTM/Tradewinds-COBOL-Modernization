/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ibm.cics.cip.bank.springboot.transferfunds.jsonclasses.transferfunds.TransferFundsForm;
import com.ibm.cics.cip.bank.springboot.transferfunds.jsonclasses.transferfunds.TransferFundsResponse;
import com.ibm.cics.cip.bank.springboot.transferfunds.model.AccountEntity;
import com.ibm.cics.cip.bank.springboot.transferfunds.model.AccountRepository;
import com.ibm.cics.cip.bank.springboot.transferfunds.model.ProcessedTransactionEntity;
import com.ibm.cics.cip.bank.springboot.transferfunds.model.ProcessedTransactionRepository;

/**
 * Modernised implementation of XFRFUN.cbl — Transfer Funds business logic.
 *
 * <h2>COBOL paragraph → Java method mapping</h2>
 * <pre>
 *   PREMIERE / A010                    → transferFunds()
 *   UPDATE-ACCOUNT-DB2                 → transferFunds() orchestration
 *   UPDATE-ACCOUNT-DB2-FROM / UADF010  → debitFromAccount()
 *   UPDATE-ACCOUNT-DB2-TO   / UADT010  → creditToAccount()
 *   WRITE-TO-PROCTRAN-DB2  / WTPD010   → writeProcessedTransaction()
 * </pre>
 *
 * <h2>Business rules preserved from COBOL</h2>
 * <ol>
 *   <li>Amount must be positive (COMM-AMT &lt;= ZERO → fail code '4')</li>
 *   <li>Same-account transfer is rejected with an abend (ABCODE 'SAME')</li>
 *   <li>Accounts are updated in account-number order to prevent deadlocks
 *       (the COBOL compares COMM-FACCNO vs COMM-TACCNO)</li>
 *   <li>FROM account: available_balance -= amount, actual_balance -= amount</li>
 *   <li>TO account:   available_balance += amount, actual_balance += amount</li>
 *   <li>No overdraft-limit enforcement (per COBOL comment at line 21)</li>
 *   <li>Missing FROM account → fail code '1'; missing TO account → fail code '2';
 *       Db2 error → fail code '3'</li>
 *   <li>If either account update fails, the entire transaction rolls back
 *       (Spring @Transactional replaces EXEC CICS SYNCPOINT ROLLBACK)</li>
 *   <li>On success, a PROCTRAN record with type 'TFR' is inserted</li>
 *   <li>The PROCTRAN description encodes "TRANSFER" + target sort code +
 *       target account number (PROC-TRAN-DESC-XFR layout)</li>
 * </ol>
 *
 * <h2>COBOL sort code default</h2>
 * The SORTCODE copybook defines: 77 SORTCODE PIC 9(6) VALUE 987654.
 * This is used as the default when no sort code is supplied.
 */
@Service
public class TransferFundsService
{


	private static final Logger log = LoggerFactory
			.getLogger(TransferFundsService.class);

	private static final String DEFAULT_SORT_CODE = "987654";

	private static final String PROCTRAN_EYECATCHER = "PRTR";

	private static final String PROCTRAN_TYPE_TRANSFER = "TFR";

	private static final String SUCCESS_YES = "Y";

	private static final String SUCCESS_NO = "N";

	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter
			.ofPattern("dd.MM.yyyy");

	private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
			.ofPattern("HHmmss");

	private final AccountRepository accountRepository;

	private final ProcessedTransactionRepository proctranRepository;


	public TransferFundsService(AccountRepository accountRepository,
			ProcessedTransactionRepository proctranRepository)
	{
		this.accountRepository = accountRepository;
		this.proctranRepository = proctranRepository;
	}


	/**
	 * Execute a funds transfer — the modernised equivalent of XFRFUN's
	 * PREMIERE section.
	 *
	 * COBOL: A010
	 *   MOVE SORTCODE TO COMM-FSCODE COMM-TSCODE.
	 *   IF COMM-AMT <= ZERO → fail code '4'
	 *   PERFORM UPDATE-ACCOUNT-DB2
	 *   PERFORM GET-ME-OUT-OF-HERE
	 */
	@Transactional
	public TransferFundsResponse transferFunds(TransferFundsForm form)
	{
		TransferFundsResponse response = new TransferFundsResponse();
		response.setAmount(form.getAmount());
		response.setSuccess(SUCCESS_NO);

		String fromSortCode = normaliseSortCode(form.getFromSortCode());
		String toSortCode = normaliseSortCode(form.getToSortCode());
		String fromAccNo = padAccountNumber(form.getFromAccountNumber());
		String toAccNo = padAccountNumber(form.getToAccountNumber());

		response.setFromAccountNumber(fromAccNo);
		response.setFromSortCode(fromSortCode);
		response.setToAccountNumber(toAccNo);
		response.setToSortCode(toSortCode);

		// COBOL: IF COMM-AMT <= ZERO → MOVE '4' TO COMM-FAIL-CODE
		if (form.getAmount().compareTo(BigDecimal.ZERO) <= 0)
		{
			response.setFailCode("4");
			return response;
		}

		// COBOL: IF COMM-FACCNO = COMM-TACCNO AND COMM-FSCODE = COMM-TSCODE
		//        → EXEC CICS ABEND ABCODE('SAME')
		if (fromAccNo.equals(toAccNo) && fromSortCode.equals(toSortCode))
		{
			log.error("Cannot transfer to the same account: {}/{}",
					fromSortCode, fromAccNo);
			throw new SameAccountTransferException(fromSortCode, fromAccNo);
		}

		// COBOL deadlock-prevention: update accounts in account-number order.
		// If COMM-FACCNO < COMM-TACCNO → update FROM first, then TO.
		// Otherwise → update TO first, then FROM.
		boolean fromFirst = fromAccNo.compareTo(toAccNo) < 0;

		AccountEntity fromAccount;
		AccountEntity toAccount;

		if (fromFirst)
		{
			fromAccount = debitFromAccount(fromSortCode, fromAccNo,
					form.getAmount(), response);
			if (fromAccount == null)
			{
				return response;
			}
			toAccount = creditToAccount(toSortCode, toAccNo, form.getAmount(),
					response);
			if (toAccount == null)
			{
				throw new TransferRollbackException(
						"TO account not found or update failed, rolling back FROM debit");
			}
		}
		else
		{
			toAccount = creditToAccount(toSortCode, toAccNo, form.getAmount(),
					response);
			if (toAccount == null)
			{
				return response;
			}
			fromAccount = debitFromAccount(fromSortCode, fromAccNo,
					form.getAmount(), response);
			if (fromAccount == null)
			{
				throw new TransferRollbackException(
						"FROM account not found or update failed, rolling back TO credit");
			}
		}

		// COBOL: PERFORM WRITE-TO-PROCTRAN
		writeProcessedTransaction(fromSortCode, fromAccNo, toSortCode, toAccNo,
				form.getAmount());

		// COBOL: MOVE 'Y' TO COMM-SUCCESS
		response.setSuccess(SUCCESS_YES);
		response.setFailCode("0");
		response.setFromAvailableBalance(fromAccount.getAvailableBalance());
		response.setFromActualBalance(fromAccount.getActualBalance());
		response.setToAvailableBalance(toAccount.getAvailableBalance());
		response.setToActualBalance(toAccount.getActualBalance());

		log.info("Transfer successful: {} from {}/{} to {}/{}",
				form.getAmount(), fromSortCode, fromAccNo, toSortCode,
				toAccNo);

		return response;
	}


	/**
	 * Debit the FROM account.
	 *
	 * COBOL: UPDATE-ACCOUNT-DB2-FROM / UADF010
	 *   SELECT ... FROM ACCOUNT WHERE (ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?)
	 *   IF SQLCODE NOT = 0:
	 *     SQLCODE = +100 → fail code '1' (not found)
	 *     else           → fail code '3' (Db2 error)
	 *   COMPUTE HV-ACCOUNT-AVAIL-BAL = HV-ACCOUNT-AVAIL-BAL - COMM-AMT
	 *   COMPUTE HV-ACCOUNT-ACTUAL-BAL = HV-ACCOUNT-ACTUAL-BAL - COMM-AMT
	 *   UPDATE ACCOUNT SET ... WHERE ...
	 *   MOVE HV-ACCOUNT-AVAIL-BAL TO COMM-FAVBAL
	 *   MOVE HV-ACCOUNT-ACTUAL-BAL TO COMM-FACTBAL
	 *   MOVE 'Y' TO COMM-SUCCESS
	 *
	 * @return the updated AccountEntity, or null if account not found
	 */
	AccountEntity debitFromAccount(String sortCode, String accountNumber,
			BigDecimal amount, TransferFundsResponse response)
	{
		Optional<AccountEntity> optAccount = accountRepository
				.findBySortcodeAndAccountNumber(sortCode, accountNumber);

		if (optAccount.isEmpty())
		{
			log.warn("FROM account not found: {}/{}", sortCode, accountNumber);
			response.setSuccess(SUCCESS_NO);
			response.setFailCode("1");
			return null;
		}

		AccountEntity account = optAccount.get();
		account.setAvailableBalance(
				account.getAvailableBalance().subtract(amount));
		account.setActualBalance(account.getActualBalance().subtract(amount));
		accountRepository.save(account);

		response.setFromAvailableBalance(account.getAvailableBalance());
		response.setFromActualBalance(account.getActualBalance());

		return account;
	}


	/**
	 * Credit the TO account.
	 *
	 * COBOL: UPDATE-ACCOUNT-DB2-TO / UADT010
	 *   SELECT ... FROM ACCOUNT WHERE (ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?)
	 *   IF SQLCODE NOT = 0:
	 *     SQLCODE = +100 → fail code '2' (not found)
	 *     else           → fail code '3' (Db2 error)
	 *   COMPUTE HV-ACCOUNT-AVAIL-BAL = HV-ACCOUNT-AVAIL-BAL + COMM-AMT
	 *   COMPUTE HV-ACCOUNT-ACTUAL-BAL = HV-ACCOUNT-ACTUAL-BAL + COMM-AMT
	 *   UPDATE ACCOUNT SET ... WHERE ...
	 *   MOVE HV-ACCOUNT-AVAIL-BAL TO COMM-TAVBAL
	 *   MOVE HV-ACCOUNT-ACTUAL-BAL TO COMM-TACTBAL
	 *   MOVE 'Y' TO COMM-SUCCESS
	 *
	 * @return the updated AccountEntity, or null if account not found
	 */
	AccountEntity creditToAccount(String sortCode, String accountNumber,
			BigDecimal amount, TransferFundsResponse response)
	{
		Optional<AccountEntity> optAccount = accountRepository
				.findBySortcodeAndAccountNumber(sortCode, accountNumber);

		if (optAccount.isEmpty())
		{
			log.warn("TO account not found: {}/{}", sortCode, accountNumber);
			response.setSuccess(SUCCESS_NO);
			response.setFailCode("2");
			return null;
		}

		AccountEntity account = optAccount.get();
		account.setAvailableBalance(account.getAvailableBalance().add(amount));
		account.setActualBalance(account.getActualBalance().add(amount));
		accountRepository.save(account);

		response.setToAvailableBalance(account.getAvailableBalance());
		response.setToActualBalance(account.getActualBalance());

		return account;
	}


	/**
	 * Write a PROCTRAN audit record for the successful transfer.
	 *
	 * COBOL: WRITE-TO-PROCTRAN-DB2 / WTPD010
	 *   MOVE 'PRTR' TO HV-PROCTRAN-EYECATCHER
	 *   MOVE COMM-FSCODE TO HV-PROCTRAN-SORT-CODE
	 *   MOVE COMM-FACCNO TO HV-PROCTRAN-ACC-NUMBER
	 *   MOVE EIBTASKN TO WS-EIBTASKN12 → HV-PROCTRAN-REF
	 *   SET PROC-TY-TRANSFER TO TRUE  (type = 'TFR')
	 *   MOVE COMM-AMT TO HV-PROCTRAN-AMOUNT
	 *   Description layout (PROC-TRAN-DESC-XFR):
	 *     26 bytes: 'TRANSFER' padded to 26 chars
	 *      6 bytes: target sort code
	 *      8 bytes: target account number
	 *   INSERT INTO PROCTRAN (...)
	 *   IF SQLCODE NOT = 0 → EXEC CICS ABEND ABCODE('WPCD')
	 */
	void writeProcessedTransaction(String fromSortCode,
			String fromAccountNumber, String toSortCode,
			String toAccountNumber, BigDecimal amount)
	{
		LocalDateTime now = LocalDateTime.now();

		ProcessedTransactionEntity proctran = new ProcessedTransactionEntity();
		proctran.setEyecatcher(PROCTRAN_EYECATCHER);
		proctran.setSortcode(fromSortCode);
		proctran.setAccountNumber(fromAccountNumber);
		proctran.setTransactionDate(now.format(DATE_FORMATTER));
		proctran.setTransactionTime(now.format(TIME_FORMATTER));
		proctran.setReference(
				String.format("%012d", Thread.currentThread().getId()));
		proctran.setType(PROCTRAN_TYPE_TRANSFER);

		// COBOL PROC-TRAN-DESC-XFR layout: 26-char header + 6-char sortcode
		// + 8-char account
		String description = String.format("%-26s%6s%8s", "TRANSFER",
				toSortCode, toAccountNumber);
		proctran.setDescription(description);
		proctran.setAmount(amount);

		proctranRepository.save(proctran);

		log.info("PROCTRAN record written for transfer {}/{} -> {}/{}",
				fromSortCode, fromAccountNumber, toSortCode, toAccountNumber);
	}


	/**
	 * Normalise sort code, defaulting to '987654' per SORTCODE.cpy.
	 *
	 * COBOL: MOVE SORTCODE TO COMM-FSCODE COMM-TSCODE.
	 */
	String normaliseSortCode(String sortCode)
	{
		if (sortCode == null || sortCode.isBlank())
		{
			return DEFAULT_SORT_CODE;
		}
		return String.format("%6s", sortCode).replace(' ', '0');
	}


	/**
	 * Pad account number to 8 digits with leading zeros.
	 *
	 * COBOL: PIC 9(8) — numeric display with leading zeros.
	 */
	String padAccountNumber(String accountNumber)
	{
		return String.format("%8s", accountNumber).replace(' ', '0');
	}
}
