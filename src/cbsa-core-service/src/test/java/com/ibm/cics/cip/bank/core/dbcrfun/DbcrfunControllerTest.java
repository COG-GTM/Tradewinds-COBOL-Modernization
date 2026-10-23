package com.ibm.cics.cip.bank.core.dbcrfun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ibm.cics.cip.bank.core.common.repository.ProctranRepository;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {DbcrfunTestDb.DB, DbcrfunTestDb.CLOCK, DbcrfunTestDb.RANDOM})
@AutoConfigureMockMvc
class DbcrfunControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    ProctranRepository proctran;

    @BeforeEach
    void seed() {
        DbcrfunTestDb.resetToSeed(jdbc, dataSource);
    }

    private ResultActions putJson(String path, String body) throws Exception {
        return mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String payment(String accno, String amt, String faciltype) {
        return """
                {"PAYDBCR": {"CommAccno": "%s", "CommAmt": %s, "mSortC": 0, "CommAvBal": 0, "CommActBal": 0,
                  "CommOrigin": {"CommApplid": "PAYAPP", "CommUserid": "ACMECORP", "CommFacilityName": "        ",
                                 "CommNetwrkId": "        ", "CommFaciltype": %s, "Fill0": "    "},
                  "CommSuccess": " ", "CommFailCode": " "}}""".formatted(accno, amt, faciltype);
    }

    @Test
    void tellerDebitReturnsAccountJson() throws Exception {
        putJson("/banking/account/debit/7", "{\"amount\": 55.55}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("00000007"))
                .andExpect(jsonPath("$.sortCode").value("987654"))
                .andExpect(jsonPath("$.customerNumber").isString())
                .andExpect(jsonPath("$.availableBalance").value(311684.47))
                .andExpect(jsonPath("$.actualBalance").value(311684.47))
                .andExpect(jsonPath("$.dateOpened").isString());
        assertThat(proctran.findByType("DEB").stream().filter(p -> p.date().toString().equals("2026-01-15")).toList()).singleElement()
                .satisfies(p -> assertThat(p.description().trim()).isEqualTo("COUNTER WTHDRW"));
    }

    @Test
    void tellerCreditReturnsAccountJson() throws Exception {
        putJson("/banking/account/credit/1", "{\"amount\": 100}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(623475.58));
        assertThat(proctran.findByType("CRE").stream().filter(p -> p.date().toString().equals("2026-01-15")).toList()).singleElement()
                .satisfies(p -> assertThat(p.description().trim()).isEqualTo("COUNTER RECVED"));
    }

    @Test
    void tellerDebitOnLoanIsAllowed() throws Exception {
        putJson("/banking/account/debit/4", "{\"amount\": 1}").andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"amount\": 0}", "{\"amount\": -5}", "{}", "{\"amount\": 1.001}",
            "{\"amount\": 12345678901}"})
    void invalidAmountIs400(String body) throws Exception {
        putJson("/banking/account/debit/1", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").isString());
        putJson("/banking/account/credit/1", body).andExpect(status().isBadRequest());
    }

    @Test
    void unknownAccountIs404FailCode1() throws Exception {
        putJson("/banking/account/credit/99999", "{\"amount\": 1}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.failCode").value("1"))
                .andExpect(jsonPath("$.errorMessage").value("Account number 99999 not found."));
    }

    @Test
    void paymentDebitSucceedsWithPdrAndOriginDescription() throws Exception {
        putJson("/makepayment/dbcr", payment("00000011", "-10.00", "496"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.PAYDBCR.CommSuccess").value("Y"))
                .andExpect(jsonPath("$.PAYDBCR.CommFailCode").value("0"))
                .andExpect(jsonPath("$.PAYDBCR.mSortC").value(987654))
                .andExpect(jsonPath("$.PAYDBCR.CommAvBal").value(956247.97))
                .andExpect(jsonPath("$.PAYDBCR.CommActBal").value(956247.97))
                .andExpect(jsonPath("$.PAYDBCR.CommOrigin.CommApplid").value("PAYAPP"));
        assertThat(proctran.findByType("PDR")).singleElement()
                .satisfies(p -> assertThat(p.description()).startsWith("PAYAPP  ACMECO "));
    }

    @Test
    void paymentAcceptsFaciltypeAsStringLikeThePaymentInterface() throws Exception {
        putJson("/makepayment/dbcr", payment("9", "5", "\"0496\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.PAYDBCR.CommSuccess").value("Y"));
        assertThat(proctran.findByType("PCR")).hasSize(1);
    }

    @Test
    void paymentFailuresAreHttp200WithFailCodes() throws Exception {
        putJson("/makepayment/dbcr", payment("00099999", "1", "496"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.PAYDBCR.CommSuccess").value("N"))
                .andExpect(jsonPath("$.PAYDBCR.CommFailCode").value("1"));
        putJson("/makepayment/dbcr", payment("00000076", "-400", "496"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.PAYDBCR.CommFailCode").value("3"))
                .andExpect(jsonPath("$.PAYDBCR.CommAvBal").value(0));
        putJson("/makepayment/dbcr", payment("00000074", "10", "496"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.PAYDBCR.CommFailCode").value("4"));
        putJson("/makepayment/dbcr", payment("ABC", "10", "496"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.PAYDBCR.CommFailCode").value("1"));
        assertThat(proctran.count()).isEqualTo(8);
    }

    @Test
    void malformedPaymentIs400() throws Exception {
        putJson("/makepayment/dbcr", "{\"PAYDBCR\": {\"CommAccno\": \"00000001\"}}").andExpect(status().isBadRequest());
        putJson("/makepayment/dbcr", "{}").andExpect(status().isBadRequest());
        putJson("/makepayment/dbcr", "not json").andExpect(status().isBadRequest());
        putJson("/makepayment/dbcr", payment("00000009", "1.239", "496")).andExpect(status().isBadRequest());
        putJson("/makepayment/dbcr", payment("00000009", "12345678901", "496")).andExpect(status().isBadRequest());
        assertThat(proctran.count()).isEqualTo(8);
    }

    @Test
    void paymentAmountWithTrailingZerosBeyondCentsIsAccepted() throws Exception {
        putJson("/makepayment/dbcr", payment("00000009", "1.2300", "496"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.PAYDBCR.CommSuccess").value("Y"));
    }

    @Test
    void origin14IsApplidPlusFirstSixOfUserid() {
        assertThat(PaymentController.origin14(new PayDbcr.Origin("APPLID01", "USERID99", "", "", 496, "")))
                .isEqualTo("APPLID01USERID");
        assertThat(PaymentController.origin14(null)).isEqualTo(" ".repeat(14));
    }
}
