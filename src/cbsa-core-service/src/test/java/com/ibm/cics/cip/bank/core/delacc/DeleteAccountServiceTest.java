package com.ibm.cics.cip.bank.core.delacc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:delacc-service;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "cbsa.clock.fixed=2026-01-15T10:30:00Z",
        "cbsa.random.seed=42"})
@AutoConfigureMockMvc
@Transactional
class DeleteAccountServiceTest {

    @Autowired
    DeleteAccountService service;

    @Autowired
    AccountRepository accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @Test
    void deletesAccountAndWritesOdaProctranWithActualBalance() {
        long proctranBefore = countProctran();

        Account deleted = service.deleteAccount(2);

        assertThat(deleted.accountNumber()).isEqualTo(2);
        assertThat(deleted.customerNumber()).isEqualTo(1);
        assertThat(deleted.accountType()).isEqualTo("SAVING");
        assertThat(deleted.actualBalance()).isEqualByComparingTo("329286.06");
        assertThat(accounts.findByKey("987654", 2)).isEmpty();
        assertThat(countProctran()).isEqualTo(proctranBefore + 1);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM PROCTRAN WHERE PROCTRAN_TYPE = 'ODA' "
                + "AND PROCTRAN_NUMBER = '00000002'");
        assertThat(row.get("PROCTRAN_EYECATCHER")).isEqualTo("PRTR");
        assertThat(row.get("PROCTRAN_SORTCODE")).isEqualTo("987654");
        assertThat(((java.sql.Date) row.get("PROCTRAN_DATE")).toLocalDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(row.get("PROCTRAN_TIME")).isEqualTo("103000");
        assertThat(row.get("PROCTRAN_DESC")).isEqualTo("0000000001SAVING  0107202101082021DELETE");
        assertThat((BigDecimal) row.get("PROCTRAN_AMOUNT")).isEqualByComparingTo("329286.06");
    }

    @Test
    void negativeBalanceIsAuditedAsNegativeAmount() {
        service.deleteAccount(74);
        BigDecimal amount = jdbc.queryForObject("SELECT PROCTRAN_AMOUNT FROM PROCTRAN WHERE PROCTRAN_TYPE = 'ODA' "
                + "AND PROCTRAN_NUMBER = '00000074'", BigDecimal.class);
        assertThat(amount).isEqualByComparingTo("-150000.00");
    }

    @Test
    void notFoundThrowsFailCode1AndWritesNothing() {
        long proctranBefore = countProctran();
        long accountsBefore = accounts.count("987654");

        assertThatThrownBy(() -> service.deleteAccount(99999))
                .isInstanceOf(AccountNotFoundException.class)
                .satisfies(e -> assertThat(((AccountNotFoundException) e).failCode()).isEqualTo("1"));

        assertThat(countProctran()).isEqualTo(proctranBefore);
        assertThat(accounts.count("987654")).isEqualTo(accountsBefore);
    }

    @Test
    void secondDeleteOfSameAccountIsNotFound() {
        service.deleteAccount(2);
        assertThatThrownBy(() -> service.deleteAccount(2)).isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void restDeleteReturnsDeletedAccountJson() throws Exception {
        mvc.perform(delete("/banking/account/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("00000002"))
                .andExpect(jsonPath("$.sortCode").value("987654"))
                .andExpect(jsonPath("$.customerNumber").value("0000000001"))
                .andExpect(jsonPath("$.accountType").value("SAVING"))
                .andExpect(jsonPath("$.interestRate").value(1.75))
                .andExpect(jsonPath("$.overdraft").value(0))
                .andExpect(jsonPath("$.availableBalance").value(329286.06))
                .andExpect(jsonPath("$.actualBalance").value(329286.06))
                .andExpect(jsonPath("$.dateOpened").value("1967-10-21"))
                .andExpect(jsonPath("$.lastStatementDate").value("2021-07-01"))
                .andExpect(jsonPath("$.nextStatementDate").value("2021-08-01"));
    }

    @Test
    void restDeleteUnknownAccountReturns404FailCode1() throws Exception {
        mvc.perform(delete("/banking/account/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.failCode").value("1"))
                .andExpect(jsonPath("$.errorMessage").value("Account 99999 not found"))
                .andExpect(jsonPath("$.abendCode").doesNotExist());
    }

    @Test
    void restDeleteNonNumericIdIs400() throws Exception {
        mvc.perform(delete("/banking/account/abc")).andExpect(status().isBadRequest());
    }

    private long countProctran() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM PROCTRAN", Long.class);
    }
}
