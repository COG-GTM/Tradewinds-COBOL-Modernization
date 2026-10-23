package com.ibm.cics.cip.bank.core.crecust;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cics.cip.bank.core.CbsaCoreApplication;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.proctran.TaskNumberSupplier;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Replays every {@code baseline/transcripts/<PROGRAM>/*.json} for CRECUST and CRDTAGY1-5 against the Java service:
 * a fresh application context per case (own H2 database loaded from {@code baseline/seed/seed.sql}, fixed clock
 * and random seed from the case's {@code env}, EIBTASKN fast-forwarded to {@code CBSA_TASKN}), a real HTTP
 * {@code POST /webui-1.0/banking/customer}, then a comparison of
 * <ul>
 * <li>{@code commarea_out} with the HTTP outcome (status, fail code, customer number, score, review date);</li>
 * <li>the credit check itself (score, review date and every agency's delay = the {@code CHILD_DONE} trace) for all
 * cases where the legacy credit check ran, including those that later fail the date-of-birth check;</li>
 * <li>{@code db_delta} for CUSTOMER, ACCOUNT, PROCTRAN (excluding PROCTRAN_REF) and CONTROL.</li>
 * </ul>
 */
class CrecustTranscriptReplayTest {

    static final Path REPO_ROOT = Path.of("../..").toAbsolutePath().normalize();
    static final Path TRANSCRIPTS = REPO_ROOT.resolve("baseline/transcripts");
    static final Path SEED = REPO_ROOT.resolve("baseline/seed/seed.sql");
    static final List<String> PROGRAMS = List.of("CRECUST", "CRDTAGY1", "CRDTAGY2", "CRDTAGY3", "CRDTAGY4", "CRDTAGY5");

    static final Map<String, List<String>> KEYS = Map.of(
            "CUSTOMER", List.of("CUSTOMER_SORTCODE", "CUSTOMER_NUMBER"),
            "ACCOUNT", List.of("ACCOUNT_SORTCODE", "ACCOUNT_NUMBER"),
            "CONTROL", List.of("CONTROL_NAME"),
            "PROCTRAN", List.of());
    static final Set<String> IGNORED_COLUMNS = Set.of("PROCTRAN_REF");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static int replayed;
    private static int matched;

    @TestFactory
    Stream<DynamicTest> replayBaselineTranscripts() throws IOException {
        List<Path> cases = new ArrayList<>();
        for (String program : PROGRAMS) {
            Path dir = TRANSCRIPTS.resolve(program);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    files.filter(p -> p.toString().endsWith(".json")).sorted().forEach(cases::add);
                }
            }
        }
        assertThat(cases).as("CRECUST transcripts under %s", TRANSCRIPTS).isNotEmpty();
        return cases.stream().map(p -> DynamicTest.dynamicTest(
                p.getParent().getFileName() + "/" + p.getFileName(), () -> replay(p)));
    }

    @AfterAll
    static void report() {
        System.out.printf("CRECUST transcript replay: %d/%d matching%n", matched, replayed);
    }

    private void replay(Path file) throws Exception {
        replayed++;
        JsonNode t = JSON.readTree(file.toFile());
        JsonNode env = t.path("env");
        String caseName = t.path("case").asText().replaceAll("[^A-Za-z0-9]", "_");
        long seed = Long.parseLong(env.path("CBSA_RANDOM_SEED").asText("0"));
        long taskn = Long.parseLong(env.path("CBSA_TASKN").asText("100"));

        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(CbsaCoreApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=jdbc:h2:mem:crecust_" + caseName
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                        "--cbsa.seed.location=file:" + SEED,
                        "--cbsa.seed.mode=always",
                        "--cbsa.clock.fixed=" + env.path("CBSA_FIXED_TIME").asText("2026-01-15T10:30:00Z"),
                        "--cbsa.random.seed=" + seed,
                        "--cbsa.credit.simulate-delay=false",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN")) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            for (String setup : iterable(t.path("setup_sql"))) {
                jdbc.execute(setup);
            }
            fastForwardTaskNumber(ctx.getBean(TaskNumberSupplier.class), taskn);

            JsonNode in = t.path("commarea_in");
            JsonNode out = t.path("commarea_out");
            assertCreditCheck(ctx.getBean(CreditCheckService.class), in, out, t.path("trace"), taskn);

            Map<String, List<Map<String, String>>> before = snapshot(jdbc);
            HttpResponse<String> response = post(ctx, in);
            Map<String, List<Map<String, String>>> after = snapshot(jdbc);

            assertCommareaOut(out, response);
            assertThat(delta(before, after)).as("db_delta").isEqualTo(expectedDelta(t.path("db_delta")));
            assertThat(t.path("abend").isNull() || t.path("abend").isMissingNode()).as("legacy abend").isTrue();
        }
        matched++;
    }

    /** EIBTASKN of the CRECUST task = CBSA_TASKN: consume task numbers until the next one is {@code taskn}. */
    private static void fastForwardTaskNumber(TaskNumberSupplier supplier, long taskn) {
        long n = 0;
        while (n < taskn - 1) {
            n = supplier.nextTaskNumber();
        }
    }

    private static void assertCreditCheck(CreditCheckService service, JsonNode in, JsonNode out, JsonNode trace,
            long taskn) {
        List<JsonNode> childDone = new ArrayList<>();
        for (JsonNode e : trace) {
            if ("CHILD_DONE".equals(e.path(0).asText())) {
                childDone.add(e);
            }
        }
        if (childDone.isEmpty()) {
            return; // legacy credit check did not run (title failure)
        }
        Customer applicant = new Customer("CUST", "987654", 0, in.path("COMM-NAME").asText(),
                in.path("COMM-ADDRESS").asText(), null, 0, null);
        CreditCheckResult r = service.check(applicant, taskn);
        assertThat(r.ok()).isTrue();
        assertThat(r.score()).as("COMM-CREDIT-SCORE").isEqualTo(out.path("COMM-CREDIT-SCORE").asInt());
        assertThat(r.reviewDate()).as("COMM-CS-REVIEW-DATE").isEqualTo(ddmmyyyy(out.path("COMM-CS-REVIEW-DATE").asLong()));
        assertThat(r.answers()).hasSize(childDone.size());
        for (int i = 0; i < childDone.size(); i++) {
            AgencyAnswer a = r.answers().get(i);
            assertThat(a.programName()).isEqualTo(childDone.get(i).path(2).asText());
            assertThat(a.delaySeconds()).as("%s delay", a.programName())
                    .isEqualTo((int) Double.parseDouble(childDone.get(i).path(3).asText()));
        }
    }

    private static HttpResponse<String> post(ConfigurableApplicationContext ctx, JsonNode in) throws Exception {
        String port = ctx.getEnvironment().getProperty("local.server.port");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", in.path("COMM-NAME").asText());
        body.put("customerAddress", in.path("COMM-ADDRESS").asText());
        String dob = String.format("%08d", in.path("COMM-DATE-OF-BIRTH").asLong());
        body.put("dateOfBirth", dob.substring(4) + "-" + dob.substring(2, 4) + "-" + dob.substring(0, 2));
        body.put("sortCode", String.format("%06d", in.path("COMM-KEY").path("COMM-SORTCODE").asLong()));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webui-1.0/banking/customer"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertCommareaOut(JsonNode out, HttpResponse<String> response) throws IOException {
        JsonNode body = JSON.readTree(response.body());
        if ("Y".equals(out.path("COMM-SUCCESS").asText())) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            assertThat(body.path("id").asText())
                    .isEqualTo(String.format("%010d", out.path("COMM-KEY").path("COMM-NUMBER").asLong()));
            assertThat(body.path("sortCode").asText())
                    .isEqualTo(String.format("%06d", out.path("COMM-KEY").path("COMM-SORTCODE").asLong()));
            assertThat(body.path("customerName").asText()).isEqualTo(out.path("COMM-NAME").asText().stripTrailing());
            assertThat(body.path("customerAddress").asText()).isEqualTo(out.path("COMM-ADDRESS").asText().stripTrailing());
            assertThat(LocalDate.parse(body.path("dateOfBirth").asText()))
                    .isEqualTo(ddmmyyyy(out.path("COMM-DATE-OF-BIRTH").asLong()));
            assertThat(body.path("customerCreditScore").asText()).isEqualTo(out.path("COMM-CREDIT-SCORE").asText());
            assertThat(LocalDate.parse(body.path("customerCreditScoreReviewDate").asText()))
                    .isEqualTo(ddmmyyyy(out.path("COMM-CS-REVIEW-DATE").asLong()));
        } else {
            String failCode = out.path("COMM-FAIL-CODE").asText();
            int expectedStatus = Set.of("T", "O", "Y", "Z").contains(failCode) ? 400 : 500;
            assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
            assertThat(body.path("failCode").asText()).isEqualTo(failCode);
        }
    }

    private static LocalDate ddmmyyyy(long value) {
        String s = String.format("%08d", value);
        return LocalDate.of(Integer.parseInt(s.substring(4)), Integer.parseInt(s.substring(2, 4)),
                Integer.parseInt(s.substring(0, 2)));
    }

    private static Map<String, List<Map<String, String>>> snapshot(JdbcTemplate jdbc) {
        Map<String, List<Map<String, String>>> tables = new TreeMap<>();
        for (String table : KEYS.keySet()) {
            List<Map<String, String>> rows = new ArrayList<>();
            for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM " + table)) {
                Map<String, String> normalized = new TreeMap<>();
                row.forEach((k, v) -> normalized.put(k.toUpperCase(), normalize(v)));
                rows.add(normalized);
            }
            tables.put(table, rows);
        }
        return tables;
    }

    /** Canonical, order-independent form: table -> sorted list of "inserted|..", "deleted|..", "updated|..". */
    private static Map<String, List<String>> delta(Map<String, List<Map<String, String>>> before,
            Map<String, List<Map<String, String>>> after) {
        Map<String, List<String>> result = new TreeMap<>();
        for (String table : KEYS.keySet()) {
            List<String> changes = new ArrayList<>();
            List<String> keys = KEYS.get(table);
            if (keys.isEmpty()) {
                List<String> b = before.get(table).stream().map(r -> rowString(r)).toList();
                List<String> remaining = new ArrayList<>(b);
                for (Map<String, String> r : after.get(table)) {
                    String s = rowString(r);
                    if (!remaining.remove(s)) {
                        changes.add("inserted|" + s);
                    }
                }
                remaining.forEach(s -> changes.add("deleted|" + s));
            } else {
                Map<String, Map<String, String>> b = byKey(before.get(table), keys);
                Map<String, Map<String, String>> a = byKey(after.get(table), keys);
                for (var e : a.entrySet()) {
                    Map<String, String> old = b.get(e.getKey());
                    if (old == null) {
                        changes.add("inserted|" + rowString(e.getValue()));
                    } else if (!old.equals(e.getValue())) {
                        Map<String, String> diff = new TreeMap<>();
                        e.getValue().forEach((col, v) -> {
                            if (!IGNORED_COLUMNS.contains(col) && !v.equals(old.get(col))) {
                                diff.put(col, old.get(col) + "->" + v);
                            }
                        });
                        changes.add("updated|" + e.getKey() + "|" + diff);
                    }
                }
                b.keySet().stream().filter(k -> !a.containsKey(k)).forEach(k -> changes.add("deleted|" + rowString(b.get(k))));
            }
            changes.sort(null);
            result.put(table, changes);
        }
        return result;
    }

    private static Map<String, List<String>> expectedDelta(JsonNode dbDelta) {
        Map<String, List<String>> result = new TreeMap<>();
        for (String table : KEYS.keySet()) {
            JsonNode d = dbDelta.path(table);
            List<String> changes = new ArrayList<>();
            for (JsonNode row : d.path("inserted")) {
                changes.add("inserted|" + rowString(jsonRow(row)));
            }
            for (JsonNode row : d.path("deleted")) {
                changes.add("deleted|" + rowString(jsonRow(row)));
            }
            for (JsonNode u : d.path("updated")) {
                Map<String, String> key = jsonRow(u.path("key"));
                StringBuilder k = new StringBuilder();
                for (String col : KEYS.get(table)) {
                    k.append(k.isEmpty() ? "" : "/").append(key.get(col));
                }
                Map<String, String> diff = new TreeMap<>();
                u.path("changes").fields().forEachRemaining(c -> {
                    if (!IGNORED_COLUMNS.contains(c.getKey())) {
                        diff.put(c.getKey(), normalize(c.getValue().get(0)) + "->" + normalize(c.getValue().get(1)));
                    }
                });
                changes.add("updated|" + k + "|" + diff);
            }
            changes.sort(null);
            result.put(table, changes);
        }
        return result;
    }

    private static Map<String, Map<String, String>> byKey(List<Map<String, String>> rows, List<String> keys) {
        Map<String, Map<String, String>> m = new TreeMap<>();
        for (Map<String, String> r : rows) {
            StringBuilder k = new StringBuilder();
            for (String col : keys) {
                k.append(k.isEmpty() ? "" : "/").append(r.get(col));
            }
            m.put(k.toString(), r);
        }
        return m;
    }

    private static Map<String, String> jsonRow(JsonNode row) {
        Map<String, String> m = new TreeMap<>();
        row.fields().forEachRemaining(e -> m.put(e.getKey().toUpperCase(), normalize(e.getValue())));
        return m;
    }

    private static String rowString(Map<String, String> row) {
        Map<String, String> m = new TreeMap<>(row);
        IGNORED_COLUMNS.forEach(m::remove);
        return m.toString();
    }

    private static String normalize(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof JsonNode n) {
            if (n.isNull()) {
                return "null";
            }
            if (n.isNumber()) {
                return n.decimalValue().stripTrailingZeros().toPlainString();
            }
            return n.asText().stripTrailing();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof LocalDate d) {
            return d.toString();
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        return v.toString().stripTrailing();
    }

    private static Iterable<String> iterable(JsonNode array) {
        List<String> list = new ArrayList<>();
        for (Iterator<JsonNode> it = array.elements(); it.hasNext();) {
            list.add(it.next().asText());
        }
        return list;
    }
}
