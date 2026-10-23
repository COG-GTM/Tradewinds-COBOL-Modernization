package com.ibm.cics.cip.bank.core.updacc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Replays every {@code baseline/transcripts/UPDACC/*.json} legacy transcript (recorded from UPDACC under the
 * GnuCOBOL harness) from the canonical seed with the fixed clock/seed and asserts identical COMMAREA output and
 * DB deltas (PROCTRAN compared without PROCTRAN_REF).
 *
 * <p>{@link #serviceMatchesLegacy} drives {@link UpdateAccountService} with the COMMAREA (the UPDACC port);
 * {@link #restContractOnLegacyCases} drives {@code PUT /banking/account/{id}} with the same inputs, where the
 * REST contract additionally rejects account types outside ISA/MORTGAGE/LOAN/SAVING/CURRENT (legacy UPDACC
 * accepts any non-blank type; see baseline/README.md defect 9).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "cbsa.clock.fixed=2026-01-15T10:30:00Z",
        "cbsa.random.seed=42",
        "spring.datasource.url=jdbc:h2:mem:cbsa-updacc;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"})
@AutoConfigureMockMvc
class UpdaccTranscriptReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path TRANSCRIPTS = UpdaccTestDatabase.BASELINE.resolve("transcripts/UPDACC");

    @Autowired
    UpdateAccountService service;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void resetToCanonicalSeed() {
        UpdaccTestDatabase.resetToSeed(jdbc, dataSource);
    }

    static Stream<Path> transcripts() throws IOException {
        try (Stream<Path> files = Files.list(TRANSCRIPTS)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList().stream();
        }
    }

    @Test
    void everyRecordedCaseIsReplayed() throws IOException {
        assertThat(transcripts().count()).isEqualTo(5);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transcripts")
    void serviceMatchesLegacy(Path file) throws IOException {
        JsonNode t = JSON.readTree(file.toFile());
        assertThat(t.path("program").asText()).isEqualTo("UPDACC");
        JsonNode in = t.path("commarea_in");
        JsonNode out = t.path("commarea_out");
        long accno = in.path("COMM-ACCNO").asLong();
        boolean exists = accountExists(accno);

        Map<String, List<Map<String, Object>>> before = UpdaccTestDatabase.snapshot(jdbc);
        if ("Y".equals(out.path("COMM-SUCCESS").asText())) {
            Account a = service.updateAccount(accno, in.path("COMM-ACC-TYPE").asText(),
                    in.path("COMM-INT-RATE").decimalValue(), in.path("COMM-OVERDRAFT").asInt());
            assertThat(toCommarea(a)).isEqualTo(expectedCommarea(out));
        } else {
            CbsaException e = catchThrowableOfType(() -> service.updateAccount(accno,
                    in.path("COMM-ACC-TYPE").asText(), in.path("COMM-INT-RATE").decimalValue(),
                    in.path("COMM-OVERDRAFT").asInt()), CbsaException.class);
            assertThat(e).as("legacy COMM-SUCCESS = N").isNotNull();
            assertThat(e.status()).isEqualTo(exists ? HttpStatus.BAD_REQUEST : HttpStatus.NOT_FOUND);
        }
        assertThat(UpdaccTestDatabase.delta(before, UpdaccTestDatabase.snapshot(jdbc)))
                .isEqualTo(expectedDelta(t.path("db_delta")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transcripts")
    void restContractOnLegacyCases(Path file) throws Exception {
        JsonNode t = JSON.readTree(file.toFile());
        JsonNode in = t.path("commarea_in");
        JsonNode out = t.path("commarea_out");
        long accno = in.path("COMM-ACCNO").asLong();
        String type = in.path("COMM-ACC-TYPE").asText();
        boolean legacyOk = "Y".equals(out.path("COMM-SUCCESS").asText());
        boolean typeSupported = UpdateAccountController.VALID_ACCOUNT_TYPES.contains(type.trim());

        ObjectNode body = JSON.createObjectNode();
        body.put("id", CbsaFormats.accountNumber(accno));
        body.put("sortCode", in.path("COMM-SCODE").asText());
        body.put("accountType", type);
        body.set("interestRate", in.path("COMM-INT-RATE"));
        body.set("overdraft", in.path("COMM-OVERDRAFT"));
        body.set("availableBalance", in.path("COMM-AVAIL-BAL"));
        body.set("actualBalance", in.path("COMM-ACTUAL-BAL"));

        Map<String, List<Map<String, Object>>> before = UpdaccTestDatabase.snapshot(jdbc);
        MvcResult r = mvc.perform(put("/banking/account/{id}", accno)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();
        int status = r.getResponse().getStatus();
        Map<String, Map<String, List<Object>>> delta =
                UpdaccTestDatabase.delta(before, UpdaccTestDatabase.snapshot(jdbc));

        if (legacyOk && typeSupported) {
            assertThat(status).isEqualTo(200);
            JsonNode json = JSON.readTree(r.getResponse().getContentAsString());
            Map<String, Object> got = new TreeMap<>();
            got.put("COMM-CUSTNO", json.path("customerNumber").asText());
            got.put("COMM-SCODE", json.path("sortCode").asText());
            got.put("COMM-ACCNO", json.path("id").asLong());
            got.put("COMM-ACC-TYPE", json.path("accountType").asText());
            got.put("COMM-INT-RATE", UpdaccTestDatabase.normalise(json.path("interestRate").decimalValue()));
            got.put("COMM-OPENED", ddmmyyyy(LocalDate.parse(json.path("dateOpened").asText())));
            got.put("COMM-OVERDRAFT", json.path("overdraft").asInt());
            got.put("COMM-LAST-STMT-DT", ddmmyyyy(LocalDate.parse(json.path("lastStatementDate").asText())));
            got.put("COMM-NEXT-STMT-DT", ddmmyyyy(LocalDate.parse(json.path("nextStatementDate").asText())));
            got.put("COMM-AVAIL-BAL", UpdaccTestDatabase.normalise(json.path("availableBalance").decimalValue()));
            got.put("COMM-ACTUAL-BAL", UpdaccTestDatabase.normalise(json.path("actualBalance").decimalValue()));
            Map<String, Object> expected = expectedCommarea(out);
            expected.remove("COMM-EYE");
            assertThat(got).isEqualTo(expected);
            assertThat(delta).isEqualTo(expectedDelta(t.path("db_delta")));
        } else if (legacyOk) {
            // Contract deviation (AC: invalid type -> 400): legacy wrote the unsupported type, REST rejects it.
            assertThat(status).isEqualTo(400);
            assertThat(r.getResponse().getContentAsString()).contains("is not supported");
            assertThat(delta).isEqualTo(emptyDelta());
        } else {
            assertThat(status).isEqualTo(accountExists(accno) ? 400 : 404);
            assertThat(delta).isEqualTo(expectedDelta(t.path("db_delta")));
        }
    }

    private boolean accountExists(long accno) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT WHERE ACCOUNT_SORTCODE = '987654' "
                + "AND ACCOUNT_NUMBER = ?", Long.class, CbsaFormats.accountNumber(accno));
        return n != null && n > 0;
    }

    /** UPDACC COMMAREA out (copybook UPDACC.cpy) built from the returned Account. */
    private static Map<String, Object> toCommarea(Account a) {
        Map<String, Object> m = new TreeMap<>();
        m.put("COMM-EYE", a.eyecatcher());
        m.put("COMM-CUSTNO", CbsaFormats.customerNumber(a.customerNumber()));
        m.put("COMM-SCODE", a.sortCode());
        m.put("COMM-ACCNO", a.accountNumber());
        m.put("COMM-ACC-TYPE", a.accountType());
        m.put("COMM-INT-RATE", UpdaccTestDatabase.normalise(a.interestRate()));
        m.put("COMM-OPENED", ddmmyyyy(a.opened()));
        m.put("COMM-OVERDRAFT", a.overdraftLimit());
        m.put("COMM-LAST-STMT-DT", ddmmyyyy(a.lastStatement()));
        m.put("COMM-NEXT-STMT-DT", ddmmyyyy(a.nextStatement()));
        m.put("COMM-AVAIL-BAL", UpdaccTestDatabase.normalise(a.availableBalance()));
        m.put("COMM-ACTUAL-BAL", UpdaccTestDatabase.normalise(a.actualBalance()));
        return m;
    }

    private static Map<String, Object> expectedCommarea(JsonNode out) {
        Map<String, Object> m = new TreeMap<>();
        m.put("COMM-EYE", out.path("COMM-EYE").asText());
        m.put("COMM-CUSTNO", out.path("COMM-CUSTNO").asText());
        m.put("COMM-SCODE", out.path("COMM-SCODE").asText());
        m.put("COMM-ACCNO", out.path("COMM-ACCNO").asLong());
        m.put("COMM-ACC-TYPE", out.path("COMM-ACC-TYPE").asText().stripTrailing());
        m.put("COMM-INT-RATE", UpdaccTestDatabase.normalise(out.path("COMM-INT-RATE").decimalValue()));
        m.put("COMM-OPENED", out.path("COMM-OPENED").asInt());
        m.put("COMM-OVERDRAFT", out.path("COMM-OVERDRAFT").asInt());
        m.put("COMM-LAST-STMT-DT", out.path("COMM-LAST-STMT-DT").asInt());
        m.put("COMM-NEXT-STMT-DT", out.path("COMM-NEXT-STMT-DT").asInt());
        m.put("COMM-AVAIL-BAL", UpdaccTestDatabase.normalise(out.path("COMM-AVAIL-BAL").decimalValue()));
        m.put("COMM-ACTUAL-BAL", UpdaccTestDatabase.normalise(out.path("COMM-ACTUAL-BAL").decimalValue()));
        return m;
    }

    /** Numeric DDMMYYYY as held in PIC 9(8) COMM dates. */
    private static int ddmmyyyy(LocalDate d) {
        return Integer.parseInt(CbsaFormats.ddmmyyyy(d));
    }

    private static Map<String, Map<String, List<Object>>> expectedDelta(JsonNode dbDelta) {
        Map<String, Map<String, List<Object>>> result = new LinkedHashMap<>();
        for (String table : UpdaccTestDatabase.KEYS.keySet()) {
            JsonNode d = dbDelta.path(table);
            Map<String, List<Object>> m = new LinkedHashMap<>();
            m.put("inserted", rows(d.path("inserted"), table));
            List<Object> updated = new ArrayList<>();
            for (JsonNode u : d.path("updated")) {
                Map<String, Object> upd = new LinkedHashMap<>();
                upd.put("key", normalisedObject(u.path("key")));
                Map<String, Object> changes = new TreeMap<>();
                Iterator<Map.Entry<String, JsonNode>> it = u.path("changes").fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> c = it.next();
                    changes.put(c.getKey(), List.of(String.valueOf(value(c.getValue().get(0))),
                            String.valueOf(value(c.getValue().get(1)))));
                }
                upd.put("changes", changes);
                updated.add(upd);
            }
            m.put("updated", updated);
            m.put("deleted", rows(d.path("deleted"), table));
            result.put(table, m);
        }
        return result;
    }

    private static Map<String, Map<String, List<Object>>> emptyDelta() {
        return expectedDelta(JSON.createObjectNode());
    }

    private static List<Object> rows(JsonNode array, String table) {
        List<Object> rows = new ArrayList<>();
        for (JsonNode row : array) {
            Map<String, Object> m = normalisedObject(row);
            if ("PROCTRAN".equals(table)) {
                m.remove("PROCTRAN_REF");
            }
            rows.add(m);
        }
        return rows;
    }

    private static Map<String, Object> normalisedObject(JsonNode obj) {
        Map<String, Object> m = new TreeMap<>();
        obj.fields().forEachRemaining(e -> m.put(e.getKey(), value(e.getValue())));
        return m;
    }

    private static Object value(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return UpdaccTestDatabase.normalise(new BigDecimal(v.asText()));
        }
        return UpdaccTestDatabase.normalise(v.asText());
    }
}
