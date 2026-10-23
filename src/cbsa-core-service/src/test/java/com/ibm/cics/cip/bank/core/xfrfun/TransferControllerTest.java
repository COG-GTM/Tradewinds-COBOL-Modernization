package com.ibm.cics.cip.bank.core.xfrfun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ibm.cics.cip.bank.core.common.error.AbendEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** {@code PUT /banking/account/transfer/{id}} against H2 + canonical seed (accounts 1..76, sort code 987654). */
@SpringBootTest(properties = {XfrfunTestSupport.FIXED_TIME, XfrfunTestSupport.RANDOM_SEED,
        XfrfunTestSupport.PRIVATE_DB})
@AutoConfigureMockMvc
@RecordApplicationEvents
class TransferControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApplicationEvents events;

    private XfrfunTestSupport.Snapshot snapshot;

    @BeforeEach
    void snapshot() {
        snapshot = XfrfunTestSupport.snapshot(jdbc);
    }

    @AfterEach
    void restore() {
        XfrfunTestSupport.restore(jdbc, snapshot);
    }

    private ResultActions transfer(String id, String body) throws Exception {
        return mvc.perform(put("/banking/account/transfer/" + id).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private List<Map<String, Object>> newProctran() {
        return XfrfunTestSupport.inserted(snapshot.proctran(), XfrfunTestSupport.proctranRows(jdbc));
    }

    @Test
    void transferReturnsBothAccountsInContractShape() throws Exception {
        transfer("1", "{\"targetAccount\": 2, \"amount\": 250.00}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(250.00))
                .andExpect(jsonPath("$.fromAccount.id").value("00000001"))
                .andExpect(jsonPath("$.fromAccount.sortCode").value("987654"))
                .andExpect(jsonPath("$.fromAccount.customerNumber").value("0000000001"))
                .andExpect(jsonPath("$.fromAccount.availableBalance").value(623125.58))
                .andExpect(jsonPath("$.fromAccount.actualBalance").value(623125.58))
                .andExpect(jsonPath("$.fromAccount.accountType").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.endsWith(" "))))
                .andExpect(jsonPath("$.fromAccount.dateOpened").isString())
                .andExpect(jsonPath("$.toAccount.id").value("00000002"))
                .andExpect(jsonPath("$.toAccount.availableBalance").value(329536.06));

        List<Map<String, Object>> rows = newProctran();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("PROCTRAN_TYPE", "TFR").containsEntry("PROCTRAN_NUMBER", "00000001");
        assertThat(rows.get(0).get("PROCTRAN_DESC").toString()).isEqualTo("TRANSFER                  98765400000002");
        assertThat((BigDecimal) rows.get(0).get("PROCTRAN_AMOUNT")).isEqualByComparingTo("250.00");
    }

    @Test
    void zeroPaddedIdsAndStringTargetAreAccepted() throws Exception {
        transfer("00000002", "{\"targetAccount\": \"00000001\", \"amount\": \"1.00\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromAccount.id").value("00000002"))
                .andExpect(jsonPath("$.toAccount.id").value("00000001"));
    }

    @Test
    void amountIsTruncatedToTwoDecimalsLikePicS9V99() throws Exception {
        transfer("1", "{\"targetAccount\": 2, \"amount\": 10.019}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(10.01));
        assertThat(XfrfunTestSupport.balances(jdbc, 1)[0]).isEqualByComparingTo("623365.57");
    }

    @Test
    void nonPositiveOrMissingAmountIsFailCode4() throws Exception {
        for (String body : new String[] {"{\"targetAccount\": 2, \"amount\": 0}",
                "{\"targetAccount\": 2, \"amount\": -5.00}", "{\"targetAccount\": 2}",
                "{\"targetAccount\": 2, \"amount\": 0.001}"}) {
            transfer("1", body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.failCode").value("4"))
                    .andExpect(jsonPath("$.errorMessage").value("Amount must be greater than zero."));
        }
        assertThat(newProctran()).isEmpty();
    }

    @Test
    void amountIsCheckedBeforeSameAccount() throws Exception {
        transfer("3", "{\"targetAccount\": 3, \"amount\": 0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failCode").value("4"));
        assertThat(events.stream(AbendEvent.class)).isEmpty();
    }

    @Test
    void sameAccountIsAbendSame() throws Exception {
        transfer("3", "{\"targetAccount\": 3, \"amount\": 10.00}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Cannot transfer to the same account"))
                .andExpect(jsonPath("$.abendCode").value("SAME"))
                .andExpect(jsonPath("$.failCode").doesNotExist());
        assertThat(events.stream(AbendEvent.class))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.abendCode()).isEqualTo("SAME");
                    assertThat(e.program()).isEqualTo("XFRFUN");
                });
        assertThat(XfrfunTestSupport.snapshot(jdbc).balances()).usingRecursiveComparison()
                .isEqualTo(snapshot.balances());
        assertThat(newProctran()).isEmpty();
    }

    @Test
    void missingFromAccountIs404FailCode1() throws Exception {
        transfer("99999", "{\"targetAccount\": 1, \"amount\": 1.00}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.failCode").value("1"))
                .andExpect(jsonPath("$.errorMessage").value("Account number 99999 not found."));
        assertThat(XfrfunTestSupport.balances(jdbc, 1)).isEqualTo(snapshot.balances().get("00000001"));
        assertThat(newProctran()).isEmpty();
    }

    @Test
    void missingToAccountIs404FailCode2AndRollsBackFromUpdate() throws Exception {
        transfer("1", "{\"targetAccount\": 99999, \"amount\": 1.00}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.failCode").value("2"));
        BigDecimal[] fromNow = XfrfunTestSupport.balances(jdbc, 1);
        assertThat(fromNow[0]).isEqualByComparingTo(snapshot.balances().get("00000001")[0]);
        assertThat(fromNow[1]).isEqualByComparingTo(snapshot.balances().get("00000001")[1]);
        assertThat(newProctran()).isEmpty();
    }

    @Test
    void missingFromAccountAboveExistingToRollsBackToUpdate() throws Exception {
        transfer("99999", "{\"targetAccount\": 5, \"amount\": 7.00}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.failCode").value("1"));
        assertThat(XfrfunTestSupport.balances(jdbc, 5)[0])
                .isEqualByComparingTo(snapshot.balances().get("00000005")[0]);
        assertThat(newProctran()).isEmpty();
    }

    @Test
    void noInsufficientFundsCheck() throws Exception {
        transfer("76", "{\"targetAccount\": 1, \"amount\": 600.00}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromAccount.availableBalance").value(-600.00))
                .andExpect(jsonPath("$.fromAccount.actualBalance").value(-600.00));
    }

    @Test
    void missingTargetOrBadIdsAre400() throws Exception {
        transfer("1", "{\"amount\": 1.00}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("targetAccount is required"));
        transfer("abc", "{\"targetAccount\": 2, \"amount\": 1.00}").andExpect(status().isBadRequest());
        transfer("-1", "{\"targetAccount\": 2, \"amount\": 1.00}").andExpect(status().isBadRequest());
        transfer("1", "{\"targetAccount\": 100000000, \"amount\": 1.00}").andExpect(status().isBadRequest());
        transfer("1", "{\"targetAccount\": 2, \"amount\": 10000000000.00}").andExpect(status().isBadRequest());
        transfer("1", "not json").andExpect(status().isBadRequest());
        assertThat(newProctran()).isEmpty();
    }

    @Test
    void servedUnderContextPath() throws Exception {
        mvc.perform(put("/webui-1.0/banking/account/transfer/1").contextPath("/webui-1.0")
                .contentType(MediaType.APPLICATION_JSON).content("{\"targetAccount\": 2, \"amount\": 1.00}"))
                .andExpect(status().isOk());
    }
}
