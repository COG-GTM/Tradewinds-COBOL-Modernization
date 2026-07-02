/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.ibm.cics.cip.bank.springboot.transferfunds.jsonclasses.transferfunds.TransferFundsForm;
import com.ibm.cics.cip.bank.springboot.transferfunds.jsonclasses.transferfunds.TransferFundsResponse;
import com.ibm.cics.cip.bank.springboot.transferfunds.model.AccountEntity;
import com.ibm.cics.cip.bank.springboot.transferfunds.model.AccountRepository;
import com.ibm.cics.cip.bank.springboot.transferfunds.model.ProcessedTransactionEntity;
import com.ibm.cics.cip.bank.springboot.transferfunds.model.ProcessedTransactionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests capturing the legacy XFRFUN.cbl business rules.
 *
 * These tests encode the COBOL semantics so future refactors cannot silently
 * drift from mainframe behavior. Each test documents which COBOL paragraph /
 * business rule it validates.
 */
@SpringBootTest
class TransferFundsServiceTest
{


	@Autowired
	private TransferFundsService service;

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


	private AccountEntity createAccount(String sortcode, String accountNumber,
			BigDecimal availableBalance, BigDecimal actualBalance)
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
		account.setAvailableBalance(availableBalance);
		account.setActualBalance(actualBalance);
		return accountRepository.save(account);
	}


	@Nested
	@DisplayName("Happy-path transfer (COBOL: PREMIERE -> UPDATE-ACCOUNT-DB2 -> WRITE-TO-PROCTRAN)")
	class HappyPath
	{


		@Test
		@DisplayName("Transfer $100 between two accounts — balances update correctly and PROCTRAN is written")
		void happyPathTransfer()
		{
			// COBOL: SORTCODE.cpy -> 77 SORTCODE PIC 9(6) VALUE 987654
			String sortCode = "987654";
			createAccount(sortCode, "00000001", new BigDecimal("1000.00"),
					new BigDecimal("1000.00"));
			createAccount(sortCode, "00000002", new BigDecimal("500.00"),
					new BigDecimal("500.00"));

			TransferFundsForm form = new TransferFundsForm("00000001",
					sortCode, "00000002", sortCode, new BigDecimal("100.00"));

			TransferFundsResponse response = service.transferFunds(form);

			// COBOL: MOVE 'Y' TO COMM-SUCCESS
			assertEquals("Y", response.getSuccess());
			assertEquals("0", response.getFailCode());

			// COBOL: COMPUTE HV-ACCOUNT-AVAIL-BAL =
			//        HV-ACCOUNT-AVAIL-BAL - COMM-AMT (FROM)
			// COBOL: MOVE HV-ACCOUNT-AVAIL-BAL TO COMM-FAVBAL
			assertEquals(new BigDecimal("900.00"),
					response.getFromAvailableBalance());
			assertEquals(new BigDecimal("900.00"),
					response.getFromActualBalance());

			// COBOL: COMPUTE HV-ACCOUNT-AVAIL-BAL =
			//        HV-ACCOUNT-AVAIL-BAL + COMM-AMT (TO)
			// COBOL: MOVE HV-ACCOUNT-AVAIL-BAL TO COMM-TAVBAL
			assertEquals(new BigDecimal("600.00"),
					response.getToAvailableBalance());
			assertEquals(new BigDecimal("600.00"),
					response.getToActualBalance());

			// COBOL: PERFORM WRITE-TO-PROCTRAN -> INSERT INTO PROCTRAN
			List<ProcessedTransactionEntity> proctrans = proctranRepository
					.findAll();
			assertEquals(1, proctrans.size());

			ProcessedTransactionEntity proctran = proctrans.get(0);
			// COBOL: MOVE 'PRTR' TO HV-PROCTRAN-EYECATCHER
			assertEquals("PRTR", proctran.getEyecatcher());
			// COBOL: SET PROC-TY-TRANSFER TO TRUE -> 'TFR'
			assertEquals("TFR", proctran.getType());
			// COBOL: MOVE COMM-AMT TO HV-PROCTRAN-AMOUNT
			assertEquals(0,
					new BigDecimal("100.00").compareTo(proctran.getAmount()));
			// COBOL: MOVE COMM-FSCODE TO HV-PROCTRAN-SORT-CODE
			assertEquals(sortCode, proctran.getSortcode());
			// COBOL: MOVE COMM-FACCNO TO HV-PROCTRAN-ACC-NUMBER
			assertEquals("00000001", proctran.getAccountNumber());
			// COBOL: description = 'TRANSFER' (26 chars) + toSortCode +
			// toAccNo
			assertNotNull(proctran.getDescription());
			assertTrue(proctran.getDescription().startsWith("TRANSFER"));
			assertTrue(proctran.getDescription().contains("00000002"));
		}


		@Test
		@DisplayName("Packed-decimal precision: $0.01 transfer preserves cents exactly")
		void packedDecimalPrecision()
		{
			// COBOL: PIC S9(10)V99 COMP-3 -- two decimal places
			createAccount("987654", "00000010", new BigDecimal("100.00"),
					new BigDecimal("100.00"));
			createAccount("987654", "00000020", new BigDecimal("50.00"),
					new BigDecimal("50.00"));

			TransferFundsForm form = new TransferFundsForm("00000010", "987654",
					"00000020", "987654", new BigDecimal("0.01"));

			TransferFundsResponse response = service.transferFunds(form);

			assertEquals("Y", response.getSuccess());
			assertEquals(new BigDecimal("99.99"),
					response.getFromAvailableBalance());
			assertEquals(new BigDecimal("50.01"),
					response.getToAvailableBalance());
		}


		@Test
		@DisplayName("Large transfer amount — PIC S9(10)V99 supports up to 9,999,999,999.99")
		void largeAmountTransfer()
		{
			BigDecimal largeBalance = new BigDecimal("9999999999.99");
			createAccount("987654", "00000030", largeBalance, largeBalance);
			createAccount("987654", "00000040", BigDecimal.ZERO,
					BigDecimal.ZERO);

			TransferFundsForm form = new TransferFundsForm("00000030", "987654",
					"00000040", "987654", largeBalance);

			TransferFundsResponse response = service.transferFunds(form);

			assertEquals("Y", response.getSuccess());
			assertEquals(0, BigDecimal.ZERO
					.compareTo(response.getFromAvailableBalance()));
			assertEquals(0,
					largeBalance.compareTo(response.getToAvailableBalance()));
		}
	}


	@Nested
	@DisplayName("Negative-amount rejection (COBOL: A010 — IF COMM-AMT <= ZERO)")
	class NegativeAmount
	{


		@Test
		@DisplayName("Zero amount -> fail code '4', no database changes")
		void zeroAmountRejected()
		{
			createAccount("987654", "00000001", new BigDecimal("1000.00"),
					new BigDecimal("1000.00"));
			createAccount("987654", "00000002", new BigDecimal("500.00"),
					new BigDecimal("500.00"));

			TransferFundsForm form = new TransferFundsForm("00000001",
					"987654", "00000002", "987654", BigDecimal.ZERO);

			TransferFundsResponse response = service.transferFunds(form);

			// COBOL: MOVE 'N' TO COMM-SUCCESS, MOVE '4' TO COMM-FAIL-CODE
			assertEquals("N", response.getSuccess());
			assertEquals("4", response.getFailCode());

			// No PROCTRAN should be written
			assertEquals(0, proctranRepository.count());
		}


		@Test
		@DisplayName("Negative amount -> fail code '4'")
		void negativeAmountRejected()
		{
			createAccount("987654", "00000001", new BigDecimal("1000.00"),
					new BigDecimal("1000.00"));
			createAccount("987654", "00000002", new BigDecimal("500.00"),
					new BigDecimal("500.00"));

			TransferFundsForm form = new TransferFundsForm("00000001",
					"987654", "00000002", "987654",
					new BigDecimal("-50.00"));

			TransferFundsResponse response = service.transferFunds(form);

			assertEquals("N", response.getSuccess());
			assertEquals("4", response.getFailCode());
		}
	}


	@Nested
	@DisplayName("Same-account rejection (COBOL: UAD010 — ABCODE 'SAME')")
	class SameAccount
	{


		@Test
		@DisplayName("Same sortcode + account number -> SameAccountTransferException")
		void sameAccountThrows()
		{
			createAccount("987654", "00000001", new BigDecimal("1000.00"),
					new BigDecimal("1000.00"));

			TransferFundsForm form = new TransferFundsForm("00000001",
					"987654", "00000001", "987654",
					new BigDecimal("100.00"));

			// COBOL: EXEC CICS ABEND ABCODE('SAME')
			assertThrows(SameAccountTransferException.class,
					() -> service.transferFunds(form));
		}
	}


	@Nested
	@DisplayName("Missing-account failures (COBOL: SQLCODE = +100)")
	class MissingAccount
	{


		@Test
		@DisplayName("FROM account not found (FROM < TO) -> fail code '1', clean return")
		void fromAccountNotFound()
		{
			// Create only the TO account with a higher number.
			// FROM(00000001) < TO(00000099) so FROM is updated first.
			// FROM not found -> SQLCODE +100 -> fail code '1', clean return.
			// COBOL: UPDATE-ACCOUNT-DB2-FROM / UADF010
			createAccount("987654", "00000099", new BigDecimal("500.00"),
					new BigDecimal("500.00"));

			TransferFundsForm form = new TransferFundsForm("00000001",
					"987654", "00000099", "987654",
					new BigDecimal("100.00"));

			TransferFundsResponse response = service.transferFunds(form);

			assertEquals("N", response.getSuccess());
			assertEquals("1", response.getFailCode());

			// TO account balance unchanged since FROM failed first
			AccountEntity toAccount = accountRepository
					.findBySortcodeAndAccountNumber("987654", "00000099")
					.orElseThrow();
			assertEquals(new BigDecimal("500.00"),
					toAccount.getAvailableBalance());
		}


		@Test
		@DisplayName("TO account not found (FROM < TO) -> transaction rolls back (COBOL SYNCPOINT ROLLBACK)")
		void toAccountNotFoundRollsBack()
		{
			// Create only the FROM account.
			// FROM(00000001) < TO(99999999) so FROM is updated first.
			// FROM succeeds, TO not found -> rollback.
			// COBOL: UPDATE-ACCOUNT-DB2-TO / UADT010
			// IF SQLCODE = +100 -> MOVE '2' TO COMM-FAIL-CODE
			// then EXEC CICS SYNCPOINT ROLLBACK
			createAccount("987654", "00000001", new BigDecimal("1000.00"),
					new BigDecimal("1000.00"));

			TransferFundsForm form = new TransferFundsForm("00000001",
					"987654", "99999999", "987654",
					new BigDecimal("100.00"));

			// The @Transactional rollback means the FROM debit is undone
			assertThrows(TransferRollbackException.class,
					() -> service.transferFunds(form));

			// Verify the FROM account balance was NOT changed (rollback)
			AccountEntity fromAccount = accountRepository
					.findBySortcodeAndAccountNumber("987654", "00000001")
					.orElseThrow();
			assertEquals(new BigDecimal("1000.00"),
					fromAccount.getAvailableBalance());
		}
	}


	@Nested
	@DisplayName("Atomic rollback (COBOL: SYNCPOINT ROLLBACK on datastore failure)")
	class AtomicRollback
	{


		@Test
		@DisplayName("When TO account missing after FROM updated, both accounts roll back")
		void atomicRollbackOnSecondLegFailure()
		{
			// FROM(00000001) < TO(00000002) so FROM is updated first.
			// FROM succeeds, TO (00000002) doesn't exist -> rollback
			createAccount("987654", "00000001", new BigDecimal("500.00"),
					new BigDecimal("500.00"));

			TransferFundsForm form = new TransferFundsForm("00000001",
					"987654", "00000002", "987654",
					new BigDecimal("100.00"));

			assertThrows(TransferRollbackException.class,
					() -> service.transferFunds(form));

			// FROM account balance must be unchanged due to rollback
			AccountEntity fromAccount = accountRepository
					.findBySortcodeAndAccountNumber("987654", "00000001")
					.orElseThrow();
			assertEquals(new BigDecimal("500.00"),
					fromAccount.getAvailableBalance());
			assertEquals(new BigDecimal("500.00"),
					fromAccount.getActualBalance());

			// No PROCTRAN record should exist
			assertEquals(0, proctranRepository.count());
		}
	}


	@Nested
	@DisplayName("Sort code and account number normalisation")
	class Normalisation
	{


		@Test
		@DisplayName("Default sort code applied when omitted (COBOL: SORTCODE.cpy VALUE 987654)")
		void defaultSortCode()
		{
			createAccount("987654", "00000001", new BigDecimal("1000.00"),
					new BigDecimal("1000.00"));
			createAccount("987654", "00000002", new BigDecimal("500.00"),
					new BigDecimal("500.00"));

			// Omit sort codes — should default to 987654
			TransferFundsForm form = new TransferFundsForm("00000001", null,
					"00000002", null, new BigDecimal("50.00"));

			TransferFundsResponse response = service.transferFunds(form);

			assertEquals("Y", response.getSuccess());
			assertEquals("987654", response.getFromSortCode());
			assertEquals("987654", response.getToSortCode());
		}


		@Test
		@DisplayName("Account number padded with leading zeros (COBOL: PIC 9(8))")
		void accountNumberPadding()
		{
			createAccount("987654", "00000001", new BigDecimal("1000.00"),
					new BigDecimal("1000.00"));
			createAccount("987654", "00000002", new BigDecimal("500.00"),
					new BigDecimal("500.00"));

			// Short account numbers should be zero-padded
			TransferFundsForm form = new TransferFundsForm("1", "987654", "2",
					"987654", new BigDecimal("25.00"));

			TransferFundsResponse response = service.transferFunds(form);

			assertEquals("Y", response.getSuccess());
			assertEquals("00000001", response.getFromAccountNumber());
			assertEquals("00000002", response.getToAccountNumber());
		}
	}


	@Nested
	@DisplayName("Deadlock-prevention ordering (COBOL: IF COMM-FACCNO < COMM-TACCNO)")
	class AccountOrdering
	{


		@Test
		@DisplayName("FROM > TO: TO is updated first, then FROM (reverse order)")
		void reverseOrderWhenFromGreaterThanTo()
		{
			createAccount("987654", "00000099", new BigDecimal("1000.00"),
					new BigDecimal("1000.00"));
			createAccount("987654", "00000001", new BigDecimal("500.00"),
					new BigDecimal("500.00"));

			// FROM (99) > TO (01), so TO should be credited first
			TransferFundsForm form = new TransferFundsForm("00000099",
					"987654", "00000001", "987654",
					new BigDecimal("200.00"));

			TransferFundsResponse response = service.transferFunds(form);

			assertEquals("Y", response.getSuccess());
			assertEquals(new BigDecimal("800.00"),
					response.getFromAvailableBalance());
			assertEquals(new BigDecimal("700.00"),
					response.getToAvailableBalance());
		}
	}


	@Nested
	@DisplayName("No overdraft limit enforcement (COBOL comment: line 21)")
	class NoOverdraftLimit
	{


		@Test
		@DisplayName("Transfer more than available balance succeeds — no overdraft check")
		void overdraftAllowed()
		{
			// COBOL: "No checking is made on overdraft limits."
			createAccount("987654", "00000001", new BigDecimal("100.00"),
					new BigDecimal("100.00"));
			createAccount("987654", "00000002", new BigDecimal("500.00"),
					new BigDecimal("500.00"));

			TransferFundsForm form = new TransferFundsForm("00000001",
					"987654", "00000002", "987654",
					new BigDecimal("200.00"));

			TransferFundsResponse response = service.transferFunds(form);

			assertEquals("Y", response.getSuccess());
			// Balance goes negative — COBOL allows this
			assertEquals(new BigDecimal("-100.00"),
					response.getFromAvailableBalance());
			assertEquals(new BigDecimal("-100.00"),
					response.getFromActualBalance());
			assertEquals(new BigDecimal("700.00"),
					response.getToAvailableBalance());
		}
	}
}
