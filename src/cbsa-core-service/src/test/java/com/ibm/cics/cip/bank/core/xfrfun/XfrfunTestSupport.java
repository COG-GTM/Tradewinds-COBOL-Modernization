package com.ibm.cics.cip.bank.core.xfrfun;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared test plumbing: the fixed clock / seed of the baseline transcripts, a private H2 database (so other
 * tickets' tests cannot shift the canonical seed balances under us) and snapshot/restore of the tables
 * XFRFUN touches so every test starts from the canonical seed.
 */
final class XfrfunTestSupport {

    static final String FIXED_TIME = "cbsa.clock.fixed=2026-01-15T10:30:00Z";
    static final String RANDOM_SEED = "cbsa.random.seed=42";
    static final String PRIVATE_DB =
            "spring.datasource.url=jdbc:h2:mem:cbsa-xfrfun;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    private XfrfunTestSupport() {
    }

    /** ACCOUNT balances by 8-char account number, and the full PROCTRAN table. */
    record Snapshot(Map<String, BigDecimal[]> balances, List<Map<String, Object>> proctran) {
    }

    static Snapshot snapshot(JdbcTemplate jdbc) {
        Map<String, BigDecimal[]> balances = new LinkedHashMap<>();
        jdbc.query("SELECT ACCOUNT_NUMBER, ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE FROM ACCOUNT "
                + "ORDER BY ACCOUNT_NUMBER", rs -> {
                    balances.put(rs.getString(1).trim(),
                            new BigDecimal[] {rs.getBigDecimal(2), rs.getBigDecimal(3)});
                });
        return new Snapshot(balances, proctranRows(jdbc));
    }

    static List<Map<String, Object>> proctranRows(JdbcTemplate jdbc) {
        return new ArrayList<>(jdbc.queryForList("SELECT * FROM PROCTRAN"));
    }

    static void restore(JdbcTemplate jdbc, Snapshot s) {
        s.balances().forEach((acc, bal) -> jdbc.update("UPDATE ACCOUNT SET ACCOUNT_AVAILABLE_BALANCE = ?, "
                + "ACCOUNT_ACTUAL_BALANCE = ? WHERE ACCOUNT_NUMBER = ?", bal[0], bal[1], acc));
        jdbc.update("DELETE FROM PROCTRAN");
        for (Map<String, Object> row : s.proctran()) {
            jdbc.update("INSERT INTO PROCTRAN (PROCTRAN_EYECATCHER, PROCTRAN_SORTCODE, PROCTRAN_NUMBER, "
                    + "PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_REF, PROCTRAN_TYPE, PROCTRAN_DESC, PROCTRAN_AMOUNT) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    row.get("PROCTRAN_EYECATCHER"), row.get("PROCTRAN_SORTCODE"), row.get("PROCTRAN_NUMBER"),
                    row.get("PROCTRAN_DATE"), row.get("PROCTRAN_TIME"), row.get("PROCTRAN_REF"),
                    row.get("PROCTRAN_TYPE"), row.get("PROCTRAN_DESC"), row.get("PROCTRAN_AMOUNT"));
        }
    }

    /** Rows of {@code after} not present in {@code before} (multiset difference). */
    static List<Map<String, Object>> inserted(List<Map<String, Object>> before, List<Map<String, Object>> after) {
        List<Map<String, Object>> remaining = new ArrayList<>(before);
        List<Map<String, Object>> added = new ArrayList<>();
        for (Map<String, Object> row : after) {
            if (!remaining.remove(row)) {
                added.add(row);
            }
        }
        return added;
    }

    static BigDecimal[] balances(JdbcTemplate jdbc, long account) {
        return jdbc.queryForObject("SELECT ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE FROM ACCOUNT "
                + "WHERE ACCOUNT_NUMBER = ?", (rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)},
                String.format("%08d", account));
    }

    static String rtrim(Object o) {
        return o == null ? null : o.toString().replaceAll("\\s+$", "");
    }
}
