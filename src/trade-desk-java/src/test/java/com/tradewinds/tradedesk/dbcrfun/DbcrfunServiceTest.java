package com.tradewinds.tradedesk.dbcrfun;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One test per DBCRFUN branch, run against H2 with the real repositories and
 * transaction manager. Line numbers refer to src/base/cobol_src/DBCRFUN.cbl.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DbcrfunServiceTest {

    static final String SORTCODE = "987654";
    static final String ACC_NO = "00000042";
    static final int TELLER = 0;
    static final int PAYMENT = 496;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-17T09:04:05Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    DbcrfunService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("DELETE FROM PROCTRAN");
        jdbc.execute("DELETE FROM ACCOUNT");
    }

    // 1. L283-284
    @Test
    void accountNotFoundReturnsFailCode1() {
        DbcrfunResponse response = service.applyTransaction(request(ACC_NO, "-10.00", TELLER));

        assertThat(response.success()).isEqualTo("N");
        assertThat(response.failCode()).isEqualTo("1");
        assertThat(response.sortc()).isEqualTo(SORTCODE);
        assertThat(proctranRows()).isEmpty();
    }

    // 2. L285-286
    @Test
    void selectErrorReturnsFailCode2() {
        seedAccount(ACC_NO, "CURRENT", "100.00", "100.00");
        jdbc.execute("ALTER TABLE ACCOUNT RENAME TO ACCOUNT_OFFLINE");
        try {
            DbcrfunResponse response = service.applyTransaction(request(ACC_NO, "10.00", TELLER));

            assertThat(response.success()).isEqualTo("N");
            assertThat(response.failCode()).isEqualTo("2");
        } finally {
            jdbc.execute("ALTER TABLE ACCOUNT_OFFLINE RENAME TO ACCOUNT");
        }
        assertThat(proctranRows()).isEmpty();
    }

    // 3. L330-338
    @ParameterizedTest
    @ValueSource(strings = {"HOSPITAL", "TENDER"})
    void paymentDebitFromHospitalOrTenderAccountReturnsFailCode4(String accountType) {
        seedAccount(ACC_NO, accountType, "1000.00", "1000.00");

        DbcrfunResponse response = service.applyTransaction(request(ACC_NO, "-10.00", PAYMENT));

        assertThat(response.success()).isEqualTo("N");
        assertThat(response.failCode()).isEqualTo("4");
        assertBalances(ACC_NO, "1000.00", "1000.00");
        assertThat(proctranRows()).isEmpty();
    }

    // 4. L340-350
    @Test
    void paymentDebitWithInsufficientFundsReturnsFailCode3() {
        seedAccount(ACC_NO, "CURRENT", "50.00", "50.00");

        DbcrfunResponse response = service.applyTransaction(request(ACC_NO, "-50.01", PAYMENT));

        assertThat(response.success()).isEqualTo("N");
        assertThat(response.failCode()).isEqualTo("3");
        assertBalances(ACC_NO, "50.00", "50.00");
        assertThat(proctranRows()).isEmpty();
    }

    // 5. L344: the funds check only applies to PAYMENT (496) requests.
    @Test
    void tellerDebitWithInsufficientFundsSucceedsAsOverdraft() {
        seedAccount(ACC_NO, "CURRENT", "50.00", "60.00");

        DbcrfunResponse response = service.applyTransaction(request(ACC_NO, "-80.00", TELLER));

        assertThat(response.success()).isEqualTo("Y");
        assertThat(response.failCode()).isEqualTo("0");
        assertThat(response.avBal()).isEqualByComparingTo("-30.00");
        assertThat(response.actBal()).isEqualByComparingTo("-20.00");
        assertBalances(ACC_NO, "-30.00", "-20.00");
    }

    // 6. L368-376: second HOSPITAL/TENDER check also blocks PAYMENT credits.
    @Test
    void paymentCreditToHospitalAccountReturnsFailCode4() {
        seedAccount(ACC_NO, "HOSPITAL", "1000.00", "1000.00");

        DbcrfunResponse response = service.applyTransaction(request(ACC_NO, "25.00", PAYMENT));

        assertThat(response.success()).isEqualTo("N");
        assertThat(response.failCode()).isEqualTo("4");
        assertBalances(ACC_NO, "1000.00", "1000.00");
        assertThat(proctranRows()).isEmpty();
    }

    // 7. L384-419, L491-493, L649-650
    @Test
    void tellerDebitSucceedsUpdatesBalancesAndWritesDebProctran() {
        seedAccount(ACC_NO, "CURRENT", "500.00", "450.00");

        DbcrfunResponse response = service.applyTransaction(request(ACC_NO, "-120.50", TELLER));

        assertThat(response.success()).isEqualTo("Y");
        assertThat(response.failCode()).isEqualTo("0");
        assertThat(response.avBal()).isEqualByComparingTo("379.50");
        assertThat(response.actBal()).isEqualByComparingTo("329.50");
        assertBalances(ACC_NO, "379.50", "329.50");

        Map<String, Object> row = singleProctranRow();
        assertThat(row.get("PROCTRAN_EYECATCHER")).isEqualTo("PRTR");
        assertThat(row.get("PROCTRAN_SORTCODE")).isEqualTo(SORTCODE);
        assertThat(row.get("PROCTRAN_NUMBER")).isEqualTo(ACC_NO);
        assertThat(row.get("PROCTRAN_DATE")).isEqualTo("17.11.2026");
        assertThat(row.get("PROCTRAN_TIME")).isEqualTo("090405");
        assertThat((String) row.get("PROCTRAN_REF")).matches("\\d{12}");
        assertThat(row.get("PROCTRAN_TYPE")).isEqualTo("DEB");
        assertThat(row.get("PROCTRAN_DESC")).isEqualTo(pad40("COUNTER WTHDRW"));
        assertThat((BigDecimal) row.get("PROCTRAN_AMOUNT")).isEqualByComparingTo("-120.50");
    }

    // 8. L504-506
    @Test
    void tellerCreditSucceedsAndWritesCreProctran() {
        seedAccount(ACC_NO, "CURRENT", "100.00", "100.00");

        DbcrfunResponse response = service.applyTransaction(request(ACC_NO, "40.00", TELLER));

        assertThat(response.success()).isEqualTo("Y");
        assertThat(response.failCode()).isEqualTo("0");
        assertBalances(ACC_NO, "140.00", "140.00");
        Map<String, Object> row = singleProctranRow();
        assertThat(row.get("PROCTRAN_TYPE")).isEqualTo("CRE");
        assertThat(row.get("PROCTRAN_DESC")).isEqualTo(pad40("COUNTER RECVED"));
        assertThat((BigDecimal) row.get("PROCTRAN_AMOUNT")).isEqualByComparingTo("40.00");
    }

    // 9a. L498-502
    @Test
    void paymentDebitWritesPdrProctranWithOriginPrefix() {
        seedAccount(ACC_NO, "CURRENT", "100.00", "100.00");

        DbcrfunResponse response = service.applyTransaction(
                new DbcrfunRequest(ACC_NO, new BigDecimal("-30.00"), null,
                        new DbcrfunOrigin("MENARINI", "PHARMACY", "FAC", "NET", PAYMENT)));

        assertThat(response.success()).isEqualTo("Y");
        Map<String, Object> row = singleProctranRow();
        assertThat(row.get("PROCTRAN_TYPE")).isEqualTo("PDR");
        // COMM-ORIGIN(1:14) = APPLID (8) + first 6 bytes of USERID.
        assertThat(row.get("PROCTRAN_DESC")).isEqualTo(pad40("MENARINIPHARMA"));
    }

    // 9b. L511-515
    @Test
    void paymentCreditWritesPcrProctranWithOriginPrefix() {
        seedAccount(ACC_NO, "CURRENT", "100.00", "100.00");

        DbcrfunResponse response = service.applyTransaction(
                new DbcrfunRequest(ACC_NO, new BigDecimal("30.00"), null,
                        new DbcrfunOrigin("ACME", "CO", null, null, PAYMENT)));

        assertThat(response.success()).isEqualTo("Y");
        assertBalances(ACC_NO, "130.00", "130.00");
        Map<String, Object> row = singleProctranRow();
        assertThat(row.get("PROCTRAN_TYPE")).isEqualTo("PCR");
        assertThat(row.get("PROCTRAN_DESC")).isEqualTo(pad40("ACME    CO    "));
    }

    // 10. L554-637
    @Test
    void proctranInsertFailureRollsBackAccountAndReturnsTruncatedFailCode0() {
        seedAccount(ACC_NO, "CURRENT", "100.00", "100.00");
        jdbc.execute("ALTER TABLE PROCTRAN RENAME TO PROCTRAN_OFFLINE");
        DbcrfunResponse response;
        try {
            response = service.applyTransaction(request(ACC_NO, "-25.00", TELLER));
        } finally {
            jdbc.execute("ALTER TABLE PROCTRAN_OFFLINE RENAME TO PROCTRAN");
        }

        assertThat(response.success()).isEqualTo("N");
        // MOVE '02' TO COMM-FAIL-CODE (PIC X) leaves '0'.
        assertThat(response.failCode()).isEqualTo("0").hasSize(1);
        // L416-419 had already moved the new balances into the COMMAREA before the rollback.
        assertThat(response.avBal()).isEqualByComparingTo("75.00");
        assertThat(response.actBal()).isEqualByComparingTo("75.00");
        assertBalances(ACC_NO, "100.00", "100.00");
        assertThat(proctranRows()).isEmpty();
    }

    // Edge case: L307 IF COMM-AMT < 0, so zero takes the credit path.
    @Test
    void zeroAmountIsTreatedAsCredit() {
        // Available balance is negative: a debit-path PAYMENT would fail with '3'.
        seedAccount(ACC_NO, "CURRENT", "-10.00", "-10.00");

        DbcrfunResponse response = service.applyTransaction(request(ACC_NO, "0.00", PAYMENT));

        assertThat(response.success()).isEqualTo("Y");
        assertThat(response.failCode()).isEqualTo("0");
        assertThat(singleProctranRow().get("PROCTRAN_TYPE")).isEqualTo("PCR");
    }

    @Test
    void zeroAmountTellerRequestWritesCreProctran() {
        seedAccount(ACC_NO, "CURRENT", "10.00", "10.00");

        DbcrfunResponse response = service.applyTransaction(request(ACC_NO, "0", TELLER));

        assertThat(response.success()).isEqualTo("Y");
        Map<String, Object> row = singleProctranRow();
        assertThat(row.get("PROCTRAN_TYPE")).isEqualTo("CRE");
        assertThat(row.get("PROCTRAN_DESC")).isEqualTo(pad40("COUNTER RECVED"));
    }

    // L211-212: COMM-SORTC input is overwritten with the site SORTCODE.
    @Test
    void inputSortcodeIsIgnoredAndSiteSortcodeIsUsed() {
        seedAccount(ACC_NO, "CURRENT", "100.00", "100.00");
        seedAccount("111111", ACC_NO, "CURRENT", "100.00", "100.00");

        DbcrfunResponse response = service.applyTransaction(
                new DbcrfunRequest(ACC_NO, new BigDecimal("5.00"), "111111",
                        new DbcrfunOrigin(null, null, null, null, TELLER)));

        assertThat(response.sortc()).isEqualTo(SORTCODE);
        assertBalances(ACC_NO, "105.00", "105.00");
        assertThat(jdbc.queryForObject(
                "SELECT ACCOUNT_AVAILABLE_BALANCE FROM ACCOUNT WHERE ACCOUNT_SORTCODE = '111111'",
                BigDecimal.class)).isEqualByComparingTo("100.00");
        assertThat(singleProctranRow().get("PROCTRAN_SORTCODE")).isEqualTo(SORTCODE);
    }

    @Test
    void shortAccountNumberIsZeroFilledLikePic9() {
        seedAccount(ACC_NO, "CURRENT", "100.00", "100.00");

        DbcrfunResponse response = service.applyTransaction(request("42", "1.00", TELLER));

        assertThat(response.success()).isEqualTo("Y");
        assertThat(singleProctranRow().get("PROCTRAN_NUMBER")).isEqualTo(ACC_NO);
    }

    private static DbcrfunRequest request(String accNo, String amt, int facilType) {
        return new DbcrfunRequest(accNo, new BigDecimal(amt), null,
                new DbcrfunOrigin("CBSAAPPL", "TELLER01", "T001", "NET1", facilType));
    }

    private void seedAccount(String accNo, String type, String available, String actual) {
        seedAccount(SORTCODE, accNo, type, available, actual);
    }

    private void seedAccount(String sortcode, String accNo, String type, String available, String actual) {
        jdbc.update("INSERT INTO ACCOUNT VALUES ('ACCT', '0000000001', ?, ?, ?, 1.50, DATE '2020-01-01', 0,"
                + " DATE '2026-10-01', DATE '2026-11-01', ?, ?)",
                sortcode, accNo, type, new BigDecimal(available), new BigDecimal(actual));
    }

    private void assertBalances(String accNo, String available, String actual) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE FROM ACCOUNT"
                        + " WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?", SORTCODE, accNo);
        assertThat((BigDecimal) row.get("ACCOUNT_AVAILABLE_BALANCE")).isEqualByComparingTo(available);
        assertThat((BigDecimal) row.get("ACCOUNT_ACTUAL_BALANCE")).isEqualByComparingTo(actual);
    }

    private List<Map<String, Object>> proctranRows() {
        return jdbc.queryForList("SELECT * FROM PROCTRAN");
    }

    private Map<String, Object> singleProctranRow() {
        List<Map<String, Object>> rows = proctranRows();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static String pad40(String value) {
        return String.format("%-40s", value);
    }
}
