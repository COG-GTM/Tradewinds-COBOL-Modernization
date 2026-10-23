package com.ibm.cics.cip.bank.core.bankdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Dev-only endpoint against H2 (own in-memory DB so other contexts keep the canonical seed). */
@SpringBootTest(properties = {
        "cbsa.clock.fixed=2026-01-15T10:30:00Z",
        "cbsa.random.seed=42",
        "cbsa.bankdata.endpoint.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:cbsa-bankdata;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"})
@AutoConfigureMockMvc
class BankDataControllerTest {

    static final String PARM = "{\"startKey\":1,\"endKey\":30,\"stepKey\":1,\"seed\":42}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    BankDataGenerator generator;

    @Autowired
    BankDataSqlWriter writer;

    @Test
    void runReplacesCustomersAccountsAndControlsWithBankdataOutput() throws Exception {
        long proctranBefore = count("PROCTRAN");

        mvc.perform(post("/banking/admin/bankdata").contentType(MediaType.APPLICATION_JSON).content(PARM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customers").value(30))
                .andExpect(jsonPath("$.accounts").value(73))
                .andExpect(jsonPath("$['987654-ACCOUNT-LAST']").value(73))
                .andExpect(jsonPath("$['987654-CUSTOMER-LAST']").value(30));

        assertThat(count("CUSTOMER")).isEqualTo(30);
        assertThat(count("ACCOUNT")).isEqualTo(73);
        assertThat(count("PROCTRAN")).isEqualTo(proctranBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT WHERE ACCOUNT_TYPE = 'MORTGAGE'", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT CUSTOMER_NAME FROM CUSTOMER WHERE CUSTOMER_NUMBER = '0000000001'",
                String.class)).isEqualTo("Drs Adam N Renton");
        assertThat(jdbc.queryForObject(
                "SELECT ACCOUNT_AVAILABLE_BALANCE FROM ACCOUNT WHERE ACCOUNT_NUMBER = '00000001'", BigDecimal.class))
                .isEqualByComparingTo("623125.58");
        assertThat(jdbc.queryForObject(
                "SELECT ACCOUNT_ACTUAL_BALANCE FROM ACCOUNT WHERE ACCOUNT_NUMBER = '00000004'", BigDecimal.class))
                .isEqualByComparingTo("-593118.54");
        for (String[] row : new String[][] {{"ACCOUNT-COUNT", "73"}, {"ACCOUNT-LAST", "73"},
                {"CUSTOMER-COUNT", "30"}, {"CUSTOMER-LAST", "30"}}) {
            assertThat(jdbc.queryForObject("SELECT CONTROL_VALUE_NUM FROM CONTROL WHERE CONTROL_NAME = ?",
                    Long.class, "987654-" + row[0])).isEqualTo(Long.parseLong(row[1]));
        }
    }

    @Test
    void sqlEndpointReturnsSeedFormat() throws Exception {
        String expected = writer.toSql(generator.generate(1, 30, 1, 42));

        mvc.perform(post("/banking/admin/bankdata/sql").contentType(MediaType.APPLICATION_JSON).content(PARM))
                .andExpect(status().isOk())
                .andExpect(content().string(expected));
        assertThat(BaselineSeed.normalise(expected))
                .isEqualTo(BaselineSeed.normalise(BaselineSeed.bankdataOnly(BaselineSeed.read())));
    }

    @Test
    void invalidParmReturns400WithReturnCode12() throws Exception {
        mvc.perform(post("/banking/admin/bankdata").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startKey\":30,\"endKey\":1,\"stepKey\":1,\"seed\":42}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage")
                        .value("Final customer number cannot be smaller than first customer number"))
                .andExpect(jsonPath("$.failCode").value("12"));
        mvc.perform(post("/banking/admin/bankdata").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startKey\":1,\"endKey\":30,\"stepKey\":0,\"seed\":42}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Gap between customers cannot be zero"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
