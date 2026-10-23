package com.ibm.cics.cip.bank.core.xfrfun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Replays every baseline/transcripts/XFRFUN/*.json (recorded from the legacy COBOL under GnuCOBOL) against
 * {@code PUT /banking/account/transfer/{id}} from the canonical seed with the transcript's fixed clock and
 * seed, and asserts the same outcome (success / fail code / abend mapped to HTTP), the same balances and the
 * same ACCOUNT and PROCTRAN deltas (PROCTRAN_REF excluded: EIBTASKN is not reproducible).
 */
@SpringBootTest(properties = {XfrfunTestSupport.FIXED_TIME, XfrfunTestSupport.RANDOM_SEED,
        XfrfunTestSupport.PRIVATE_DB})
@AutoConfigureMockMvc
class XfrfunTranscriptReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    static Path transcriptDir() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("baseline/transcripts/XFRFUN");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("baseline/transcripts/XFRFUN not found above " + Paths.get("").toAbsolutePath());
    }

    static Stream<String> transcripts() throws IOException {
        try (Stream<Path> files = Files.list(transcriptDir())) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted().toList()
                    .stream();
        }
    }

    @Test
    void everyBaselineCaseIsReplayed() throws IOException {
        assertThat(transcripts().toList()).hasSize(8).contains("ok.json", "to-not-found.json", "same-account.json");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transcripts")
    void replay(String file) throws Exception {
        JsonNode t = JSON.readTree(transcriptDir().resolve(file).toFile());
        assertThat(t.path("program").asText()).isEqualTo("XFRFUN");
        assertThat(t.path("env").path("CBSA_FIXED_TIME").asText()).isEqualTo("2026-01-15T10:30:00Z");
        JsonNode in = t.path("commarea_in");
        JsonNode out = t.path("commarea_out");
        JsonNode delta = t.path("db_delta");

        // Start from the transcript's "before" values for every row it changes (the canonical seed).
        for (JsonNode upd : delta.path("ACCOUNT").path("updated")) {
            JsonNode ch = upd.path("changes");
            jdbc.update("UPDATE ACCOUNT SET ACCOUNT_AVAILABLE_BALANCE = ?, ACCOUNT_ACTUAL_BALANCE = ? "
                    + "WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?",
                    ch.path("ACCOUNT_AVAILABLE_BALANCE").get(0).decimalValue(),
                    ch.path("ACCOUNT_ACTUAL_BALANCE").get(0).decimalValue(),
                    upd.path("key").path("ACCOUNT_SORTCODE").asText(), upd.path("key").path("ACCOUNT_NUMBER").asText());
        }
        XfrfunTestSupport.Snapshot before = XfrfunTestSupport.snapshot(jdbc);
        List<Map<String, Object>> customersBefore = jdbc.queryForList("SELECT * FROM CUSTOMER");
        List<Map<String, Object>> controlBefore = jdbc.queryForList("SELECT * FROM CONTROL");
        try {
            String body = "{\"targetAccount\": " + in.path("COMM-TACCNO").asLong() + ", \"amount\": "
                    + in.path("COMM-AMT").decimalValue().toPlainString() + "}";
            MockHttpServletResponse resp = mvc.perform(put("/banking/account/transfer/" + in.path("COMM-FACCNO").asLong())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
            JsonNode r = JSON.readTree(resp.getContentAsString());

            assertOutcome(t, out, resp.getStatus(), r);
            assertAccountDelta(delta.path("ACCOUNT"), before);
            assertProctranDelta(delta.path("PROCTRAN"), before);
            assertThat(delta.path("CUSTOMER").path("inserted")).isEmpty();
            assertThat(jdbc.queryForList("SELECT * FROM CUSTOMER")).isEqualTo(customersBefore);
            assertThat(delta.path("CONTROL").path("updated")).isEmpty();
            assertThat(jdbc.queryForList("SELECT * FROM CONTROL")).isEqualTo(controlBefore);
        } finally {
            XfrfunTestSupport.restore(jdbc, before);
        }
    }

    private static void assertOutcome(JsonNode t, JsonNode out, int status, JsonNode r) {
        if (!t.path("abend").isNull() && !t.path("abend").isMissingNode()) {
            assertThat(t.path("abend").path("code").asText()).isEqualTo("SAME");
            assertThat(status).isEqualTo(400);
            assertThat(r.path("abendCode").asText()).isEqualTo("SAME");
            assertThat(r.path("errorMessage").asText()).isEqualTo("Cannot transfer to the same account");
            return;
        }
        if ("Y".equals(out.path("COMM-SUCCESS").asText())) {
            assertThat(status).isEqualTo(200);
            assertThat(r.path("fromAccount").path("id").asText())
                    .isEqualTo(String.format("%08d", out.path("COMM-FACCNO").asLong()));
            assertThat(r.path("toAccount").path("id").asText())
                    .isEqualTo(String.format("%08d", out.path("COMM-TACCNO").asLong()));
            assertMoney(r.path("fromAccount").path("availableBalance"), out.path("COMM-FAVBAL"));
            assertMoney(r.path("fromAccount").path("actualBalance"), out.path("COMM-FACTBAL"));
            assertMoney(r.path("toAccount").path("availableBalance"), out.path("COMM-TAVBAL"));
            assertMoney(r.path("toAccount").path("actualBalance"), out.path("COMM-TACTBAL"));
            assertMoney(r.path("amount"), out.path("COMM-AMT"));
            return;
        }
        String failCode = out.path("COMM-FAIL-CODE").asText();
        int expectedStatus = switch (failCode) {
            case "4" -> 400;
            case "1", "2" -> 404;
            default -> 500;
        };
        assertThat(status).isEqualTo(expectedStatus);
        assertThat(r.path("failCode").asText()).isEqualTo(failCode);
    }

    private void assertAccountDelta(JsonNode expected, XfrfunTestSupport.Snapshot before) {
        Map<String, BigDecimal[]> after = XfrfunTestSupport.snapshot(jdbc).balances();
        assertThat(after.keySet()).isEqualTo(before.balances().keySet());
        Map<String, BigDecimal[][]> actualChanges = new HashMap<>();
        before.balances().forEach((acc, old) -> {
            BigDecimal[] now = after.get(acc);
            if (old[0].compareTo(now[0]) != 0 || old[1].compareTo(now[1]) != 0) {
                actualChanges.put(acc, new BigDecimal[][] {old, now});
            }
        });
        assertThat(actualChanges).hasSize(expected.path("updated").size());
        for (JsonNode upd : expected.path("updated")) {
            String acc = upd.path("key").path("ACCOUNT_NUMBER").asText();
            BigDecimal[][] change = actualChanges.get(acc);
            assertThat(change).as("account %s changed", acc).isNotNull();
            JsonNode ch = upd.path("changes");
            assertThat(ch.size()).as("only balance columns change").isEqualTo(2);
            assertThat(change[0][0]).isEqualByComparingTo(ch.path("ACCOUNT_AVAILABLE_BALANCE").get(0).decimalValue());
            assertThat(change[1][0]).isEqualByComparingTo(ch.path("ACCOUNT_AVAILABLE_BALANCE").get(1).decimalValue());
            assertThat(change[0][1]).isEqualByComparingTo(ch.path("ACCOUNT_ACTUAL_BALANCE").get(0).decimalValue());
            assertThat(change[1][1]).isEqualByComparingTo(ch.path("ACCOUNT_ACTUAL_BALANCE").get(1).decimalValue());
        }
        assertThat(expected.path("inserted")).isEmpty();
        assertThat(expected.path("deleted")).isEmpty();
    }

    private void assertProctranDelta(JsonNode expected, XfrfunTestSupport.Snapshot before) {
        List<Map<String, Object>> added =
                XfrfunTestSupport.inserted(before.proctran(), XfrfunTestSupport.proctranRows(jdbc));
        assertThat(added).hasSize(expected.path("inserted").size());
        for (int i = 0; i < added.size(); i++) {
            Map<String, Object> row = added.get(i);
            JsonNode exp = expected.path("inserted").get(i);
            for (String col : new String[] {"PROCTRAN_EYECATCHER", "PROCTRAN_SORTCODE", "PROCTRAN_NUMBER",
                    "PROCTRAN_DATE", "PROCTRAN_TIME", "PROCTRAN_TYPE", "PROCTRAN_DESC"}) {
                assertThat(XfrfunTestSupport.rtrim(row.get(col))).as(col)
                        .isEqualTo(XfrfunTestSupport.rtrim(exp.path(col).asText()));
            }
            assertThat((BigDecimal) row.get("PROCTRAN_AMOUNT"))
                    .isEqualByComparingTo(exp.path("PROCTRAN_AMOUNT").decimalValue());
        }
    }

    private static void assertMoney(JsonNode actual, JsonNode expected) {
        assertThat(actual.decimalValue()).isEqualByComparingTo(expected.decimalValue());
    }
}
