package com.ibm.cics.cip.bank.core.delacc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replays every baseline/transcripts/DELACC/*.json against DELETE /banking/account/{id} from the canonical seed
 * (each case in a rolled-back test transaction) and asserts the COMMAREA output and the DB delta match the
 * legacy GnuCOBOL run. PROCTRAN_REF (EIBTASKN) is excluded from the comparison.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:delacc-replay;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "cbsa.clock.fixed=2026-01-15T10:30:00Z",
        "cbsa.random.seed=42"})
@AutoConfigureMockMvc
@Transactional
class DelaccTranscriptReplayTest {

    static final Path TRANSCRIPTS = Path.of("../../baseline/transcripts/DELACC");
    static final String FIXED_TIME = "2026-01-15T10:30:00Z";
    static final String RANDOM_SEED = "42";
    static final List<String> TABLES = List.of("CUSTOMER", "ACCOUNT", "PROCTRAN", "CONTROL");
    static final Map<String, List<String>> KEYS = Map.of(
            "CUSTOMER", List.of("CUSTOMER_SORTCODE", "CUSTOMER_NUMBER"),
            "ACCOUNT", List.of("ACCOUNT_SORTCODE", "ACCOUNT_NUMBER"),
            "CONTROL", List.of("CONTROL_NAME"));
    static final String EXCLUDED_COLUMN = "PROCTRAN_REF";
    static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("ddMMyyyy");

    static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    static Stream<Arguments> transcripts() throws IOException {
        assertThat(TRANSCRIPTS).isDirectory();
        try (Stream<Path> files = Files.list(TRANSCRIPTS)) {
            List<Path> list = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            assertThat(list).isNotEmpty();
            return list.stream().map(p -> Arguments.of(p.getFileName().toString(), p));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transcripts")
    void replay(String name, Path file) throws Exception {
        JsonNode t = JSON.readTree(file.toFile());
        assertThat(t.path("program").asText()).isEqualTo("DELACC");
        assertThat(t.path("env").path("CBSA_FIXED_TIME").asText()).isEqualTo(FIXED_TIME);
        assertThat(t.path("env").path("CBSA_RANDOM_SEED").asText()).isEqualTo(RANDOM_SEED);
        for (JsonNode sql : t.path("setup_sql")) {
            jdbc.execute(sql.asText());
        }

        Map<String, List<Map<String, Object>>> before = snapshot();
        long accno = t.path("commarea_in").path("DELACC-ACCNO").asLong();
        MvcResult result = mvc.perform(delete("/banking/account/{id}", accno)).andReturn();
        Map<String, List<Map<String, Object>>> after = snapshot();

        JsonNode out = t.path("commarea_out");
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        int status = result.getResponse().getStatus();
        JsonNode abend = t.path("abend");
        if (!abend.isNull() && !abend.isMissingNode()) {
            assertThat(status).isEqualTo(500);
            assertThat(body.path("abendCode").asText()).isEqualTo(abend.path("code").asText());
        } else if ("Y".equals(out.path("DELACC-DEL-SUCCESS").asText())) {
            assertThat(status).as("HTTP status").isEqualTo(200);
            assertAccountJson(out, body);
        } else {
            String failCode = out.path("DELACC-DEL-FAIL-CD").asText();
            assertThat(status).as("HTTP status").isEqualTo("1".equals(failCode) ? 404 : 500);
            assertThat(body.path("failCode").asText()).isEqualTo(failCode);
        }

        for (String table : TABLES) {
            JsonNode expected = t.path("db_delta").path(table);
            Delta actual = delta(table, before.get(table), after.get(table));
            assertThat(actual.inserted).as(table + " inserted").isEqualTo(rows(expected.path("inserted")));
            assertThat(actual.deleted).as(table + " deleted").isEqualTo(rows(expected.path("deleted")));
            assertThat(actual.updated).as(table + " updated").isEqualTo(updates(expected.path("updated")));
        }
    }

    private static void assertAccountJson(JsonNode out, JsonNode body) {
        assertThat(body.path("id").asText()).isEqualTo(String.format("%08d", out.path("DELACC-ACCNO").asLong()));
        assertThat(body.path("sortCode").asText()).isEqualTo(out.path("DELACC-SCODE").asText());
        assertThat(body.path("customerNumber").asText()).isEqualTo(out.path("DELACC-CUSTNO").asText());
        assertThat(body.path("accountType").asText()).isEqualTo(out.path("DELACC-ACC-TYPE").asText().trim());
        assertThat(body.path("interestRate").decimalValue()).isEqualByComparingTo(out.path("DELACC-INT-RATE").decimalValue());
        assertThat(body.path("overdraft").asInt()).isEqualTo(out.path("DELACC-OVERDRAFT").asInt());
        assertThat(body.path("availableBalance").decimalValue())
                .isEqualByComparingTo(out.path("DELACC-AVAIL-BAL").decimalValue());
        assertThat(body.path("actualBalance").decimalValue())
                .isEqualByComparingTo(out.path("DELACC-ACTUAL-BAL").decimalValue());
        assertThat(body.path("dateOpened").asText()).isEqualTo(isoDate(out.path("DELACC-OPENED")));
        assertThat(body.path("lastStatementDate").asText()).isEqualTo(isoDate(out.path("DELACC-LAST-STMT-DT")));
        assertThat(body.path("nextStatementDate").asText()).isEqualTo(isoDate(out.path("DELACC-NEXT-STMT-DT")));
    }

    /** COMMAREA dates are DDMMYYYY integers (leading zero dropped). */
    private static String isoDate(JsonNode ddmmyyyy) {
        return LocalDate.parse(String.format("%08d", ddmmyyyy.asLong()), DDMMYYYY).toString();
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> snap = new LinkedHashMap<>();
        for (String table : TABLES) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM " + table)) {
                Map<String, Object> norm = new TreeMap<>();
                row.forEach((k, v) -> {
                    if (!EXCLUDED_COLUMN.equalsIgnoreCase(k)) {
                        norm.put(k.toUpperCase(), normalize(v));
                    }
                });
                rows.add(norm);
            }
            snap.put(table, rows);
        }
        return snap;
    }

    record Delta(List<Map<String, Object>> inserted, List<Map<String, Object>> deleted,
            List<Map<String, Object>> updated) {
    }

    /** Keyed tables: rows matched by key; PROCTRAN (no key): multiset difference of whole rows. */
    private static Delta delta(String table, List<Map<String, Object>> before, List<Map<String, Object>> after) {
        List<Map<String, Object>> inserted = new ArrayList<>(after);
        List<Map<String, Object>> deleted = new ArrayList<>();
        for (Map<String, Object> row : before) {
            if (!inserted.remove(row)) {
                deleted.add(row);
            }
        }
        List<Map<String, Object>> updated = new ArrayList<>();
        List<String> key = KEYS.get(table);
        if (key != null) {
            for (Iterator<Map<String, Object>> it = deleted.iterator(); it.hasNext();) {
                Map<String, Object> old = it.next();
                Map<String, Object> neu = inserted.stream()
                        .filter(r -> key.stream().allMatch(k -> Objects.equals(r.get(k), old.get(k))))
                        .findFirst().orElse(null);
                if (neu != null) {
                    Map<String, Object> changes = new TreeMap<>();
                    old.forEach((k, v) -> {
                        if (!Objects.equals(v, neu.get(k))) {
                            changes.put(k, List.of(String.valueOf(v), String.valueOf(neu.get(k))));
                        }
                    });
                    updated.add(changes);
                    inserted.remove(neu);
                    it.remove();
                }
            }
        }
        return new Delta(inserted, deleted, updated);
    }

    private static List<Map<String, Object>> rows(JsonNode array) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode node : array) {
            Map<String, Object> row = new TreeMap<>();
            node.fields().forEachRemaining(e -> {
                if (!EXCLUDED_COLUMN.equals(e.getKey())) {
                    row.put(e.getKey(), normalize(e.getValue()));
                }
            });
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> updates(JsonNode array) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonNode node : array) {
            Map<String, Object> changes = new TreeMap<>();
            node.path("changes").fields().forEachRemaining(e -> changes.put(e.getKey(),
                    List.of(String.valueOf(normalize(e.getValue().get(0))),
                            String.valueOf(normalize(e.getValue().get(1))))));
            list.add(changes);
        }
        return list;
    }

    private static Object normalize(Object v) {
        if (v instanceof JsonNode n) {
            if (n.isNull()) {
                return null;
            }
            return n.isNumber() ? normalize(n.decimalValue()) : n.asText().trim();
        }
        if (v instanceof BigDecimal d) {
            return d.signum() == 0 ? BigDecimal.ZERO : d.stripTrailingZeros();
        }
        if (v instanceof Number n) {
            return normalize(new BigDecimal(n.toString()));
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof LocalDate d) {
            return d.toString();
        }
        return v == null ? null : v.toString().trim();
    }
}
