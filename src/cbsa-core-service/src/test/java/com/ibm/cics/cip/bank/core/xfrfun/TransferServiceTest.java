package com.ibm.cics.cip.bank.core.xfrfun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.proctran.ProctranWriter;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Unit tests of the UAD010 control flow (ordering, fail codes, abends) with mocked data access. */
class TransferServiceTest {

    private static final String SC = "987654";

    private AccountRepository accounts;
    private ProctranWriter proctran;
    private TransferService service;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        proctran = mock(ProctranWriter.class);
        BankIdentity bank = mock(BankIdentity.class);
        when(bank.sortCode()).thenReturn(SC);
        service = new TransferService(accounts, proctran, bank);
        when(accounts.updateBalances(anyString(), anyLong(), any(), any())).thenReturn(1);
    }

    private static Account account(long number, String balance) {
        BigDecimal b = new BigDecimal(balance);
        return new Account("ACCT", number, SC, number, "ISA", new BigDecimal("1.50"), LocalDate.of(2020, 1, 1), 0,
                LocalDate.of(2025, 12, 1), LocalDate.of(2026, 1, 1), b, b);
    }

    private void exists(long number, String balance) {
        when(accounts.findByKey(SC, number)).thenReturn(Optional.of(account(number, balance)));
    }

    @Test
    void lowerFromAccountIsUpdatedFirst() {
        exists(3, "100.00");
        exists(8, "50.00");
        TransferResult r = service.transfer(3, 8, new BigDecimal("25.00"));
        InOrder order = inOrder(accounts, proctran);
        order.verify(accounts).updateBalances(SC, 3, new BigDecimal("75.00"), new BigDecimal("75.00"));
        order.verify(accounts).updateBalances(SC, 8, new BigDecimal("75.00"), new BigDecimal("75.00"));
        order.verify(proctran).transfer(SC, 3, SC, 8, new BigDecimal("25.00"));
        assertThat(r.from().availableBalance()).isEqualByComparingTo("75.00");
        assertThat(r.to().actualBalance()).isEqualByComparingTo("75.00");
        assertThat(r.amount()).isEqualByComparingTo("25.00");
    }

    @Test
    void lowerToAccountIsUpdatedFirst() {
        exists(9, "10.00");
        exists(5, "20.00");
        service.transfer(9, 5, BigDecimal.ONE);
        InOrder order = inOrder(accounts, proctran);
        order.verify(accounts).updateBalances(SC, 5, new BigDecimal("21.00"), new BigDecimal("21.00"));
        order.verify(accounts).updateBalances(SC, 9, new BigDecimal("9.00"), new BigDecimal("9.00"));
        order.verify(proctran).transfer(SC, 9, SC, 5, new BigDecimal("1.00"));
    }

    @Test
    void toMissingWithLowerToNeverTouchesFrom() {
        exists(9, "10.00");
        when(accounts.findByKey(SC, 5)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.transfer(9, 5, BigDecimal.ONE))
                .isInstanceOfSatisfying(CbsaException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.failCode()).isEqualTo("2");
                });
        verify(accounts, never()).findByKey(SC, 9);
        verify(accounts, never()).updateBalances(anyString(), anyLong(), any(), any());
        verifyNoInteractions(proctran);
    }

    @Test
    void validationHappensBeforeAnyDataAccess() {
        assertThatThrownBy(() -> service.transfer(1, 2, null))
                .isInstanceOfSatisfying(CbsaException.class, e -> assertThat(e.failCode()).isEqualTo("4"));
        assertThatThrownBy(() -> service.transfer(4, 4, BigDecimal.TEN))
                .isInstanceOfSatisfying(TransferAbendException.class, e -> {
                    assertThat(e.abendCode()).isEqualTo("SAME");
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verifyNoInteractions(accounts, proctran);
    }

    @Test
    void dataStoreErrorOnFromIsFailCode3AbendFrom() {
        when(accounts.findByKey(SC, 1)).thenThrow(new DataAccessResourceFailureException("db down"));
        assertThatThrownBy(() -> service.transfer(1, 2, BigDecimal.ONE))
                .isInstanceOfSatisfying(TransferAbendException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(e.failCode()).isEqualTo("3");
                    assertThat(e.abendCode()).isEqualTo("FROM");
                });
    }

    @Test
    void updateTouchingNoRowIsFailCode3AbendTo() {
        exists(1, "5.00");
        exists(2, "5.00");
        when(accounts.updateBalances(eq(SC), eq(2L), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.transfer(1, 2, BigDecimal.ONE))
                .isInstanceOfSatisfying(TransferAbendException.class, e -> {
                    assertThat(e.failCode()).isEqualTo("3");
                    assertThat(e.abendCode()).isEqualTo("TO  ");
                });
        verifyNoInteractions(proctran);
    }

    @Test
    void proctranFailureIsAbendWpcd() {
        exists(1, "5.00");
        exists(2, "5.00");
        when(proctran.transfer(anyString(), anyLong(), anyString(), anyLong(), any()))
                .thenThrow(new DataAccessResourceFailureException("insert failed"));
        assertThatThrownBy(() -> service.transfer(1, 2, BigDecimal.ONE))
                .isInstanceOfSatisfying(CbsaAbendException.class, e -> {
                    assertThat(e.abendCode()).isEqualTo("WPCD");
                    assertThat(e.program()).isEqualTo("XFRFUN");
                });
    }

    @Test
    void transferIsOneRepeatableReadTransaction() throws NoSuchMethodException {
        Transactional tx = TransferService.class
                .getMethod("transfer", long.class, long.class, BigDecimal.class).getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }
}
