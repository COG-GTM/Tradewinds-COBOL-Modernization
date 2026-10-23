package com.ibm.cics.cip.bank.core.dbcrfun;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Test helpers: reset to the canonical seed, snapshot tables and compute transcript-style db_delta. */
final class DbcrfunTestDb {

    static final Path REPO_ROOT = Path.of("../..").toAbsolutePath().normalize();
    static final Path SEED = REPO_ROOT.resolve("baseline/seed/seed.sql");
    static final String[] TABLES = {"CUSTOMER", "ACCOUNT", "PROCTRAN", "CONTROL"};
    private static final Map<String, List<String>> KEYS = Map.of(
            "CUSTOMER", List.of("CUSTOMER_SORTCODE", "CUSTOMER_NUMBER"),
            "ACCOUNT", List.of("ACCOUNT_SORTCODE", "ACCOUNT_NUMBER"),
            "CONTROL", List.of("CONTROL_NAME"));

    /** Properties shared by the DBCRFUN tests: own H2 database, fixed clock and seed. */
    static final String DB = "spring.datasource.url=jdbc:h2:mem:dbcrfun;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
            + "DB_CLOSE_ON_EXIT=FALSE";
    static final String CLOCK = "cbsa.clock.fixed=2026-01-15T10:30:00Z";
    static final String RANDOM = "cbsa.random.seed=42";

    private DbcrfunTestDb() {
    }

    static void resetToSeed(JdbcTemplate jdbc, DataSource dataSource) {
        for (String t : TABLES) {
            jdbc.update("DELETE FROM " + t);
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new FileSystemResource(SEED));
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.execute(dataSource);
    }

    static Map<String, List<Map<String, Object>>> snapshot(JdbcTemplate jdbc) {
        Map<String, List<Map<String, Object>>> snap = new LinkedHashMap<>();
        for (String t : TABLES) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM " + t)) {
                Map<String, Object> n = new TreeMap<>();
                r.forEach((k, v) -> n.put(k.toUpperCase(), normalize(v)));
                rows.add(n);
            }
            snap.put(t, rows);
        }
        return snap;
    }

    /**
     * Delta in the transcript shape: per table {inserted, updated [{key, changes {col: [old, new]}}], deleted}.
     * PROCTRAN has no key, so it is diffed as a multiset of whole rows.
     */
    static Map<String, Object> delta(Map<String, List<Map<String, Object>>> before,
            Map<String, List<Map<String, Object>>> after) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String t : TABLES) {
            List<Map<String, Object>> b = before.get(t);
            List<Map<String, Object>> a = after.get(t);
            List<Map<String, Object>> inserted = new ArrayList<>();
            List<Map<String, Object>> deleted = new ArrayList<>();
            List<Map<String, Object>> updated = new ArrayList<>();
            List<String> key = KEYS.get(t);
            if (key == null) {
                List<Map<String, Object>> remaining = new ArrayList<>(b);
                for (Map<String, Object> row : a) {
                    if (!remaining.remove(row)) {
                        inserted.add(row);
                    }
                }
                deleted.addAll(remaining);
            } else {
                Map<List<Object>, Map<String, Object>> bm = index(b, key);
                Map<List<Object>, Map<String, Object>> am = index(a, key);
                for (var e : am.entrySet()) {
                    Map<String, Object> old = bm.get(e.getKey());
                    if (old == null) {
                        inserted.add(e.getValue());
                        continue;
                    }
                    Map<String, Object> changes = new TreeMap<>();
                    e.getValue().forEach((col, v) -> {
                        Object o = old.get(col);
                        if (o == null ? v != null : !o.equals(v)) {
                            changes.put(col, java.util.Arrays.asList(o, v));
                        }
                    });
                    if (!changes.isEmpty()) {
                        Map<String, Object> k = new TreeMap<>();
                        key.forEach(c -> k.put(c, e.getValue().get(c)));
                        updated.add(Map.of("key", k, "changes", changes));
                    }
                }
                bm.forEach((k, v) -> {
                    if (!am.containsKey(k)) {
                        deleted.add(v);
                    }
                });
            }
            out.put(t, Map.of("inserted", inserted, "updated", updated, "deleted", deleted));
        }
        return out;
    }

    private static Map<List<Object>, Map<String, Object>> index(List<Map<String, Object>> rows, List<String> key) {
        Map<List<Object>, Map<String, Object>> m = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            m.put(key.stream().map(r::get).toList(), r);
        }
        return m;
    }

    /** Comparable form: numbers as plain BigDecimal strings, dates ISO, strings right-trimmed. */
    static Object normalize(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd.signum() == 0 ? "0" : bd.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Number n) {
            return normalize(new BigDecimal(n.toString()));
        }
        if (v instanceof Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof LocalDate d) {
            return d.toString();
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate().toString();
        }
        return v.toString().stripTrailing();
    }
}
