package com.ibm.cics.cip.bank.core.creacc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:creacc-web;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "cbsa.clock.fixed=2026-01-15T10:30:00Z",
        "cbsa.random.seed=42"})
@AutoConfigureMockMvc
@Transactional
class CreateAccountControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    ResultActions postJson(String body) throws Exception {
        return mvc.perform(post("/banking/account").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    static String body(String customer, String type, String rate, String overdraft, String sortCode) {
        return """
                {"customerNumber":%s,"accountType":%s,"interestRate":%s,"overdraft":%s,"sortCode":%s,
                 "dateOpened":"2001-01-01"}""".formatted(customer, type, rate, overdraft, sortCode);
    }

    @Test
    void createsAccountFromReactPayload() throws Exception {
        postJson(body("\"9\"", "\"CURRENT\"", "\"1.5\"", "\"250\"", "\"987654\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("00000077"))
                .andExpect(jsonPath("$.sortCode").value("987654"))
                .andExpect(jsonPath("$.customerNumber").value("0000000009"))
                .andExpect(jsonPath("$.accountType").value("CURRENT"))
                .andExpect(jsonPath("$.interestRate").value(1.5))
                .andExpect(jsonPath("$.overdraft").value(250))
                .andExpect(jsonPath("$.availableBalance").value(0))
                .andExpect(jsonPath("$.actualBalance").value(0))
                .andExpect(jsonPath("$.dateOpened").value("2026-01-15"))
                .andExpect(jsonPath("$.lastStatementDate").value("2026-01-15"))
                .andExpect(jsonPath("$.nextStatementDate").value("2026-02-14"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM ACCOUNT WHERE ACCOUNT_SORTCODE='987654' AND ACCOUNT_NUMBER='00000077'");
        assertThat(row.get("ACCOUNT_CUSTOMER_NUMBER")).isEqualTo("0000000009");
        assertThat(String.valueOf(row.get("ACCOUNT_TYPE")).strip()).isEqualTo("CURRENT");
        Map<String, Object> pt = jdbc.queryForMap("SELECT * FROM PROCTRAN WHERE PROCTRAN_NUMBER='00000077'");
        assertThat(pt.get("PROCTRAN_TYPE")).isEqualTo("OCA");
        assertThat(pt.get("PROCTRAN_DESC")).isEqualTo("0000000009CURRENT 1501202614022026      ");
        assertThat(jdbc.queryForObject("SELECT CONTROL_VALUE_NUM FROM CONTROL WHERE CONTROL_NAME='987654-ACCOUNT-LAST'",
                Long.class)).isEqualTo(77L);
    }

    @Test
    void numericJsonValuesAndPaddedTypeAccepted() throws Exception {
        postJson(body("9", "\" LOAN \"", "0", "0", "987654"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountType").value("LOAN"));
    }

    @Test
    void unknownCustomerIs404FailCode1() throws Exception {
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM PROCTRAN", Integer.class);
        postJson(body("\"999\"", "\"ISA\"", "1", "0", "\"987654\""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.failCode").value("1"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM PROCTRAN", Integer.class)).isEqualTo(before);
    }

    @Test
    void tenthAccountAllowedEleventhRejectedFailCode8() throws Exception {
        long existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ACCOUNT WHERE ACCOUNT_CUSTOMER_NUMBER='0000000030'", Long.class);
        for (long i = existing; i < 9; i++) {
            jdbc.update("INSERT INTO ACCOUNT VALUES ('ACCT','0000000030','987654',?,'SAVING',1.0,'2020-01-01',0,"
                    + "'2025-12-01','2026-01-01',10,10)", String.format("%08d", 300 + i));
        }
        postJson(body("\"30\"", "\"SAVING\"", "1", "0", "\"987654\"")).andExpect(status().isCreated());
        postJson(body("\"30\"", "\"SAVING\"", "1", "0", "\"987654\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failCode").value("8"))
                .andExpect(jsonPath("$.errorMessage").value("Customer number 30 cannot have more than ten accounts."));
    }

    @ParameterizedTest(name = "type {0}")
    @CsvSource({"CHEQUE", "isa", "IS"})
    void invalidTypeIs400FailCodeA(String type) throws Exception {
        postJson(body("\"9\"", "\"" + type + "\"", "1", "0", "\"987654\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failCode").value("A"))
                .andExpect(jsonPath("$.errorMessage").value("Account type " + type + " is not valid"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "missing type        | \"9\"          | null      | 1        | 0    | \"987654\"",
            "missing rate        | \"9\"          | \"ISA\"   | null     | 0    | \"987654\"",
            "negative rate       | \"9\"          | \"ISA\"   | -0.01    | 0    | \"987654\"",
            "rate above max      | \"9\"          | \"ISA\"   | 10000.00 | 0    | \"987654\"",
            "rate 3dp            | \"9\"          | \"ISA\"   | 1.234    | 0    | \"987654\"",
            "missing overdraft   | \"9\"          | \"ISA\"   | 1        | null | \"987654\"",
            "negative overdraft  | \"9\"          | \"ISA\"   | 1        | -1   | \"987654\"",
            "missing customer    | null           | \"ISA\"   | 1        | 0    | \"987654\"",
            "customer zero       | \"0\"          | \"ISA\"   | 1        | 0    | \"987654\"",
            "customer sentinel   | \"9999999999\" | \"ISA\"   | 1        | 0    | \"987654\"",
            "customer non-number | \"abc\"        | \"ISA\"   | 1        | 0    | \"987654\"",
            "wrong sort code     | \"9\"          | \"ISA\"   | 1        | 0    | \"123456\"",
            "missing sort code   | \"9\"          | \"ISA\"   | 1        | 0    | null"})
    void webValidationRA9(String name, String customer, String type, String rate, String overdraft, String sortCode)
            throws Exception {
        postJson(body(customer, type, rate, overdraft, sortCode)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT CONTROL_VALUE_NUM FROM CONTROL WHERE CONTROL_NAME='987654-ACCOUNT-LAST'",
                Long.class)).isEqualTo(76L);
    }

    @Test
    void maxRateWithTwoDecimalsAccepted() throws Exception {
        postJson(body("\"9\"", "\"MORTGAGE\"", "\"9999.99\"", "0", "\"987654\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.interestRate").value(9999.99));
    }
}
