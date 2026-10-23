package com.ibm.cics.cip.bank.core.inqacc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Replays every legacy transcript in baseline/transcripts/INQACC and baseline/transcripts/INQACCCU (recorded by
 * running the COBOL under GnuCOBOL, see baseline/README.md) against the REST API, from the canonical seed with
 * the transcript's fixed clock and random seed, and asserts the same COMMAREA outcome and the same DB delta
 * (CUSTOMER, ACCOUNT, PROCTRAN excluding PROCTRAN_REF, CONTROL). Each case runs in a rolled-back transaction so
 * its {@code setup_sql} cannot leak into other tests.
 */
@SpringBootTest(properties = {"cbsa.clock.fixed=2026-01-15T10:30:00Z", "cbsa.random.seed=42"})
@AutoConfigureMockMvc
class InqaccTranscriptReplayTest {

    static final Path TRANSCRIPTS = Path.of("../../baseline/transcripts");
    static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("ddMMyyyy");
    static final Map<String, String> TABLE_ORDER = Map.of(
            "CUSTOMER", "CUSTOMER_SORTCODE, CUSTOMER_NUMBER",
            "ACCOUNT", "ACCOUNT_SORTCODE, ACCOUNT_NUMBER",
            "PROCTRAN", "PROCTRAN_SORTCODE, PROCTRAN_NUMBER, PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_TYPE",
            "CONTROL", "CONTROL_NAME");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    PlatformTransactionManager txManager;

    @TestFactory
    Stream<DynamicTest> replayInqacc() throws IOException {
        return cases("INQACC", this::replayInqaccCase);
    }

    @TestFactory
    Stream<DynamicTest> replayInqacccu() throws IOException {
        return cases("INQACCCU", this::replayInqacccuCase);
    }

    interface CaseRunner {
        void run(JsonNode transcript) throws Exception;
    }

    interface Body {
        void run() throws Exception;
    }

    Stream<DynamicTest> cases(String program, CaseRunner runner) throws IOException {
        Path dir = TRANSCRIPTS.resolve(program);
        assertThat(dir).as("transcript directory %s", dir.toAbsolutePath()).isDirectory();
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(files).as("%s transcripts", program).isNotEmpty();
        return files.stream().map(f -> DynamicTest.dynamicTest(program + "/" + f.getFileName(), () -> {
            JsonNode t = json.readTree(f.toFile());
            assertThat(t.path("env").path("CBSA_FIXED_TIME").asText()).isEqualTo("2026-01-15T10:30:00Z");
            assertThat(t.path("env").path("CBSA_RANDOM_SEED").asText()).isEqualTo("42");
            inRollback(() -> {
                for (JsonNode sql : t.path("setup_sql")) {
                    jdbc.execute(sql.asText().replaceAll(";\\s*$", ""));
                }
                Map<String, List<Map<String, Object>>> before = snapshot();
                runner.run(t);
                assertDbDelta(t.path("db_delta"), before, snapshot());
            });
        }));
    }

    void replayInqaccCase(JsonNode t) throws Exception {
        JsonNode in = t.path("commarea_in");
        JsonNode out = t.path("commarea_out");
        long accno = in.path("INQACC-ACCNO").asLong();
        MvcResult r = mvc.perform(get("/banking/account/{id}", accno)).andReturn();
        JsonNode body = exactJson().readTree(r.getResponse().getContentAsString());
        if ("Y".equals(out.path("INQACC-SUCCESS").asText())) {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(body.path("id").asText()).isEqualTo(String.format("%08d", out.path("INQACC-ACCNO").asLong()));
            assertThat(body.path("customerNumber").asText())
                    .isEqualTo(String.format("%010d", out.path("INQACC-CUSTNO").asLong()));
            assertThat(body.path("sortCode").asText())
                    .isEqualTo(String.format("%06d", out.path("INQACC-SCODE").asLong()));
            assertThat(body.path("accountType").asText()).isEqualTo(out.path("INQACC-ACC-TYPE").asText().strip());
            assertMoney(body.path("interestRate"), out.path("INQACC-INT-RATE"));
            assertThat(body.path("overdraft").asLong()).isEqualTo(out.path("INQACC-OVERDRAFT").asLong());
            assertDate(body.path("dateOpened"), out.path("INQACC-OPENED"));
            assertDate(body.path("lastStatementDate"), out.path("INQACC-LAST-STMT-DT"));
            assertDate(body.path("nextStatementDate"), out.path("INQACC-NEXT-STMT-DT"));
            assertMoney(body.path("availableBalance"), out.path("INQACC-AVAIL-BAL"));
            assertMoney(body.path("actualBalance"), out.path("INQACC-ACTUAL-BAL"));
        } else {
            assertThat(r.getResponse().getStatus()).isEqualTo(404);
            assertThat(body.path("errorMessage").asText()).isEqualTo("Account number " + accno + " not found.");
        }
    }

    void replayInqacccuCase(JsonNode t) throws Exception {
        JsonNode out = t.path("commarea_out");
        long custno = t.path("commarea_in").path("CUSTOMER-NUMBER").asLong();
        MvcResult r = mvc.perform(get("/banking/account/retrieveByCustomerNumber/{id}", custno)).andReturn();
        JsonNode body = exactJson().readTree(r.getResponse().getContentAsString());
        if ("Y".equals(out.path("COMM-SUCCESS").asText())) {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(body.path("customerFound").asBoolean())
                    .isEqualTo("Y".equals(out.path("CUSTOMER-FOUND").asText()));
            assertThat(body.path("customerNumber").asText()).isEqualTo(String.format("%010d", custno));
            assertThat(body.path("numberOfAccounts").asInt()).isEqualTo(out.path("NUMBER-OF-ACCOUNTS").asInt());
            JsonNode expected = out.path("ACCOUNT-DETAILS");
            JsonNode actual = body.path("accounts");
            assertThat(actual.size()).isEqualTo(expected.size());
            for (int i = 0; i < expected.size(); i++) {
                JsonNode e = expected.get(i);
                JsonNode a = actual.get(i);
                assertThat(a.path("id").asText()).isEqualTo(String.format("%08d", e.path("COMM-ACCNO").asLong()));
                assertThat(a.path("customerNumber").asText()).isEqualTo(e.path("COMM-CUSTNO").asText());
                assertThat(a.path("sortCode").asText()).isEqualTo(e.path("COMM-SCODE").asText());
                assertThat(a.path("accountType").asText()).isEqualTo(e.path("COMM-ACC-TYPE").asText().strip());
                assertMoney(a.path("interestRate"), e.path("COMM-INT-RATE"));
                assertThat(a.path("overdraft").asLong()).isEqualTo(e.path("COMM-OVERDRAFT").asLong());
                assertDate(a.path("dateOpened"), e.path("COMM-OPENED"));
                assertDate(a.path("lastStatementDate"), e.path("COMM-LAST-STMT-DT"));
                assertDate(a.path("nextStatementDate"), e.path("COMM-NEXT-STMT-DT"));
                assertMoney(a.path("availableBalance"), e.path("COMM-AVAIL-BAL"));
                assertMoney(a.path("actualBalance"), e.path("COMM-ACTUAL-BAL"));
            }
        } else {
            String failCode = out.path("COMM-FAIL-CODE").asText();
            assertThat(r.getResponse().getStatus()).isEqualTo("1".equals(failCode) ? 404 : 500);
            assertThat(body.path("failCode").asText()).isEqualTo(failCode);
            assertThat(out.path("NUMBER-OF-ACCOUNTS").asInt()).isZero();
        }
    }

    /** Keeps the response's decimal scale so the 2-dp contract can be asserted. */
    ObjectMapper exactJson() {
        return json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
    }

    static void assertMoney(JsonNode actual, JsonNode expected) {
        assertThat(actual.isNumber()).as("numeric %s", actual).isTrue();
        assertThat(actual.decimalValue()).isEqualByComparingTo(new BigDecimal(expected.asText()));
        assertThat(actual.decimalValue().scale()).as("2-dp %s", actual).isEqualTo(2);
    }

    /** COMMAREA dates are DDMMYYYY integers (leading zero dropped by the numeric encoding). */
    static void assertDate(JsonNode actual, JsonNode ddmmyyyy) {
        LocalDate expected = LocalDate.parse(String.format("%08d", ddmmyyyy.asLong()), DDMMYYYY);
        assertThat(actual.asText()).isEqualTo(expected.toString());
    }

    void inRollback(Body body) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            try {
                body.run();
            } catch (Exception e) {
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> snap = new TreeMap<>();
        TABLE_ORDER.forEach((table, order) -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM " + table + " ORDER BY " + order)) {
                Map<String, Object> norm = new TreeMap<>();
                row.forEach((k, v) -> {
                    if (!"PROCTRAN_REF".equalsIgnoreCase(k)) {
                        norm.put(k.toUpperCase(), v instanceof String s ? s.strip() : String.valueOf(v));
                    }
                });
                rows.add(norm);
            }
            snap.put(table, rows);
        });
        return snap;
    }

    /** Asserts inserted / deleted row counts per table and that nothing else changed when the transcript says so. */
    static void assertDbDelta(JsonNode delta, Map<String, List<Map<String, Object>>> before,
            Map<String, List<Map<String, Object>>> after) {
        for (String table : TABLE_ORDER.keySet()) {
            JsonNode d = delta.path(table);
            List<Map<String, Object>> inserted = new ArrayList<>(after.get(table));
            inserted.removeAll(before.get(table));
            List<Map<String, Object>> deleted = new ArrayList<>(before.get(table));
            deleted.removeAll(after.get(table));
            int updates = d.path("updated").size();
            assertThat(inserted.size()).as("%s inserted/updated rows", table)
                    .isEqualTo(d.path("inserted").size() + updates);
            assertThat(deleted.size()).as("%s deleted/updated rows", table)
                    .isEqualTo(d.path("deleted").size() + updates);
        }
    }
}
