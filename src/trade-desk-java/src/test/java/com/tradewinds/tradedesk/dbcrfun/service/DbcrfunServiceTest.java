package com.tradewinds.tradedesk.dbcrfun.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tradewinds.tradedesk.dbcrfun.dto.DbcrfunRequest;
import com.tradewinds.tradedesk.dbcrfun.dto.DbcrfunResponse;
import com.tradewinds.tradedesk.dbcrfun.dto.Origin;
import com.tradewinds.tradedesk.dbcrfun.entity.Account;
import com.tradewinds.tradedesk.dbcrfun.entity.ProcTran;
import com.tradewinds.tradedesk.dbcrfun.repository.AccountRepository;
import com.tradewinds.tradedesk.dbcrfun.repository.ProcTranRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class DbcrfunServiceTest {

    private static final String SORTCODE = "987654";
    private static final String ACCNO = "00000001";
    private static final String REF = "000000000042";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-17T10:15:30Z"), ZoneId.of("UTC"));

    private static final Origin TELLER = new Origin("CBSAAPPL", "TELLER01", "TERM0001", "NETWORK1", 0);
    private static final Origin PAYMENT = new Origin("PAYAPPL", "PAYUSER01", "PAYFAC01", "PAYNET01", 496);

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private ProcTranRepository procTranRepository;
    @Mock
    private TransactionRollback transactionRollback;

    private DbcrfunService service;

    @BeforeEach
    void setUp() {
        service = new DbcrfunService(
                accountRepository, procTranRepository, transactionRollback, () -> REF, CLOCK, SORTCODE);
    }

    // 1
    @Test
    void accountNotFoundFailsWithCode1() {
        when(accountRepository.findForUpdate(SORTCODE, ACCNO)).thenReturn(Optional.empty());

        DbcrfunResponse response = service.process(request("-10.00", TELLER));

        assertFailure(response, "1");
        verify(accountRepository, never()).saveAndFlush(any());
        verify(procTranRepository, never()).saveAndFlush(any());
    }

    // 2
    @Test
    void selectErrorFailsWithCode2() {
        when(accountRepository.findForUpdate(SORTCODE, ACCNO))
                .thenThrow(new DataAccessResourceFailureException("SQLCODE -923"));

        DbcrfunResponse response = service.process(request("-10.00", TELLER));

        assertFailure(response, "2");
        verify(transactionRollback).markRollbackOnly();
        verify(accountRepository, never()).saveAndFlush(any());
    }

    // 3
    @Test
    void paymentDebitOnHospitalAccountFailsWithCode4() {
        Account account = givenAccount("HOSPITAL", "1000.00");

        DbcrfunResponse response = service.process(request("-10.00", PAYMENT));

        assertFailure(response, "4");
        assertUnchanged(account, "1000.00");
    }

    // 4
    @Test
    void paymentDebitOnTenderAccountFailsWithCode4() {
        Account account = givenAccount("TENDER  ", "1000.00");

        DbcrfunResponse response = service.process(request("-10.00", PAYMENT));

        assertFailure(response, "4");
        assertUnchanged(account, "1000.00");
    }

    @Test
    void tenderWithoutTrailingSpacesIsStillRestricted() {
        Account account = givenAccount("TENDER", "1000.00");

        assertFailure(service.process(request("-10.00", PAYMENT)), "4");
        assertUnchanged(account, "1000.00");
    }

    // 5
    @Test
    void paymentDebitWithInsufficientFundsFailsWithCode3() {
        Account account = givenAccount("CURRENT ", "50.00");

        DbcrfunResponse response = service.process(request("-50.01", PAYMENT));

        assertFailure(response, "3");
        assertUnchanged(account, "50.00");
    }

    @Test
    void paymentDebitOfExactAvailableBalanceSucceeds() {
        givenAccount("CURRENT ", "50.00");
        givenSavesSucceed();

        DbcrfunResponse response = service.process(request("-50.00", PAYMENT));

        assertSuccess(response, "0.00", "0.00");
    }

    // 6
    @Test
    void tellerDebitWithInsufficientFundsIsAllowedToOverdraw() {
        Account account = givenAccount("CURRENT ", "50.00");
        givenSavesSucceed();

        DbcrfunResponse response = service.process(request("-80.00", TELLER));

        assertSuccess(response, "-30.00", "-30.00");
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("-30.00");
        assertThat(capturedProcTran().getType()).isEqualTo("DEB");
    }

    @Test
    void tellerDebitOnHospitalAccountIsAllowed() {
        givenAccount("HOSPITAL", "100.00");
        givenSavesSucceed();

        assertSuccess(service.process(request("-10.00", TELLER)), "90.00", "90.00");
    }

    // 7
    @Test
    void paymentCreditOnHospitalAccountFailsWithCode4() {
        Account account = givenAccount("HOSPITAL", "1000.00");

        assertFailure(service.process(request("25.00", PAYMENT)), "4");
        assertUnchanged(account, "1000.00");
    }

    @Test
    void paymentCreditOnTenderAccountFailsWithCode4() {
        Account account = givenAccount("TENDER  ", "1000.00");

        assertFailure(service.process(request("25.00", PAYMENT)), "4");
        assertUnchanged(account, "1000.00");
        verify(procTranRepository, never()).saveAndFlush(any());
    }

    // 8
    @Test
    void updateFailureFailsWithCode2() {
        givenAccount("CURRENT ", "100.00");
        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenThrow(new DataIntegrityViolationException("update failed"));

        DbcrfunResponse response = service.process(request("-10.00", TELLER));

        assertThat(response.success()).isEqualTo("N");
        assertThat(response.failCode()).isEqualTo("2");
        verify(transactionRollback).markRollbackOnly();
        verify(procTranRepository, never()).saveAndFlush(any());
    }

    // 9
    @Test
    void procTranInsertFailureFailsWith02AndRollsBackAccountUpdate() {
        givenAccount("CURRENT ", "100.00");
        when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(procTranRepository.saveAndFlush(any(ProcTran.class)))
                .thenThrow(new DataIntegrityViolationException("insert failed"));

        DbcrfunResponse response = service.process(request("-10.00", TELLER));

        assertThat(response.success()).isEqualTo("N");
        assertThat(response.failCode()).isEqualTo("02");
        verify(accountRepository).saveAndFlush(any(Account.class));
        verify(transactionRollback).markRollbackOnly();
    }

    // 10
    @Test
    void tellerDebitSucceedsAndWritesDebProcTran() {
        Account account = givenAccount("CURRENT ", "100.00", "120.00");
        givenSavesSucceed();

        DbcrfunResponse response = service.process(request("-10.50", TELLER));

        assertSuccess(response, "89.50", "109.50");
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("89.50");
        assertThat(account.getActualBalance()).isEqualByComparingTo("109.50");
        verify(accountRepository).saveAndFlush(account);
        verify(transactionRollback, never()).markRollbackOnly();

        ProcTran tran = capturedProcTran();
        assertThat(tran.getEyecatcher()).isEqualTo("PRTR");
        assertThat(tran.getSortcode()).isEqualTo(SORTCODE);
        assertThat(tran.getNumber()).isEqualTo(ACCNO);
        assertThat(tran.getDate()).isEqualTo("17.11.2026");
        assertThat(tran.getTime()).isEqualTo("101530");
        assertThat(tran.getRef()).isEqualTo(REF);
        assertThat(tran.getType()).isEqualTo("DEB");
        assertThat(tran.getDesc()).isEqualTo("COUNTER WTHDRW");
        assertThat(tran.getAmount()).isEqualByComparingTo("-10.50");
    }

    // 11
    @Test
    void paymentDebitSucceedsAndWritesPdrWithOriginPrefix() {
        givenAccount("CURRENT ", "100.00");
        givenSavesSucceed();

        DbcrfunResponse response = service.process(request("-40.00", PAYMENT));

        assertSuccess(response, "60.00", "60.00");
        ProcTran tran = capturedProcTran();
        assertThat(tran.getType()).isEqualTo("PDR");
        // APPLID "PAYAPPL" padded to 8 + first 6 bytes of USERID "PAYUSER01".
        assertThat(tran.getDesc()).isEqualTo("PAYAPPL PAYUSE").hasSize(14);
        assertThat(tran.getAmount()).isEqualByComparingTo("-40.00");
    }

    // 12a
    @Test
    void tellerCreditSucceedsAndWritesCreProcTran() {
        givenAccount("CURRENT ", "100.00");
        givenSavesSucceed();

        DbcrfunResponse response = service.process(request("25.25", TELLER));

        assertSuccess(response, "125.25", "125.25");
        ProcTran tran = capturedProcTran();
        assertThat(tran.getType()).isEqualTo("CRE");
        assertThat(tran.getDesc()).isEqualTo("COUNTER RECVED");
        assertThat(tran.getAmount()).isEqualByComparingTo("25.25");
    }

    // 12b
    @Test
    void paymentCreditSucceedsAndWritesPcrWithOriginPrefix() {
        givenAccount("CURRENT ", "100.00");
        givenSavesSucceed();

        DbcrfunResponse response = service.process(request("25.00", PAYMENT));

        assertSuccess(response, "125.00", "125.00");
        ProcTran tran = capturedProcTran();
        assertThat(tran.getType()).isEqualTo("PCR");
        assertThat(tran.getDesc()).isEqualTo("PAYAPPL PAYUSE");
    }

    @Test
    void zeroAmountIsTreatedAsCredit() {
        givenAccount("CURRENT ", "100.00");
        givenSavesSucceed();

        assertSuccess(service.process(request("0.00", TELLER)), "100.00", "100.00");
        assertThat(capturedProcTran().getType()).isEqualTo("CRE");
    }

    @Test
    void requestSortCodeIsIgnoredAndShortAccountNumberIsZeroFilled() {
        givenAccount("CURRENT ", "100.00");
        givenSavesSucceed();

        service.process(new DbcrfunRequest("1", new BigDecimal("5.00"), "111111", TELLER));

        verify(accountRepository).findForUpdate(SORTCODE, ACCNO);
    }

    private DbcrfunRequest request(String amt, Origin origin) {
        return new DbcrfunRequest(ACCNO, new BigDecimal(amt), "111111", origin);
    }

    private Account givenAccount(String type, String balance) {
        return givenAccount(type, balance, balance);
    }

    private Account givenAccount(String type, String available, String actual) {
        Account account = new Account();
        account.setEyecatcher("ACCT");
        account.setCustomerNumber("0000000001");
        account.setSortcode(SORTCODE);
        account.setNumber(ACCNO);
        account.setAccountType(type);
        account.setAvailableBalance(new BigDecimal(available));
        account.setActualBalance(new BigDecimal(actual));
        when(accountRepository.findForUpdate(SORTCODE, ACCNO)).thenReturn(Optional.of(account));
        return account;
    }

    private void givenSavesSucceed() {
        when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(procTranRepository.saveAndFlush(any(ProcTran.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ProcTran capturedProcTran() {
        ArgumentCaptor<ProcTran> captor = ArgumentCaptor.forClass(ProcTran.class);
        verify(procTranRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private void assertFailure(DbcrfunResponse response, String failCode) {
        assertThat(response.success()).isEqualTo("N");
        assertThat(response.failCode()).isEqualTo(failCode);
    }

    private void assertSuccess(DbcrfunResponse response, String avBal, String actBal) {
        assertThat(response.success()).isEqualTo("Y");
        assertThat(response.failCode()).isEqualTo("0");
        assertThat(response.avBal()).isEqualByComparingTo(avBal);
        assertThat(response.actBal()).isEqualByComparingTo(actBal);
    }

    private void assertUnchanged(Account account, String balance) {
        assertThat(account.getAvailableBalance()).isEqualByComparingTo(balance);
        assertThat(account.getActualBalance()).isEqualByComparingTo(balance);
        verify(accountRepository, never()).saveAndFlush(any());
        verify(procTranRepository, never()).saveAndFlush(any());
    }
}
