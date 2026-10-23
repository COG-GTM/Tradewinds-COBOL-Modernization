package com.ibm.cics.cip.bank.core.crecust;

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
import org.springframework.transaction.annotation.Transactional;

/** POST /banking/customer against H2 + canonical seed; each test rolls back. */
@SpringBootTest(properties = {"cbsa.clock.fixed=2026-01-15T10:30:00Z", "cbsa.random.seed=42"})
@AutoConfigureMockMvc
@Transactional
class CreateCustomerControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private long customerLast() {
        return jdbc.queryForObject("SELECT CONTROL_VALUE_NUM FROM CONTROL WHERE CONTROL_NAME = '987654-CUSTOMER-LAST'",
                Long.class);
    }

    private static String body(String name, String address, String dob, String sortCode) {
        StringBuilder b = new StringBuilder("{");
        if (name != null) {
            b.append("\"customerName\":\"").append(name).append("\",");
        }
        if (address != null) {
            b.append("\"customerAddress\":\"").append(address).append("\",");
        }
        if (dob != null) {
            b.append("\"dateOfBirth\":\"").append(dob).append("\",");
        }
        if (sortCode != null) {
            b.append("\"sortCode\":\"").append(sortCode).append("\",");
        }
        if (b.charAt(b.length() - 1) == ',') {
            b.setLength(b.length() - 1);
        }
        return b.append('}').toString();
    }

    @Test
    void createsCustomerWithCreditScoreAndOccProctran() throws Exception {
        long before = customerLast();
        long expected = before + 1;
        mvc.perform(post("/banking/customer").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Mr Alan T Turing", "1 Bletchley Park, Milton Keynes", "1912-06-23", "987654")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(String.format("%010d", expected)))
                .andExpect(jsonPath("$.sortCode").value("987654"))
                .andExpect(jsonPath("$.customerName").value("Mr Alan T Turing"))
                .andExpect(jsonPath("$.customerAddress").value("1 Bletchley Park, Milton Keynes"))
                .andExpect(jsonPath("$.dateOfBirth").value("1912-06-23"))
                .andExpect(jsonPath("$.customerCreditScore").isString())
                .andExpect(jsonPath("$.customerCreditScoreReviewDate").isString());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM CUSTOMER WHERE CUSTOMER_SORTCODE = '987654' AND CUSTOMER_NUMBER = ?",
                String.format("%010d", expected));
        assertThat(row.get("CUSTOMER_NAME")).isEqualTo("Mr Alan T Turing");
        assertThat(((Number) row.get("CUSTOMER_CREDIT_SCORE")).intValue()).isBetween(1, 998);
        assertThat(customerLast()).isEqualTo(expected);

        Map<String, Object> proctran = jdbc.queryForMap(
                "SELECT * FROM PROCTRAN WHERE PROCTRAN_TYPE = 'OCC' AND PROCTRAN_DESC LIKE ?",
                "987654" + String.format("%010d", expected) + "%");
        assertThat(((String) proctran.get("PROCTRAN_DESC")).stripTrailing())
                .isEqualTo("987654" + String.format("%010d", expected) + "Mr Alan T Turi23/06/1912");
        assertThat(proctran.get("PROCTRAN_NUMBER")).isEqualTo("00000000");
        assertThat(proctran.get("PROCTRAN_TIME")).isEqualTo("103003");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
        "invalid title | Captain Jack Sparrow | Tortuga | 1980-01-01 | 987654 | 400 | T | Customer title Captain is not valid",
        "lower-case title | mr Lower | Leeds | 1980-01-01 | 987654 | 400 | T | Customer title mr is not valid",
        "wrong sort code | Mr A B | Leeds | 1980-01-01 | 123456 | 400 | NULL | Sortcode 123456 not valid for this bank (987654)",
        "invalid month | Ms Jane Q Public | Leeds | 1990-13-15 | 987654 | 400 | Z | Date of Birth 15/13/1990 is not a valid date",
        "garbage dob | Ms Jane Q Public | Leeds | yesterday | 987654 | 400 | Z | Date of Birth yesterday is not a valid date",
        "future dob | Mr Future B Born | Leeds | 2030-01-01 | 987654 | 400 | Y | Date of Birth is in the future",
        "too old | Mr Very O Old | Leeds | 1800-01-01 | 987654 | 400 | O | Customer is over 150 years old",
        "pre 1601 | Mr Tudor H Eight | Leeds | 1491-06-28 | 987654 | 400 | O | Date of Birth year 1491 is before 1601",
        "null name | NULL | Leeds | 1980-01-01 | 987654 | 400 | NULL | Customer name is null",
        "null sort code | Mr A B | Leeds | 1980-01-01 | NULL | 400 | NULL | Sort Code is null",
        "null address | Mr A B | NULL | 1980-01-01 | 987654 | 400 | NULL | Customer address is null",
        "null dob | Mr A B | Leeds | NULL | 987654 | 400 | NULL | Date of Birth is null"})
    void rejectsInvalidRequestsWithoutWriting(String label, String name, String address, String dob, String sortCode,
            int status, String failCode, String message) throws Exception {
        long before = customerLast();
        long customers = jdbc.queryForObject("SELECT COUNT(*) FROM CUSTOMER", Long.class);
        var result = mvc.perform(post("/banking/customer").contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, address, dob, sortCode)))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.errorMessage").value(message));
        if (failCode != null) {
            result.andExpect(jsonPath("$.failCode").value(failCode));
        } else {
            result.andExpect(jsonPath("$.failCode").doesNotExist());
        }
        assertThat(customerLast()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM CUSTOMER", Long.class)).isEqualTo(customers);
    }

    @Test
    void numericSortCodeIsAccepted() throws Exception {
        mvc.perform(post("/banking/customer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"Dr Grace B Hopper\",\"customerAddress\":\"Arlington\","
                                + "\"dateOfBirth\":\"1906-12-09\",\"sortCode\":987654}"))
                .andExpect(status().isCreated());
    }
}
