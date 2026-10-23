package com.ibm.cics.cip.bank.core.delcus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Replays every baseline/transcripts/DELCUS/*.json case against DELETE /banking/customer/{id}: canonical seed,
 * the case's setup_sql, then compares the response with commarea_out and the database delta with db_delta
 * (all columns except PROCTRAN_REF, whose task number comes from a different sequence in each runtime).
 */
class DelcusTranscriptReplayTest extends DelcusTestSupport {

    private static final String[] TABLES = {"CUSTOMER", "ACCOUNT", "PROCTRAN", "CONTROL"};
    private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    ObjectMapper objectMapper;

    static Stream<Path> transcripts() throws IOException {
        try (Stream<Path> files = Files.list(BASELINE.resolve("transcripts/DELCUS"))) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList().stream();
        }
    }

    @Test
    void allFiveBaselineCasesArePresent() throws IOException {
        assertThat(transcripts().map(p -> p.getFileName().toString())).containsExactly(
                "no-accounts.json", "not-found.json", "single-account.json", "with-4-accounts.json",
                "with-mortgage.json");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transcripts")
    void replay(Path file) throws Exception {
        JsonNode t = JSON.readTree(file.toFile());
        assertThat(t.path("program").asText()).isEqualTo("DELCUS");
        assertThat(t.path("env").path("CBSA_FIXED_TIME").asText()).isEqualTo(FIXED_TIME);
        assertThat(t.path("env").path("CBSA_RANDOM_SEED").asText()).isEqualTo(RANDOM_SEED);
        assertThat(t.path("abend").isNull()).as("legacy abend").isTrue();

        for (JsonNode sql : t.path("setup_sql")) {
            jdbc.execute(sql.asText().replaceAll(";\\s*$", ""));
        }
        Map<String, List<String>> before = snapshot();

        JsonNode in = t.path("commarea_in");
        assertThat(in.path("COMM-SCODE").asText()).isEqualTo("987654");
        long customerNumber = Long.parseLong(in.path("COMM-CUSTNO").asText());
        MvcResult result = mvc.perform(delete("/banking/customer/{id}", customerNumber)).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        JsonNode out = t.path("commarea_out");
        if ("Y".equals(out.path("COMM-DEL-SUCCESS").asText())) {
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(body.path("id").asText()).isEqualTo(out.path("COMM-CUSTNO").asText());
            assertThat(body.path("sortCode").asText()).isEqualTo(out.path("COMM-SCODE").asText());
            assertThat(body.path("customerName").asText()).isEqualTo(out.path("COMM-NAME").asText());
            assertThat(body.path("customerAddress").asText()).isEqualTo(out.path("COMM-ADDR").asText());
            assertThat(ddmmyyyy(body.path("dateOfBirth"))).isEqualTo(out.path("COMM-DOB").asLong());
            assertThat(Integer.parseInt(body.path("customerCreditScore").asText()))
                    .isEqualTo(out.path("COMM-CREDIT-SCORE").asInt());
            assertThat(ddmmyyyy(body.path("customerCreditScoreReviewDate")))
                    .isEqualTo(out.path("COMM-CS-REVIEW-DATE").asLong());
            assertThat(out.path("COMM-DEL-FAIL-CD").asText()).isBlank();
        } else {
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(body.path("failCode").asText()).isEqualTo(out.path("COMM-DEL-FAIL-CD").asText());
        }

        long delaccLinks = 0;
        for (JsonNode step : t.path("trace")) {
            if ("LINK".equals(step.path(0).asText()) && "DELACC".equals(step.path(1).asText())) {
                delaccLinks++;
            }
        }
        assertThat(delacc.calls()).as("DELACC calls").hasSize((int) delaccLinks).isSorted();

        Map<String, List<String>> after = snapshot();
        JsonNode delta = t.path("db_delta");
        for (String table : TABLES) {
            JsonNode d = delta.path(table);
            assertThat(d.path("updated").size()).as(table + " updated").isZero();
            assertThat(removed(before.get(table), after.get(table)))
                    .as(table + " deleted").containsExactlyInAnyOrderElementsOf(expected(d.path("deleted")));
            assertThat(removed(after.get(table), before.get(table)))
                    .as(table + " inserted").containsExactlyElementsOf(expected(d.path("inserted")));
        }
        if (!delacc.calls().isEmpty()) {
            List<String> deletedAccounts = new ArrayList<>();
            delta.path("ACCOUNT").path("deleted")
                    .forEach(a -> deletedAccounts.add(a.path("ACCOUNT_NUMBER").asText()));
            assertThat(delacc.calls().stream().map(n -> String.format("%08d", n)))
                    .containsExactlyElementsOf(deletedAccounts.stream().sorted().toList());
        }
    }

    private static long ddmmyyyy(JsonNode isoDate) {
        return Long.parseLong(LocalDate.parse(isoDate.asText()).format(DDMMYYYY));
    }

    /** Table rows as canonical strings, in insertion (PROCTRAN) / key order. */
    private Map<String, List<String>> snapshot() {
        Map<String, List<String>> snap = new TreeMap<>();
        for (String table : TABLES) {
            String order = "PROCTRAN".equals(table) ? " ORDER BY _ROWID_" : "";
            List<String> rows = new ArrayList<>();
            for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM " + table + order)) {
                Map<String, String> canon = new TreeMap<>();
                row.forEach((col, value) -> {
                    String c = col.toUpperCase();
                    if (!c.equals("PROCTRAN_REF")) {
                        canon.put(c, canonical(value));
                    }
                });
                rows.add(canon.toString());
            }
            snap.put(table, rows);
        }
        return snap;
    }

    private static List<String> expected(JsonNode rows) {
        List<String> list = new ArrayList<>();
        for (JsonNode row : rows) {
            Map<String, String> canon = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = row.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                String c = e.getKey().toUpperCase();
                if (c.equals("PROCTRAN_REF")) {
                    continue;
                }
                JsonNode v = e.getValue();
                canon.put(c, v.isNull() ? null : v.isNumber() ? canonical(v.decimalValue()) : canonical(v.asText()));
            }
            list.add(canon.toString());
        }
        return list;
    }

    /** Rows of {@code from} not in {@code minus} (multiset difference, preserving order). */
    private static List<String> removed(List<String> from, List<String> minus) {
        List<String> remaining = new ArrayList<>(minus);
        List<String> diff = new ArrayList<>();
        for (String row : from) {
            if (!remaining.remove(row)) {
                diff.add(row);
            }
        }
        return diff;
    }

    private static String canonical(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            BigDecimal d = new BigDecimal(n.toString()).stripTrailingZeros();
            return d.signum() == 0 ? "0" : d.toPlainString();
        }
        return value.toString().stripTrailing();
    }
}
