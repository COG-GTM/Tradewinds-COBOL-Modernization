package com.ibm.cics.cip.bank.core.dbcrfun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Replays every baseline/transcripts/DBCRFUN/*.json (recorded from the legacy COBOL under GnuCOBOL) against
 * {@code PUT /makepayment/dbcr} from the canonical seed and asserts the COMMAREA out and the full db_delta
 * (CUSTOMER, ACCOUNT, PROCTRAN excluding PROCTRAN_REF, CONTROL) are identical.
 */
@SpringBootTest(properties = {DbcrfunTestDb.DB, DbcrfunTestDb.CLOCK, DbcrfunTestDb.RANDOM})
@AutoConfigureMockMvc
class DbcrfunTranscriptReplayTest {

    static final Path TRANSCRIPTS = DbcrfunTestDb.REPO_ROOT.resolve("baseline/transcripts/DBCRFUN");
    static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    static Stream<Path> transcripts() throws IOException {
        try (Stream<Path> s = Files.list(TRANSCRIPTS)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted(Comparator.comparing(Path::toString))
                    .toList().stream();
        }
    }

    @Test
    void allTwelveTranscriptsArePresent() throws IOException {
        assertThat(transcripts().count()).isEqualTo(12);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transcripts")
    void replay(Path file) throws Exception {
        JsonNode t = JSON.readTree(file.toFile());
        assertThat(t.path("program").asText()).isEqualTo("DBCRFUN");
        assertThat(t.path("env").path("CBSA_FIXED_TIME").asText()).isEqualTo("2026-01-15T10:30:00Z");

        DbcrfunTestDb.resetToSeed(jdbc, dataSource);
        for (JsonNode sql : t.path("setup_sql")) {
            jdbc.execute(sql.asText());
        }
        var before = DbcrfunTestDb.snapshot(jdbc);

        String response = mvc.perform(put("/makepayment/dbcr").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(toPayDbcr(t.path("commarea_in")))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(t.path("abend").isNull()).as("no DBCRFUN transcript abends").isTrue();
        assertThat(fromPayDbcr(JSON.readTree(response).path("PAYDBCR")))
                .as("COMMAREA out").isEqualTo(normalizeTree(t.path("commarea_out")));

        Map<String, Object> actualDelta = DbcrfunTestDb.delta(before, DbcrfunTestDb.snapshot(jdbc));
        assertThat(stripRef(normalizeObject(actualDelta)))
                .as("db_delta").isEqualTo(stripRef(normalizeTree(t.path("db_delta"))));
    }

    /** COMMAREA (PAYDBCR copybook names) -> z/OS Connect JSON. */
    static ObjectNode toPayDbcr(JsonNode in) {
        ObjectNode p = JSON.createObjectNode();
        p.put("CommAccno", in.path("COMM-ACCNO").asText());
        p.set("CommAmt", in.path("COMM-AMT"));
        p.put("mSortC", in.path("COMM-SORTC").asInt());
        p.set("CommAvBal", in.has("COMM-AV-BAL") ? in.get("COMM-AV-BAL") : JSON.getNodeFactory().numberNode(0));
        p.set("CommActBal", in.has("COMM-ACT-BAL") ? in.get("COMM-ACT-BAL") : JSON.getNodeFactory().numberNode(0));
        JsonNode o = in.path("COMM-ORIGIN");
        ObjectNode origin = p.putObject("CommOrigin");
        origin.put("CommApplid", o.path("COMM-APPLID").asText(""));
        origin.put("CommUserid", o.path("COMM-USERID").asText(""));
        origin.put("CommFacilityName", o.path("COMM-FACILITY-NAME").asText(""));
        origin.put("CommNetwrkId", o.path("COMM-NETWRK-ID").asText(""));
        origin.put("CommFaciltype", o.path("COMM-FACILTYPE").asInt());
        origin.put("Fill0", "    ");
        p.put("CommSuccess", " ");
        p.put("CommFailCode", " ");
        ObjectNode env = JSON.createObjectNode();
        env.set("PAYDBCR", p);
        return env;
    }

    /** z/OS Connect JSON -> COMMAREA names, normalized. */
    static Object fromPayDbcr(JsonNode p) {
        Map<String, Object> m = new TreeMap<>();
        m.put("COMM-ACCNO", p.path("CommAccno").asText());
        m.put("COMM-AMT", p.path("CommAmt").decimalValue());
        m.put("COMM-SORTC", p.path("mSortC").asInt());
        m.put("COMM-AV-BAL", p.path("CommAvBal").decimalValue());
        m.put("COMM-ACT-BAL", p.path("CommActBal").decimalValue());
        JsonNode o = p.path("CommOrigin");
        Map<String, Object> origin = new TreeMap<>();
        origin.put("COMM-APPLID", o.path("CommApplid").asText());
        origin.put("COMM-USERID", o.path("CommUserid").asText());
        origin.put("COMM-FACILITY-NAME", o.path("CommFacilityName").asText());
        origin.put("COMM-NETWRK-ID", o.path("CommNetwrkId").asText());
        origin.put("COMM-FACILTYPE", o.path("CommFaciltype").asInt());
        m.put("COMM-ORIGIN", origin);
        m.put("COMM-SUCCESS", p.path("CommSuccess").asText());
        m.put("COMM-FAIL-CODE", p.path("CommFailCode").asText());
        return normalizeObject(m);
    }

    static Object normalizeTree(JsonNode n) {
        return normalizeObject(JSON.convertValue(n, Object.class));
    }

    @SuppressWarnings("unchecked")
    static Object normalizeObject(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new TreeMap<>();
            m.forEach((k, val) -> out.put(k.toString(), normalizeObject(val)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(x -> out.add(normalizeObject(x)));
            return out;
        }
        return DbcrfunTestDb.normalize(v);
    }

    /** PROCTRAN_REF (EIBTASKN) is runtime-specific and excluded from the comparison. */
    @SuppressWarnings("unchecked")
    static Object stripRef(Object delta) {
        Map<String, Object> d = new LinkedHashMap<>((Map<String, Object>) delta);
        Map<String, Object> proctran = new LinkedHashMap<>((Map<String, Object>) d.get("PROCTRAN"));
        for (String part : List.of("inserted", "deleted")) {
            List<Object> rows = new ArrayList<>();
            for (Object r : (List<Object>) proctran.get(part)) {
                Map<String, Object> row = new TreeMap<>((Map<String, Object>) r);
                row.remove("PROCTRAN_REF");
                rows.add(row);
            }
            proctran.put(part, rows);
        }
        d.put("PROCTRAN", proctran);
        return d;
    }

}
