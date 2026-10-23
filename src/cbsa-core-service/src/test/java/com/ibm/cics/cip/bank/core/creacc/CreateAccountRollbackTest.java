package com.ibm.cics.cip.bank.core.creacc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.ibm.cics.cip.bank.core.common.domain.ProctranType;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.proctran.ProctranWriter;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** WRITE-PROCTRAN-DB2 failure abends HWPT and rolls back the counter and the ACCOUNT insert. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:creacc-rollback;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "cbsa.clock.fixed=2026-01-15T10:30:00Z",
        "cbsa.random.seed=42"})
class CreateAccountRollbackTest {

    @Autowired
    CreateAccountService service;

    @Autowired
    JdbcTemplate jdbc;

    @MockBean
    ProctranWriter proctran;

    @Test
    void proctranFailureRollsBackEverything() {
        when(proctran.createAccount(eq(ProctranType.BRANCH_CREATE_ACCOUNT), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("down"));
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT", Long.class);

        assertThatThrownBy(() -> service.createAccount(9, "ISA", BigDecimal.ONE, 0, BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOfSatisfying(CbsaAbendException.class, e -> assertThat(e.abendCode()).isEqualTo("HWPT"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT", Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT CONTROL_VALUE_NUM FROM CONTROL WHERE CONTROL_NAME='987654-ACCOUNT-LAST'",
                Long.class)).isEqualTo(76L);
        assertThat(jdbc.queryForObject(
                "SELECT CONTROL_VALUE_NUM FROM CONTROL WHERE CONTROL_NAME='987654-ACCOUNT-COUNT'", Long.class))
                .isEqualTo(76L);
    }
}
