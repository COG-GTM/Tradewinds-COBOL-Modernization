package com.ibm.cics.cip.bank.core.updacc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Test helper: resets the (UPDACC-private) H2 database to the canonical seed and snapshots/diffs the four
 * tables in the transcript {@code db_delta} shape.
 */
final class UpdaccTestDatabase {

    static final Path BASELINE = locateBaseline();
    static final Path SEED = BASELINE.resolve("seed/seed.sql");

    /** Primary key columns per table; PROCTRAN has none (rows compared without PROCTRAN_REF). */
    static final Map<String, List<String>> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("CUSTOMER", List.of("CUSTOMER_SORTCODE", "CUSTOMER_NUMBER"));
        KEYS.put("ACCOUNT", List.of("ACCOUNT_SORTCODE", "ACCOUNT_NUMBER"));
        KEYS.put("PROCTRAN", List.of());
        KEYS.put("CONTROL", List.of("CONTROL_NAME"));
    }

    private UpdaccTestDatabase() {
    }

    private static Path locateBaseline() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("baseline");
            if (Files.isRegularFile(candidate.resolve("seed/seed.sql"))) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("baseline/seed/seed.sql not found above " + Path.of("").toAbsolutePath());
    }

    static void resetToSeed(JdbcTemplate jdbc, DataSource dataSource) {
        for (String table : KEYS.keySet()) {
            jdbc.update("DELETE FROM " + table);
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new FileSystemResource(SEED));
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.execute(dataSource);
    }

    /** table -> rows (normalised column values). */
    static Map<String, List<Map<String, Object>>> snapshot(JdbcTemplate jdbc) {
        Map<String, List<Map<String, Object>>> snap = new LinkedHashMap<>();
        for (String table : KEYS.keySet()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM " + table)) {
                Map<String, Object> norm = new TreeMap<>();
                row.forEach((k, v) -> norm.put(k.toUpperCase(), normalise(v)));
                if ("PROCTRAN".equals(table)) {
                    norm.remove("PROCTRAN_REF");
                }
                rows.add(norm);
            }
            snap.put(table, rows);
        }
        return snap;
    }

    /** Delta in the transcript shape: {inserted: [row], updated: [{key, changes: {col: [old, new]}}], deleted: [row]}. */
    static Map<String, Map<String, List<Object>>> delta(Map<String, List<Map<String, Object>>> before,
            Map<String, List<Map<String, Object>>> after) {
        Map<String, Map<String, List<Object>>> result = new LinkedHashMap<>();
        for (String table : KEYS.keySet()) {
            List<String> keyCols = KEYS.get(table);
            Map<String, List<Object>> d = new LinkedHashMap<>();
            d.put("inserted", new ArrayList<>());
            d.put("updated", new ArrayList<>());
            d.put("deleted", new ArrayList<>());
            if (keyCols.isEmpty()) {
                List<Map<String, Object>> remaining = new ArrayList<>(before.get(table));
                for (Map<String, Object> row : after.get(table)) {
                    if (!remaining.remove(row)) {
                        d.get("inserted").add(row);
                    }
                }
                d.get("deleted").addAll(remaining);
            } else {
                Map<Map<String, Object>, Map<String, Object>> old = index(before.get(table), keyCols);
                Map<Map<String, Object>, Map<String, Object>> now = index(after.get(table), keyCols);
                now.forEach((key, row) -> {
                    Map<String, Object> prev = old.get(key);
                    if (prev == null) {
                        d.get("inserted").add(row);
                    } else if (!prev.equals(row)) {
                        Map<String, Object> changes = new TreeMap<>();
                        row.forEach((col, v) -> {
                            if (!java.util.Objects.equals(prev.get(col), v)) {
                                changes.put(col, List.of(String.valueOf(prev.get(col)), String.valueOf(v)));
                            }
                        });
                        Map<String, Object> upd = new LinkedHashMap<>();
                        upd.put("key", key);
                        upd.put("changes", changes);
                        d.get("updated").add(upd);
                    }
                });
                old.forEach((key, row) -> {
                    if (!now.containsKey(key)) {
                        d.get("deleted").add(row);
                    }
                });
            }
            result.put(table, d);
        }
        return result;
    }

    private static Map<Map<String, Object>, Map<String, Object>> index(List<Map<String, Object>> rows,
            List<String> keyCols) {
        Map<Map<String, Object>, Map<String, Object>> idx = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> key = new TreeMap<>();
            keyCols.forEach(c -> key.put(c, row.get(c)));
            idx.put(key, row);
        }
        return idx;
    }

    /** Canonical comparable form: numbers as plain strings without trailing zeros, strings right-trimmed. */
    static Object normalise(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd.signum() == 0 ? "0" : bd.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Number n) {
            return normalise(new BigDecimal(n.toString()));
        }
        if (v instanceof Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof CharSequence s) {
            return s.toString().stripTrailing();
        }
        return v.toString();
    }
}
