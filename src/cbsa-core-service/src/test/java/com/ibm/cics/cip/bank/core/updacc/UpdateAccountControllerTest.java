package com.ibm.cics.cip.bank.core.updacc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** {@code PUT /banking/account/{id}} contract (docs/migration/api-contract.md, T-07). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "cbsa.clock.fixed=2026-01-15T10:30:00Z",
        "cbsa.random.seed=42",
        "spring.datasource.url=jdbc:h2:mem:cbsa-updacc;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"})
@AutoConfigureMockMvc
class UpdateAccountControllerTest {

    /** Exactly what AccountDetailsTable.js sends (strings from the form/table cells, DD/MM dates re-ordered). */
    private static final String UI_BODY = """
            {"interestRate": "%s", "lastStatementDate": "2021-07-01", "nextStatementDate": "2021-08-01",
             "dateOpened": "1967-10-21", "actualBalance": "1.00", "overdraft": "%s", "accountType": "%s",
             "id": "00000002", "customerNumber": "0000000001", "sortCode": "%s", "availableBalance": "1.00"}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AccountRepository accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void reset() {
        UpdaccTestDatabase.resetToSeed(jdbc, dataSource);
    }

    private ResultActions putAccount(long id, String rate, String overdraft, String type, String sortCode)
            throws Exception {
        return mvc.perform(put("/banking/account/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(UI_BODY.formatted(rate, overdraft, type, sortCode)));
    }

    @Test
    void uiPayloadUpdatesTypeRateOverdraftAndReturnsAccountJson() throws Exception {
        Map<String, List<Map<String, Object>>> snap = UpdaccTestDatabase.snapshot(jdbc);
        putAccount(2, "0.5", "300", "CURRENT", "987654")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("00000002"))
                .andExpect(jsonPath("$.sortCode").value("987654"))
                .andExpect(jsonPath("$.customerNumber").value("0000000001"))
                .andExpect(jsonPath("$.accountType").value("CURRENT"))
                .andExpect(jsonPath("$.interestRate").value(0.5))
                .andExpect(jsonPath("$.overdraft").value(300))
                .andExpect(jsonPath("$.availableBalance").value(329286.06))
                .andExpect(jsonPath("$.actualBalance").value(329286.06))
                .andExpect(jsonPath("$.dateOpened").value("1967-10-21"))
                .andExpect(jsonPath("$.lastStatementDate").value("2021-07-01"))
                .andExpect(jsonPath("$.nextStatementDate").value("2021-08-01"));
        Map<String, Map<String, List<Object>>> delta =
                UpdaccTestDatabase.delta(snap, UpdaccTestDatabase.snapshot(jdbc));
        assertThat(delta.get("PROCTRAN").values()).as("no PROCTRAN row").allMatch(List::isEmpty);
        assertThat(delta.get("ACCOUNT").get("updated")).hasSize(1);
        Account stored = accounts.findByKey("987654", 2).orElseThrow();
        assertThat(stored.availableBalance()).isEqualByComparingTo("329286.06");
        assertThat(stored.actualBalance()).isEqualByComparingTo("329286.06");
    }

    @Test
    void numericJsonAndLowerCaseTypeAccepted() throws Exception {
        mvc.perform(put("/banking/account/3").contentType(MediaType.APPLICATION_JSON).content("""
                {"id": 3, "sortCode": 987654, "accountType": "isa", "interestRate": 2.10, "overdraft": 0,
                 "availableBalance": 5, "actualBalance": 5, "dateOpened": "21.10.1967"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountType").value("ISA"))
                .andExpect(jsonPath("$.interestRate").value(2.1))
                .andExpect(jsonPath("$.availableBalance").value(510249.31));
    }

    @Test
    void blankTypeIs400() throws Exception {
        putAccount(5, "1", "0", "", "987654").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Account type  is not supported."));
        putAccount(5, "1", "0", "   ", "987654").andExpect(status().isBadRequest());
    }

    @Test
    void invalidTypeIs400() throws Exception {
        putAccount(6, "1", "0", "CHEQUE", "987654").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Account type CHEQUE is not supported."));
        assertThat(accounts.findByKey("987654", 6).orElseThrow().accountType()).isEqualTo("SAVING");
    }

    @Test
    void interestRateRules() throws Exception {
        putAccount(6, "-0.01", "0", "ISA", "987654").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Interest rate cannot be less than zero."));
        putAccount(6, "10000.00", "0", "ISA", "987654").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Interest rate cannot be greater than 9999.99%."));
        putAccount(6, "1.234", "0", "ISA", "987654").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage",
                        startsWith("Interest rate cannot have more than 2 decimal places.")));
        putAccount(6, "", "0", "ISA", "987654").andExpect(status().isBadRequest());
        putAccount(6, "9999.99", "0", "ISA", "987654").andExpect(status().isOk())
                .andExpect(jsonPath("$.interestRate").value(9999.99));
        putAccount(6, "0", "0", "ISA", "987654").andExpect(status().isOk());
        putAccount(6, "1.500", "0", "ISA", "987654").andExpect(status().isOk())
                .andExpect(jsonPath("$.interestRate").value(1.5));
    }

    @Test
    void validationOrderIsTypeThenRateThenSortCode() throws Exception {
        putAccount(99999, "-1", "0", "CHEQUE", "111111").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Account type CHEQUE is not supported."));
        putAccount(99999, "-1", "0", "ISA", "111111").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Interest rate cannot be less than zero."));
        putAccount(99999, "1", "0", "ISA", "111111").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Sortcode 111111 not valid for this bank (987654)"));
    }

    @Test
    void sortCodeMustBeOurs() throws Exception {
        putAccount(6, "1", "0", "ISA", "123456").andExpect(status().isBadRequest());
        putAccount(6, "1", "0", "ISA", "").andExpect(status().isBadRequest());
        putAccount(6, "1", "0", "ISA", "ABCDEF").andExpect(status().isBadRequest());
        mvc.perform(put("/banking/account/6").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountType\": \"ISA\", \"interestRate\": 1, \"overdraft\": 0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void overdraftRules() throws Exception {
        putAccount(6, "1", "-5", "ISA", "987654").andExpect(status().isBadRequest());
        mvc.perform(put("/banking/account/6").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountType\": \"ISA\", \"interestRate\": 1, \"sortCode\": \"987654\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void notFoundIs404() throws Exception {
        putAccount(99999, "1", "0", "SAVING", "987654").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorMessage").value("Account number 99999 not found."));
    }

    @Test
    void nonNumericIdOrMalformedBodyIs400() throws Exception {
        putAccount(0, "1", "0", "SAVING", "987654").andExpect(status().isNotFound());
        mvc.perform(put("/banking/account/abc").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/banking/account/6").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @SuppressWarnings("rawtypes")
    void servedUnderContextPathOverHttp() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> req = new HttpEntity<>(UI_BODY.formatted("3.25", "50", "LOAN", "987654"), headers);
        ResponseEntity<Map> r = rest.exchange("/banking/account/2", HttpMethod.PUT, req, Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("accountType", "LOAN").containsEntry("overdraft", 50);
        assertThat(rest.getRestTemplate().getUriTemplateHandler().expand("/").getPath()).isEqualTo("/webui-1.0/");
    }
}
