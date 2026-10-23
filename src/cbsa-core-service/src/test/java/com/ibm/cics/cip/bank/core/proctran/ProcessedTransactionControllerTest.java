package com.ibm.cics.cip.bank.core.proctran;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** {@code GET /banking/processedTransaction}: the Liberty ProcessedTransactionResource read API. */
@SpringBootTest(properties = {"cbsa.clock.fixed=2026-01-15T10:30:00Z", "cbsa.random.seed=42"})
@AutoConfigureMockMvc
@Transactional
class ProcessedTransactionControllerTest {

    private static final String INSERT = "INSERT INTO PROCTRAN (PROCTRAN_EYECATCHER, PROCTRAN_SORTCODE, "
            + "PROCTRAN_NUMBER, PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_REF, PROCTRAN_TYPE, PROCTRAN_DESC, "
            + "PROCTRAN_AMOUNT) VALUES ('PRTR', ?, ?, ?, ?, ?, ?, ?, ?)";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void rows() {
        jdbc.update("DELETE FROM PROCTRAN");
        row("987654", "00000004", "2026-01-11", "101500", "000000000003", "TFR",
                "TRANSFER                  98765400000006", "125.00");
        row("987654", "00000001", "2026-01-10", "090000", "000000000001", "CRE", "COUNTER RECVED", "250.00");
        row("987654", "00000002", "2026-01-10", "090000", "000000000002", "DEB", "COUNTER WTHDRW", "-75.25");
        row("987654", "00000074", "2026-01-12", "080000", "000000000004", "OCA",
                "0000000005MORTGAGE1001202609022026", "0.00");
        row("987654", "00000000", "2026-01-12", "080001", "000000000005", "OCC",
                "9876540000000021Mr Joe Bloggs 01/02/1980", "0.00");
        row("987654", "00000077", "2026-01-12", "080002", "000000000006", "ODA",
                "0000000005CURRENT 0101202601022026DELETE", "-12.30");
        row("123456", "00000009", "2026-01-09", "070000", "000000000007", "CRE", "OTHER BANK", "1.00");
    }

    private void row(String sortCode, String number, String date, String time, String ref, String type, String desc,
            String amount) {
        jdbc.update(INSERT, sortCode, number, java.sql.Date.valueOf(date), time, ref, type, desc,
                new java.math.BigDecimal(amount));
    }

    @Test
    void listsThisBanksRowsOldestFirstWithLibertyKeys() throws Exception {
        mvc.perform(get("/banking/processedTransaction"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numberOfProcessedTransactions").value(6))
                .andExpect(jsonPath("$.numberOfProcessedTransactionRecords").value(6))
                .andExpect(jsonPath("$.success").value("Y"))
                .andExpect(jsonPath("$.processedTransactions", hasSize(6)))
                .andExpect(jsonPath("$.processedTransactions[*].accountNumber").value(
                        org.hamcrest.Matchers.contains("00000001", "00000002", "00000004", "00000074", "00000000",
                                "00000077")))
                .andExpect(jsonPath("$.processedTransactions[0].sortCode").value("987654"))
                .andExpect(jsonPath("$.processedTransactions[0].date").value("2026-01-10"))
                .andExpect(jsonPath("$.processedTransactions[0].time").value("09:00:00"))
                .andExpect(jsonPath("$.processedTransactions[0].timestamp").value("2026-01-10T09:00:00"))
                .andExpect(jsonPath("$.processedTransactions[0].reference").value("000000000001"))
                .andExpect(jsonPath("$.processedTransactions[0].type").value("CRE"))
                .andExpect(jsonPath("$.processedTransactions[0].description").value("COUNTER RECVED"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"amount\":250.00")))
                .andExpect(jsonPath("$.processedTransactions[0]", not(hasKey("customer"))))
                .andExpect(jsonPath("$.processedTransactions[0]", not(hasKey("targetAccount"))));
    }

    @Test
    void decodesTypeSpecificDescriptionLayouts() throws Exception {
        mvc.perform(get("/banking/processedTransaction"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedTransactions[1].amount").value(-75.25))
                .andExpect(jsonPath("$.processedTransactions[2].targetSortcode").value("987654"))
                .andExpect(jsonPath("$.processedTransactions[2].targetAccount").value("00000006"))
                .andExpect(jsonPath("$.processedTransactions[2].description")
                        .value("TRANSFER                  98765400000006"))
                .andExpect(jsonPath("$.processedTransactions[3].customer").value("0000000005"))
                .andExpect(jsonPath("$.processedTransactions[3].accountType").value("MORTGAGE"))
                .andExpect(jsonPath("$.processedTransactions[3].lastStatement").value("2026-01-10"))
                .andExpect(jsonPath("$.processedTransactions[3].nextStatement").value("2026-02-09"))
                .andExpect(jsonPath("$.processedTransactions[4].customer").value("0000000021"))
                .andExpect(jsonPath("$.processedTransactions[4].customerName").value("Mr Joe Bloggs"))
                .andExpect(jsonPath("$.processedTransactions[4].dateOfBirth").value("1980-02-01"))
                .andExpect(jsonPath("$.processedTransactions[5].accountType").value("CURRENT"))
                .andExpect(jsonPath("$.processedTransactions[5].lastStatement").value("2026-01-01"))
                .andExpect(jsonPath("$.processedTransactions[5].amount").value(-12.30));
    }

    @Test
    void limitAndOffsetPage() throws Exception {
        mvc.perform(get("/banking/processedTransaction?limit=2&offset=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numberOfProcessedTransactions").value(2))
                .andExpect(jsonPath("$.processedTransactions[*].accountNumber").value(
                        org.hamcrest.Matchers.contains("00000002", "00000004")));
        mvc.perform(get("/banking/processedTransaction?limit=0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numberOfProcessedTransactions").value(0))
                .andExpect(jsonPath("$.processedTransactions", hasSize(0)));
    }

    @Test
    void negativeOrNonNumericPagingIs400() throws Exception {
        mvc.perform(get("/banking/processedTransaction?limit=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/banking/processedTransaction?offset=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/banking/processedTransaction?limit=abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").exists());
    }

    @Test
    void emptyTableIsEmptyList() throws Exception {
        jdbc.update("DELETE FROM PROCTRAN");
        mvc.perform(get("/banking/processedTransaction"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numberOfProcessedTransactions").value(0))
                .andExpect(jsonPath("$.success").value("Y"));
    }
}
