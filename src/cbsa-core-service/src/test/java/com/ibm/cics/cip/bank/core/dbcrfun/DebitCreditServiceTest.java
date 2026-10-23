package com.ibm.cics.cip.bank.core.dbcrfun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.proctran.ProctranWriter;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import com.ibm.cics.cip.bank.core.common.repository.ProctranRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {DbcrfunTestDb.DB, DbcrfunTestDb.CLOCK, DbcrfunTestDb.RANDOM})
class DebitCreditServiceTest {

    private static final String SORT = "987654";

    @Autowired
    DebitCreditService service;

    @Autowired
    AccountRepository accounts;

    @Autowired
    ProctranRepository proctran;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @SpyBean
    ProctranWriter proctranWriter;

    @BeforeEach
    void seed() {
        DbcrfunTestDb.resetToSeed(jdbc, dataSource);
    }

    @AfterEach
    void resetSpy() {
        reset(proctranWriter);
    }

    private Account account(long n) {
        return accounts.findByKey(SORT, n).orElseThrow();
    }

    @Test
    void tellerDebitWritesDebCounterWithdrawal() {
        Account before = account(7);
        Account after = service.debitCredit(7, new BigDecimal("-55.55"), false, null);
        assertThat(after.availableBalance()).isEqualByComparingTo(before.availableBalance().subtract(new BigDecimal("55.55")));
        assertThat(account(7).actualBalance()).isEqualByComparingTo(before.actualBalance().subtract(new BigDecimal("55.55")));
        var rows = proctran.findByType("DEB").stream().filter(p -> p.date().toString().equals("2026-01-15")).toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).description().trim()).isEqualTo("COUNTER WTHDRW");
        assertThat(rows.get(0).number()).isEqualTo("00000007");
        assertThat(rows.get(0).amount()).isEqualByComparingTo("-55.55");
        assertThat(rows.get(0).time()).isEqualTo("103000");
    }

    @Test
    void paymentCreditUsesOriginAsDescription() {
        service.debitCredit(9, new BigDecimal("12.34"), true, "PAYAPP  ACMECO");
        var rows = proctran.findByType("PCR");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).description()).startsWith("PAYAPP  ACMECO").hasSize(40);
    }

    @Test
    void amountIsTruncatedToTwoDecimalsLikePicS9V99() {
        Account before = account(1);
        Account after = service.debitCredit(1, new BigDecimal("1.239"), false, null);
        assertThat(after.availableBalance()).isEqualByComparingTo(before.availableBalance().add(new BigDecimal("1.23")));
    }

    @Test
    void notFoundIsFailCode1() {
        assertThatThrownBy(() -> service.debitCredit(99999, BigDecimal.ONE, false, null))
                .isInstanceOfSatisfying(CbsaException.class, e -> assertThat(e.failCode()).isEqualTo("1"));
        assertThat(proctran.count()).isEqualTo(8);
    }

    @Test
    void paymentDebitBelowZeroIsFailCode3EvenWithinOverdraft() {
        assertThatThrownBy(() -> service.debitCredit(76, new BigDecimal("-400"), true, "X"))
                .isInstanceOfSatisfying(CbsaException.class, e -> assertThat(e.failCode()).isEqualTo("3"));
        assertThat(account(76).availableBalance()).isEqualByComparingTo("0");
    }

    @Test
    void tellerDebitHasNoFundsCheck() {
        assertThat(service.debitCredit(76, new BigDecimal("-600"), false, null).availableBalance())
                .isEqualByComparingTo("-600");
    }

    @Test
    void paymentToMortgageOrLoanIsFailCode4ForDebitAndCredit() {
        for (long acc : new long[] {4, 74}) {
            for (String amt : new String[] {"10", "-10"}) {
                assertThatThrownBy(() -> service.debitCredit(acc, new BigDecimal(amt), true, "X"))
                        .isInstanceOfSatisfying(CbsaException.class, e -> assertThat(e.failCode()).isEqualTo("4"));
            }
        }
        assertThat(proctran.count()).isEqualTo(8);
    }

    @Test
    void mortgageCheckPrecedesFundsCheck() {
        // account 4 is a LOAN with a negative balance: a payment debit is refused as 4, not 3 (UAD010 order)
        assertThatThrownBy(() -> service.debitCredit(4, new BigDecimal("-10"), true, "X"))
                .isInstanceOfSatisfying(CbsaException.class, e -> assertThat(e.failCode()).isEqualTo("4"));
    }

    @Test
    void proctranFailureRollsBackTheBalanceUpdate() {
        Account before = account(1);
        doThrow(new DataIntegrityViolationException("PROCTRAN insert failed")).when(proctranWriter)
                .debitCredit(anyString(), anyLong(), any(), anyBoolean(), any());
        assertThatThrownBy(() -> service.debitCredit(1, new BigDecimal("100"), false, null))
                .isInstanceOfSatisfying(CbsaException.class, e -> assertThat(e.failCode()).isEqualTo("2"));
        assertThat(account(1).availableBalance()).isEqualByComparingTo(before.availableBalance());
        assertThat(account(1).actualBalance()).isEqualByComparingTo(before.actualBalance());
        assertThat(proctran.count()).isEqualTo(8);
    }

    @Test
    void balanceOverflowIsRejectedWithFailCode2() {
        assertThatThrownBy(() -> service.debitCredit(1, new BigDecimal("9999999999.99"), false, null))
                .isInstanceOfSatisfying(CbsaException.class, e -> assertThat(e.failCode()).isEqualTo("2"));
    }

    @Test
    void concurrentDebitsAndCreditsNeverLoseAnUpdate() throws Exception {
        Account before = account(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Account>> calls = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                BigDecimal amt = i % 2 == 0 ? new BigDecimal("3.00") : new BigDecimal("-1.00");
                calls.add(() -> service.debitCredit(1, amt, false, null));
            }
            for (Future<Account> f : pool.invokeAll(calls)) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(account(1).availableBalance()).isEqualByComparingTo(before.availableBalance().add(new BigDecimal("40.00")));
        assertThat(proctran.count()).isEqualTo(48);
    }
}
