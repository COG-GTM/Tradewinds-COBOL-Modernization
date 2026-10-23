package com.ibm.cics.cip.bank.core.updcust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** PUT /banking/customer/{id} contract (docs/migration/api-contract.md) beyond the recorded transcripts. */
@SpringBootTest(properties = {
        "cbsa.clock.fixed=2026-01-15T10:30:00Z",
        "cbsa.random.seed=42"})
@AutoConfigureMockMvc
class UpdateCustomerControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    @AfterEach
    void seed() {
        DbDelta.resetToSeed(jdbc, dataSource);
    }

    private ResultActions putCustomer(Object id, String json) throws Exception {
        return mvc.perform(put("/banking/customer/{id}", id).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String nameOf(int custno) {
        return jdbc.queryForObject("SELECT CUSTOMER_NAME FROM CUSTOMER WHERE CUSTOMER_NUMBER = ?", String.class,
                String.format("%010d", custno));
    }

    private String addressOf(int custno) {
        return jdbc.queryForObject("SELECT CUSTOMER_ADDRESS FROM CUSTOMER WHERE CUSTOMER_NUMBER = ?", String.class,
                String.format("%010d", custno));
    }

    private long proctranRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM PROCTRAN", Long.class);
    }

    @Test
    void updatesNameAndAddressAndReturnsCustomerJson() throws Exception {
        long proctran = proctranRows();
        putCustomer(2, """
                {"customerName":"Mr Updated N Name","customerAddress":"99 New Road, Norwich","sortCode":"987654",
                 "dateOfBirth":"2000-01-01","creditScore":"999"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("0000000002"))
                .andExpect(jsonPath("$.sortCode").value("987654"))
                .andExpect(jsonPath("$.customerName").value("Mr Updated N Name"))
                .andExpect(jsonPath("$.customerAddress").value("99 New Road, Norwich"))
                .andExpect(jsonPath("$.dateOfBirth").value("1979-07-03"))
                .andExpect(jsonPath("$.customerCreditScore").value("630"))
                .andExpect(jsonPath("$.customerCreditScoreReviewDate").value("2026-01-26"));
        assertThat(nameOf(2)).isEqualTo("Mr Updated N Name");
        assertThat(proctranRows()).as("UPDCUST writes no PROCTRAN row").isEqualTo(proctran);
    }

    @Test
    void blankNameKeepsOldName() throws Exception {
        putCustomer(3, """
                {"customerName":"","customerAddress":"7 Changed Avenue, Derby","sortCode":"987654"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Ms Natalie U Walker"))
                .andExpect(jsonPath("$.customerAddress").value("7 Changed Avenue, Derby"));
    }

    @Test
    void blankAddressKeepsOldAddress() throws Exception {
        putCustomer(2, """
                {"customerName":"Sir Ian Name","customerAddress":"   ","sortCode":"987654"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Sir Ian Name"))
                .andExpect(jsonPath("$.customerAddress").value("39 Gorse Grove, Peterborough"));
    }

    @Test
    void leadingSpaceCountsAsBlankLikeCobol() throws Exception {
        // COMM-NAME(1:1) = ' ' is treated as blank: the old name is kept even though text follows.
        putCustomer(2, """
                {"customerName":" Captain Hook","customerAddress":"1 Dock Road","sortCode":"987654"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Dr James A Wilmore"))
                .andExpect(jsonPath("$.customerAddress").value("1 Dock Road"));
    }

    @Test
    void bothBlankIsFailCode4() throws Exception {
        putCustomer(4, """
                {"customerName":"","customerAddress":"","sortCode":"987654"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failCode").value("4"));
    }

    @Test
    void invalidTitleIsFailCodeT() throws Exception {
        putCustomer(5, """
                {"customerName":"Captain Jack Sparrow","customerAddress":"x","sortCode":"987654"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failCode").value("T"))
                .andExpect(jsonPath("$.errorMessage").value("Customer title Captain is not valid"));
        assertThat(addressOf(5)).isNotEqualTo("x");
    }

    @Test
    void titleCheckIsCaseSensitiveLikeCobol() throws Exception {
        putCustomer(5, """
                {"customerName":"mr Lower Case","customerAddress":"x","sortCode":"987654"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failCode").value("T"));
    }

    @Test
    void titleIsTruncatedToNineCharactersLikeCobol() throws Exception {
        // UNSTRING into WS-UNSTR-TITLE PIC X(9): "Professorial" is compared as "Professor".
        putCustomer(5, """
                {"customerName":"Professorial Snape","customerAddress":"Hogwarts","sortCode":"987654"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Professorial Snape"));
    }

    @Test
    void notFoundIs404FailCode1() throws Exception {
        putCustomer(999, """
                {"customerName":"Mr Nobody","customerAddress":"Nowhere","sortCode":"987654"}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.failCode").value("1"))
                .andExpect(jsonPath("$.errorMessage").value("Customer 999 not found."));
    }

    @Test
    void nullNameIsRejectedWithLegacyMessage() throws Exception {
        putCustomer(2, """
                {"customerAddress":"x","sortCode":"987654"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Customer name is null"));
    }

    @Test
    void nullSortCodeIsRejectedWithLegacyMessage() throws Exception {
        putCustomer(2, """
                {"customerName":"Mr A","customerAddress":"x"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Sort Code is null"));
    }

    @Test
    void foreignSortCodeIsRejectedWithLegacyMessage() throws Exception {
        putCustomer(2, """
                {"customerName":"Mr A","customerAddress":"x","sortCode":"012345"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Sortcode 12345 not valid for this bank (987654)"));
        assertThat(addressOf(2)).isEqualTo("39 Gorse Grove, Peterborough");
    }

    @Test
    void numericSortCodeIsAccepted() throws Exception {
        putCustomer(2, """
                {"customerName":"Mr A","customerAddress":"x","sortCode":987654}""")
                .andExpect(status().isOk());
    }

    @Test
    void nullAddressIsRejectedWithLegacyMessage() throws Exception {
        putCustomer(2, """
                {"customerName":"Mr A","sortCode":"987654"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Customer address is null"));
    }

    @Test
    void overlongNameAndAddressAreTruncatedToCommareaWidths() throws Exception {
        String name = "Mr " + "N".repeat(80);
        String addr = "A".repeat(200);
        putCustomer(2, "{\"customerName\":\"" + name + "\",\"customerAddress\":\"" + addr
                + "\",\"sortCode\":\"987654\"}")
                .andExpect(status().isOk());
        assertThat(nameOf(2)).isEqualTo(name.substring(0, 60));
        assertThat(addressOf(2)).isEqualTo(addr.substring(0, 160));
    }

    @Test
    void malformedBodyIs400() throws Exception {
        putCustomer(2, "{not json").andExpect(status().isBadRequest());
    }
}
