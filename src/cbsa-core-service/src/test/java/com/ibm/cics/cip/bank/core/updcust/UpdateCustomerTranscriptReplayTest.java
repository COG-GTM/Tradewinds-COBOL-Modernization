package com.ibm.cics.cip.bank.core.updcust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Replays every baseline/transcripts/UPDCUST/*.json (recorded from the legacy COBOL under GnuCOBOL)
 * against the Java port, from the canonical seed with the transcript's fixed clock / random seed, and
 * asserts the COMMAREA outputs and the DB delta (PROCTRAN excluding PROCTRAN_REF) are identical.
 * Each case is replayed twice: directly against {@link UpdateCustomerService} (COMMAREA semantics) and
 * through {@code PUT /banking/customer/{id}} (HTTP contract).
 */
@SpringBootTest(properties = {
        "cbsa.clock.fixed=2026-01-15T10:30:00Z",
        "cbsa.random.seed=42"})
@AutoConfigureMockMvc
class UpdateCustomerTranscriptReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DIR = Path.of(DbDelta.TRANSCRIPTS, "UPDCUST");

    @Autowired
    UpdateCustomerService service;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    static Stream<Path> transcripts() throws IOException {
        try (Stream<Path> s = Files.list(DIR)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList().stream();
        }
    }

    @BeforeEach
    @AfterEach
    void seed() {
        DbDelta.resetToSeed(jdbc, dataSource);
    }

    @Test
    void everyRecordedCaseIsReplayed() throws IOException {
        assertThat(transcripts()).hasSize(6);
    }

    @ParameterizedTest(name = "service {0}")
    @MethodSource("transcripts")
    void serviceMatchesLegacy(Path file) throws Exception {
        JsonNode t = JSON.readTree(file.toFile());
        assertEnv(t);
        JsonNode in = t.path("commarea_in");
        JsonNode out = t.path("commarea_out");
        var before = DbDelta.snapshot(jdbc);

        long custno = Long.parseLong(in.path("COMM-CUSTNO").asText());
        String name = in.path("COMM-NAME").asText();
        String addr = in.path("COMM-ADDR").asText();

        if ("Y".equals(out.path("COMM-UPD-SUCCESS").asText())) {
            Customer c = service.updateCustomer(custno, name, addr);
            assertThat(c.eyecatcher()).isEqualTo(out.path("COMM-EYE").asText());
            assertThat(c.sortCode()).isEqualTo(out.path("COMM-SCODE").asText());
            assertThat(CbsaFormats.customerNumber(c.customerNumber())).isEqualTo(out.path("COMM-CUSTNO").asText());
            assertThat(c.name()).isEqualTo(out.path("COMM-NAME").asText());
            assertThat(c.address()).isEqualTo(out.path("COMM-ADDR").asText());
            assertThat(Integer.parseInt(CbsaFormats.ddmmyyyy(c.dateOfBirth()))).isEqualTo(out.path("COMM-DOB").asInt());
            assertThat(c.creditScore()).isEqualTo(out.path("COMM-CREDIT-SCORE").asInt());
            assertThat(Integer.parseInt(CbsaFormats.ddmmyyyy(c.creditScoreReviewDate())))
                    .isEqualTo(out.path("COMM-CS-REVIEW-DATE").asInt());
        } else {
            CbsaException e = catchThrowableOfType(() -> service.updateCustomer(custno, name, addr), CbsaException.class);
            assertThat(e).as("expected fail code %s", out.path("COMM-UPD-FAIL-CD").asText()).isNotNull();
            assertThat(e.failCode()).isEqualTo(out.path("COMM-UPD-FAIL-CD").asText());
        }
        assertThat(t.path("abend").isNull()).isTrue();
        assertThat(DbDelta.diff(before, DbDelta.snapshot(jdbc))).isEqualTo(DbDelta.expected(t.path("db_delta")));
    }

    @ParameterizedTest(name = "http {0}")
    @MethodSource("transcripts")
    void httpMatchesLegacy(Path file) throws Exception {
        JsonNode t = JSON.readTree(file.toFile());
        JsonNode in = t.path("commarea_in");
        JsonNode out = t.path("commarea_out");
        var before = DbDelta.snapshot(jdbc);

        ObjectNode body = JSON.createObjectNode();
        body.put("customerName", in.path("COMM-NAME").asText());
        body.put("customerAddress", in.path("COMM-ADDR").asText());
        body.put("sortCode", in.path("COMM-SCODE").asText());
        if (in.has("COMM-DOB")) {
            body.put("dateOfBirth", LocalDate.parse(String.format("%08d", in.path("COMM-DOB").asInt()),
                    CbsaFormats.DDMMYYYY).toString());
        }
        if (in.has("COMM-CREDIT-SCORE")) {
            body.put("creditScore", in.path("COMM-CREDIT-SCORE").asText());
        }
        long custno = Long.parseLong(in.path("COMM-CUSTNO").asText());

        MockHttpServletResponse r = mvc.perform(put("/banking/customer/{id}", custno)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andReturn().getResponse();
        JsonNode resp = JSON.readTree(r.getContentAsString());

        String fail = out.path("COMM-UPD-FAIL-CD").asText();
        if ("Y".equals(out.path("COMM-UPD-SUCCESS").asText())) {
            assertThat(r.getStatus()).isEqualTo(200);
            assertThat(resp.path("id").asText()).isEqualTo(out.path("COMM-CUSTNO").asText());
            assertThat(resp.path("sortCode").asText()).isEqualTo(out.path("COMM-SCODE").asText());
            assertThat(resp.path("customerName").asText()).isEqualTo(out.path("COMM-NAME").asText());
            assertThat(resp.path("customerAddress").asText()).isEqualTo(out.path("COMM-ADDR").asText());
            assertThat(resp.path("dateOfBirth").asText()).isEqualTo(iso(out.path("COMM-DOB").asInt()));
            assertThat(resp.path("customerCreditScore").asText()).isEqualTo(out.path("COMM-CREDIT-SCORE").asText());
            assertThat(resp.path("customerCreditScoreReviewDate").asText())
                    .isEqualTo(iso(out.path("COMM-CS-REVIEW-DATE").asInt()));
        } else {
            assertThat(r.getStatus()).isEqualTo(Map.of("T", 400, "4", 400, "1", 404, "2", 500, "3", 500).get(fail));
            assertThat(resp.path("failCode").asText()).isEqualTo(fail);
            assertThat(resp.path("errorMessage").asText()).isNotBlank();
        }
        assertThat(DbDelta.diff(before, DbDelta.snapshot(jdbc))).isEqualTo(DbDelta.expected(t.path("db_delta")));
    }

    private static String iso(int ddmmyyyy) {
        return LocalDate.parse(String.format("%08d", ddmmyyyy), CbsaFormats.DDMMYYYY).toString();
    }

    private static void assertEnv(JsonNode t) {
        assertThat(t.path("program").asText()).isEqualTo("UPDCUST");
        assertThat(t.path("env").path("CBSA_FIXED_TIME").asText()).isEqualTo("2026-01-15T10:30:00Z");
        assertThat(List.of("42")).contains(t.path("env").path("CBSA_RANDOM_SEED").asText());
    }
}
