package com.ibm.cics.cip.bank.core.screens;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Snapshot / diff of the four CBSA tables in the transcript {@code db_delta} shape, with values
 * normalised (CHAR padding stripped, dates ISO, decimals without trailing zeros). PROCTRAN rows are
 * compared as a multiset excluding PROCTRAN_REF.
 */
final class DbDelta {

    static final String SEED = "../../baseline/seed/seed.sql";
    static final String TRANSCRIPTS = "../../baseline/transcripts/";

    private static final Map<String, List<String>> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("CUSTOMER", List.of("CUSTOMER_SORTCODE", "CUSTOMER_NUMBER"));
        KEYS.put("ACCOUNT", List.of("ACCOUNT_SORTCODE", "ACCOUNT_NUMBER"));
        KEYS.put("PROCTRAN", List.of());
        KEYS.put("CONTROL", List.of("CONTROL_NAME"));
    }

    private DbDelta() {
    }

    /** Restore the canonical seed (baseline/seed/seed.sql) into all four tables. */
    static void resetToSeed(JdbcTemplate jdbc, DataSource ds) {
        for (String t : KEYS.keySet()) {
            jdbc.update("DELETE FROM " + t);
        }
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(new FileSystemResource(SEED));
        pop.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        pop.execute(ds);
    }

    static Map<String, List<Map<String, String>>> snapshot(JdbcTemplate jdbc) {
        Map<String, List<Map<String, String>>> snap = new LinkedHashMap<>();
        for (String t : KEYS.keySet()) {
            List<Map<String, String>> rows = new ArrayList<>();
            for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM " + t)) {
                Map<String, String> n = new TreeMap<>();
                r.forEach((k, v) -> n.put(k.toUpperCase(), norm(v)));
                rows.add(n);
            }
            snap.put(t, rows);
        }
        return snap;
    }

    /** Normalised delta {table -> {inserted:[rows], updated:[{key, changes}], deleted:[rows]}}. */
    static Map<String, Map<String, List<String>>> diff(Map<String, List<Map<String, String>>> before,
            Map<String, List<Map<String, String>>> after) {
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        for (String t : KEYS.keySet()) {
            List<String> keys = KEYS.get(t);
            List<String> ins = new ArrayList<>();
            List<String> upd = new ArrayList<>();
            List<String> del = new ArrayList<>();
            if (keys.isEmpty()) {
                List<String> b = new ArrayList<>(before.get(t).stream().map(DbDelta::withoutRef).toList());
                for (Map<String, String> r : after.get(t)) {
                    String s = withoutRef(r);
                    if (!b.remove(s)) {
                        ins.add(s);
                    }
                }
                del.addAll(b);
            } else {
                Map<String, Map<String, String>> b = index(before.get(t), keys);
                Map<String, Map<String, String>> a = index(after.get(t), keys);
                a.forEach((k, row) -> {
                    Map<String, String> old = b.get(k);
                    if (old == null) {
                        ins.add(row.toString());
                    } else {
                        Map<String, List<String>> changes = new TreeMap<>();
                        row.forEach((c, v) -> {
                            if (!java.util.Objects.equals(v, old.get(c))) {
                                changes.put(c, java.util.Arrays.asList(old.get(c), v));
                            }
                        });
                        if (!changes.isEmpty()) {
                            upd.add(k + "=" + changes);
                        }
                    }
                });
                b.forEach((k, row) -> {
                    if (!a.containsKey(k)) {
                        del.add(row.toString());
                    }
                });
            }
            out.put(t, sorted(ins, upd, del));
        }
        return out;
    }

    /** The transcript's db_delta in the same normalised form as {@link #diff}. */
    static Map<String, Map<String, List<String>>> expected(JsonNode dbDelta) {
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        for (String t : KEYS.keySet()) {
            JsonNode td = dbDelta.path(t);
            List<String> keys = KEYS.get(t);
            List<String> ins = new ArrayList<>();
            List<String> upd = new ArrayList<>();
            List<String> del = new ArrayList<>();
            for (JsonNode r : td.path("inserted")) {
                Map<String, String> row = row(r);
                ins.add(keys.isEmpty() ? withoutRef(row) : row.toString());
            }
            for (JsonNode r : td.path("deleted")) {
                Map<String, String> row = row(r);
                del.add(keys.isEmpty() ? withoutRef(row) : row.toString());
            }
            for (JsonNode u : td.path("updated")) {
                Map<String, String> key = row(u.path("key"));
                StringBuilder k = new StringBuilder();
                for (String c : keys) {
                    k.append(key.get(c)).append('|');
                }
                Map<String, List<String>> changes = new TreeMap<>();
                Iterator<Map.Entry<String, JsonNode>> it = u.path("changes").fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    changes.put(e.getKey(), java.util.Arrays.asList(norm(e.getValue().get(0)), norm(e.getValue().get(1))));
                }
                upd.add(k + "=" + changes);
            }
            out.put(t, sorted(ins, upd, del));
        }
        return out;
    }

    private static Map<String, List<String>> sorted(List<String> ins, List<String> upd, List<String> del) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("inserted", ins.stream().sorted(Comparator.naturalOrder()).toList());
        m.put("updated", upd.stream().sorted(Comparator.naturalOrder()).toList());
        m.put("deleted", del.stream().sorted(Comparator.naturalOrder()).toList());
        return m;
    }

    private static Map<String, Map<String, String>> index(List<Map<String, String>> rows, List<String> keys) {
        Map<String, Map<String, String>> m = new TreeMap<>();
        for (Map<String, String> r : rows) {
            StringBuilder k = new StringBuilder();
            for (String c : keys) {
                k.append(r.get(c)).append('|');
            }
            m.put(k.toString(), r);
        }
        return m;
    }

    private static Map<String, String> row(JsonNode node) {
        Map<String, String> m = new TreeMap<>();
        node.fields().forEachRemaining(e -> m.put(e.getKey().toUpperCase(), norm(e.getValue())));
        return m;
    }

    private static String withoutRef(Map<String, String> row) {
        Map<String, String> m = new TreeMap<>(row);
        m.remove("PROCTRAN_REF");
        return m.toString();
    }

    static String norm(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.decimalValue().stripTrailingZeros().toPlainString();
        }
        return v.asText().stripTrailing();
    }

    static String norm(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal d) {
            return d.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        return v.toString().stripTrailing();
    }
}
