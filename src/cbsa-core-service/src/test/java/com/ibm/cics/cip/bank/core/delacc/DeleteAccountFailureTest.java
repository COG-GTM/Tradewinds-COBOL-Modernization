package com.ibm.cics.cip.bank.core.delacc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.domain.ProctranType;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.proctran.ProctranWriter;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Failure paths that need real (committed / rolled back) transactions: PROCTRAN write failure (HWPT) rolls the
 * delete back, DELETE failure gives fail code 3, and a not-found inside a caller's transaction (DELCUS) does not
 * mark that transaction rollback-only. Own H2 database so other test classes are unaffected.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:delacc-failure;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "cbsa.clock.fixed=2026-01-15T10:30:00Z",
        "cbsa.random.seed=42"})
@AutoConfigureMockMvc
class DeleteAccountFailureTest {

    @Autowired
    DeleteAccountService service;

    @SpyBean
    ProctranWriter proctranWriter;

    @SpyBean
    AccountRepository accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    MockMvc mvc;

    @AfterEach
    void resetSpies() {
        reset(proctranWriter, accounts);
    }

    @Test
    void proctranFailureAbendsHwptAndRollsBackDelete() {
        doThrow(new DataAccessResourceFailureException("PROCTRAN unavailable"))
                .when(proctranWriter).deleteAccount(any(ProctranType.class), any(Account.class));
        long proctranBefore = countProctran();

        assertThatThrownBy(() -> service.deleteAccount(3))
                .isInstanceOf(CbsaAbendException.class)
                .satisfies(e -> {
                    assertThat(((CbsaAbendException) e).abendCode()).isEqualTo("HWPT");
                    assertThat(((CbsaAbendException) e).program()).isEqualTo("DELACC");
                });

        assertThat(accountExists(3)).isTrue();
        assertThat(countProctran()).isEqualTo(proctranBefore);
    }

    @Test
    void restProctranFailureReturns500WithAbendCode() throws Exception {
        doThrow(new DataAccessResourceFailureException("PROCTRAN unavailable"))
                .when(proctranWriter).deleteAccount(any(ProctranType.class), any(Account.class));

        mvc.perform(delete("/banking/account/3"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.abendCode").value("HWPT"));

        assertThat(accountExists(3)).isTrue();
    }

    @Test
    void deleteFailureGivesFailCode3WithoutProctran() {
        doReturn(0).when(accounts).delete(anyString(), anyLong());

        assertThatThrownBy(() -> service.deleteAccount(3))
                .isInstanceOf(CbsaException.class)
                .satisfies(e -> {
                    assertThat(((CbsaException) e).failCode()).isEqualTo("3");
                    assertThat(((CbsaException) e).status().value()).isEqualTo(500);
                });

        verify(proctranWriter, never()).deleteAccount(any(), any());
        assertThat(accountExists(3)).isTrue();
    }

    @Test
    void readFailureAbendsHrac() {
        doThrow(new DataAccessResourceFailureException("DB2 down")).when(accounts).findByKey(anyString(), anyLong());

        assertThatThrownBy(() -> service.deleteAccount(3))
                .isInstanceOf(CbsaAbendException.class)
                .satisfies(e -> assertThat(((CbsaAbendException) e).abendCode()).isEqualTo("HRAC"));
    }

    @Test
    void notFoundDoesNotPoisonCallerTransaction() {
        tx.executeWithoutResult(status -> {
            assertThatThrownBy(() -> service.deleteAccount(99999)).isInstanceOf(AccountNotFoundException.class);
            assertThat(status.isRollbackOnly()).isFalse();
        });
    }

    private boolean accountExists(long accountNumber) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT WHERE ACCOUNT_SORTCODE = '987654' "
                + "AND ACCOUNT_NUMBER = ?", Long.class, String.format("%08d", accountNumber)) == 1;
    }

    private long countProctran() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM PROCTRAN", Long.class);
    }
}
