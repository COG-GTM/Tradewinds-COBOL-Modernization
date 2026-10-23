package com.ibm.cics.cip.bank.core.creacc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replays every baseline/transcripts/CREACC/*.json case (recorded by running the legacy COBOL under the GnuCOBOL
 * harness) against {@link CreateAccountService} from the canonical seed with the same fixed clock, and asserts
 * the same COMMAREA outputs and the same DB delta (ACCOUNT, PROCTRAN excluding PROCTRAN_REF, CONTROL, CUSTOMER).
 * Each case runs in a rolled-back transaction on a private H2 database, so every case starts from the seed.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:creacc-replay;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "cbsa.clock.fixed=2026-01-15T10:30:00Z",
        "cbsa.random.seed=42"})
@Transactional
class CreaccTranscriptReplayTest {

    static final Path TRANSCRIPTS = Paths.get("../../baseline/transcripts/CREACC");
    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String, List<String>> KEYS = Map.of(
            "CUSTOMER", List.of("CUSTOMER_SORTCODE", "CUSTOMER_NUMBER"),
            "ACCOUNT", List.of("ACCOUNT_SORTCODE", "ACCOUNT_NUMBER"),
            "CONTROL", List.of("CONTROL_NAME"),
            "PROCTRAN", List.of());
    static final List<String> IGNORED = List.of("PROCTRAN_REF");

    @Autowired
    CreateAccountService service;

    @Autowired
    JdbcTemplate jdbc;

    static Stream<Path> transcripts() throws IOException {
        try (Stream<Path> files = Files.list(TRANSCRIPTS)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList().stream();
        }
    }

    @Test
    void everyRecordedCaseIsReplayed() throws IOException {
        assertThat(transcripts().count()).isEqualTo(10);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transcripts")
    void replay(Path file) throws IOException {
        JsonNode t = JSON.readTree(file.toFile());
        assertThat(t.path("env").path("CBSA_FIXED_TIME").asText()).isEqualTo("2026-01-15T10:30:00Z");
        for (JsonNode sql : t.path("setup_sql")) {
            jdbc.execute(toH2(sql.asText()));
        }
        Map<String, List<Map<String, Object>>> before = snapshot();

        JsonNode in = t.path("commarea_in");
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Account a = service.createAccount(in.path("COMM-CUSTNO").asLong(), in.path("COMM-ACC-TYPE").asText(),
                    in.path("COMM-INT-RT").decimalValue(), in.path("COMM-OVERDR-LIM").asInt(),
                    in.path("COMM-AVAIL-BAL").decimalValue(), in.path("COMM-ACT-BAL").decimalValue());
            out.put("COMM-SUCCESS", "Y");
            out.put("COMM-FAIL-CODE", "");
            out.put("COMM-NUMBER", a.accountNumber());
            out.put("COMM-OPENED", Long.parseLong(CbsaFormats.ddmmyyyy(a.opened())));
            out.put("COMM-LAST-STMT-DT", Long.parseLong(CbsaFormats.ddmmyyyy(a.lastStatement())));
            out.put("COMM-NEXT-STMT-DT", Long.parseLong(CbsaFormats.ddmmyyyy(a.nextStatement())));
            out.put("COMM-ACC-TYPE", a.accountType());
            out.put("COMM-INT-RT", a.interestRate());
            out.put("COMM-OVERDR-LIM", (long) a.overdraftLimit());
            out.put("COMM-AVAIL-BAL", a.availableBalance());
            out.put("COMM-ACT-BAL", a.actualBalance());
        } catch (CbsaException e) {
            out.put("COMM-SUCCESS", "N");
            out.put("COMM-FAIL-CODE", e.failCode());
            out.put("COMM-NUMBER", 0L);
            out.put("COMM-OPENED", 0L);
            out.put("COMM-LAST-STMT-DT", 0L);
            out.put("COMM-NEXT-STMT-DT", 0L);
        }

        JsonNode expected = t.path("commarea_out");
        Map<String, Object> want = new LinkedHashMap<>();
        for (String k : out.keySet()) {
            JsonNode v = "COMM-NUMBER".equals(k) ? expected.path("COMM-KEY").path(k) : expected.path(k);
            want.put(k, normalise(v));
        }
        Map<String, Object> got = new LinkedHashMap<>();
        out.forEach((k, v) -> got.put(k, normalise(v)));
        assertThat(got).as("commarea_out of " + file.getFileName()).isEqualTo(want);
        assertThat(t.path("abend").isNull()).isTrue();

        assertThat(delta(before, snapshot())).as("db_delta of " + file.getFileName())
                .isEqualTo(expectedDelta(t.path("db_delta")));
    }

    /** The harness setup SQL is SQLite; translate printf / recursive CTE to H2. */
    static String toH2(String sql) {
        return sql.replaceAll("printf\\('%08d',([^)]*)\\)", "LPAD(CAST($1 AS VARCHAR), 8, '0')")
                .replaceAll("\\(WITH RECURSIVE n\\(value\\) AS \\(SELECT 1 UNION ALL SELECT value\\+1 FROM n WHERE "
                        + "value<(\\d+)\\) SELECT value FROM n\\)", "SYSTEM_RANGE(1, $1) r")
                .replaceAll("\\bvalue\\b", "r.X")
                .replaceAll(";\\s*$", "");
    }

    Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> s = new LinkedHashMap<>();
        for (String table : KEYS.keySet()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM " + table)) {
                Map<String, Object> n = new LinkedHashMap<>();
                r.forEach((k, v) -> {
                    if (!IGNORED.contains(k.toUpperCase())) {
                        n.put(k.toUpperCase(), normalise(v));
                    }
                });
                rows.add(n);
            }
            s.put(table, rows);
        }
        return s;
    }

    static Map<String, Object> delta(Map<String, List<Map<String, Object>>> before,
            Map<String, List<Map<String, Object>>> after) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (String table : KEYS.keySet()) {
            List<String> key = KEYS.get(table);
            List<Map<String, Object>> b = before.get(table);
            List<Map<String, Object>> a = after.get(table);
            List<Object> inserted = new ArrayList<>();
            List<Object> deleted = new ArrayList<>();
            List<Object> updated = new ArrayList<>();
            if (key.isEmpty()) {
                List<Map<String, Object>> rest = new ArrayList<>(b);
                for (Map<String, Object> r : a) {
                    if (!rest.remove(r)) {
                        inserted.add(r);
                    }
                }
                deleted.addAll(rest);
            } else {
                Map<List<Object>, Map<String, Object>> bk = byKey(b, key);
                Map<List<Object>, Map<String, Object>> ak = byKey(a, key);
                ak.forEach((k, r) -> {
                    Map<String, Object> old = bk.get(k);
                    if (old == null) {
                        inserted.add(r);
                    } else if (!old.equals(r)) {
                        Map<String, Object> changes = new LinkedHashMap<>();
                        r.forEach((c, v) -> {
                            if (!Objects.equals(old.get(c), v)) {
                                changes.put(c, List.of(String.valueOf(old.get(c)), String.valueOf(v)));
                            }
                        });
                        Map<String, Object> keyMap = new LinkedHashMap<>();
                        key.forEach(c -> keyMap.put(c, r.get(c)));
                        updated.add(Map.of("key", keyMap, "changes", changes));
                    }
                });
                bk.forEach((k, r) -> {
                    if (!ak.containsKey(k)) {
                        deleted.add(r);
                    }
                });
            }
            d.put(table, Map.of("inserted", sorted(inserted), "updated", sorted(updated), "deleted", sorted(deleted)));
        }
        return d;
    }

    static Map<String, Object> expectedDelta(JsonNode dbDelta) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (String table : KEYS.keySet()) {
            JsonNode t = dbDelta.path(table);
            List<Object> inserted = new ArrayList<>();
            List<Object> deleted = new ArrayList<>();
            List<Object> updated = new ArrayList<>();
            t.path("inserted").forEach(r -> inserted.add(row(r)));
            t.path("deleted").forEach(r -> deleted.add(row(r)));
            for (JsonNode u : t.path("updated")) {
                Map<String, Object> changes = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> it = u.path("changes").fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    changes.put(e.getKey(), List.of(String.valueOf(normalise(e.getValue().get(0))),
                            String.valueOf(normalise(e.getValue().get(1)))));
                }
                updated.add(Map.of("key", row(u.path("key")), "changes", changes));
            }
            d.put(table, Map.of("inserted", sorted(inserted), "updated", sorted(updated), "deleted", sorted(deleted)));
        }
        return d;
    }

    static Map<String, Object> row(JsonNode r) {
        Map<String, Object> m = new LinkedHashMap<>();
        r.fields().forEachRemaining(e -> {
            if (!IGNORED.contains(e.getKey())) {
                m.put(e.getKey(), normalise(e.getValue()));
            }
        });
        return m;
    }

    static Map<List<Object>, Map<String, Object>> byKey(List<Map<String, Object>> rows, List<String> key) {
        Map<List<Object>, Map<String, Object>> m = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            m.put(key.stream().map(r::get).toList(), r);
        }
        return m;
    }

    static List<Object> sorted(List<Object> rows) {
        return rows.stream().sorted((x, y) -> String.valueOf(x).compareTo(String.valueOf(y))).toList();
    }

    /** Compare values the way the harness records them: trimmed strings, numbers by value, ISO dates. */
    static Object normalise(Object v) {
        if (v instanceof JsonNode n) {
            if (n.isNull() || n.isMissingNode()) {
                return null;
            }
            if (n.isNumber()) {
                return normalise(n.decimalValue());
            }
            return normalise(n.asText());
        }
        if (v instanceof BigDecimal b) {
            return b.signum() == 0 ? "0" : b.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Number n) {
            return normalise(new BigDecimal(n.toString()));
        }
        if (v instanceof String s) {
            return s.stripTrailing();
        }
        return v == null ? null : v.toString();
    }
}
