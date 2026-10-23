package com.ibm.cics.cip.bank.core.abndproc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cics.cip.bank.core.common.error.AbendEvent;

@SpringBootTest(properties = {"cbsa.clock.fixed=2026-01-15T10:30:00Z", "cbsa.random.seed=42"})
@AutoConfigureMockMvc
class AbendRecorderTest {

    @Autowired
    ApplicationEventPublisher events;
    @Autowired
    AbendRecorder recorder;
    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void recentIsNewestFirstAndBounded() {
        AbendRecorder r = new AbendRecorder();
        for (int i = 0; i < AbendRecorder.CAPACITY + 5; i++) {
            r.record(new AbendEvent(Instant.EPOCH.plusSeconds(i), "A" + i, "P", "f"));
        }
        assertThat(r.recent(2)).extracting(AbendEvent::abendCode)
                .containsExactly("A" + (AbendRecorder.CAPACITY + 4), "A" + (AbendRecorder.CAPACITY + 3));
        assertThat(r.recent(Integer.MAX_VALUE)).hasSize(AbendRecorder.CAPACITY);
        assertThat(r.recent(-1)).isEmpty();
    }

    @Test
    void listensForPublishedAbendEventsAndServesThem() throws Exception {
        events.publishEvent(new AbendEvent(Instant.parse("2026-01-15T10:30:00Z"), "HWPT", "INQCUST", "unit test"));
        assertThat(recorder.recent(1).get(0).abendCode()).isEqualTo("HWPT");
        mvc.perform(get("/banking/abend").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].abendCode").value("HWPT"))
                .andExpect(jsonPath("$[0].program").value("INQCUST"))
                .andExpect(jsonPath("$[0].freeform").value("unit test"));
        mvc.perform(get("/banking/abend")).andExpect(status().isOk());
    }

    /** Replays baseline/transcripts/ABNDPROC/*.json: the record is stored and no table changes. */
    @Test
    void replaysAbndprocTranscripts() throws Exception {
        File dir = new File("../../baseline/transcripts/ABNDPROC");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        assertThat(files).isNotEmpty();
        ObjectMapper json = new ObjectMapper();
        for (File f : files) {
            JsonNode t = json.readTree(f);
            JsonNode in = t.get("commarea_in");
            Map<String, List<Map<String, Object>>> before = snapshot();
            events.publishEvent(new AbendEvent(Instant.parse("2026-01-15T10:30:00Z"), in.get("ABND-CODE").asText(),
                    in.get("ABND-PROGRAM").asText(), in.get("ABND-FREEFORM").asText()));
            AbendEvent stored = recorder.recent(1).get(0);
            JsonNode out = t.get("commarea_out");
            assertThat(stored.abendCode()).isEqualTo(out.get("ABND-CODE").asText());
            assertThat(stored.program()).isEqualTo(out.get("ABND-PROGRAM").asText());
            assertThat(stored.freeform()).isEqualTo(out.get("ABND-FREEFORM").asText());
            assertThat(t.get("abend").isNull()).isTrue();
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    Map<String, List<Map<String, Object>>> snapshot() {
        return Map.of("CUSTOMER", jdbc.queryForList("SELECT * FROM CUSTOMER"),
                "ACCOUNT", jdbc.queryForList("SELECT * FROM ACCOUNT"),
                "PROCTRAN", jdbc.queryForList("SELECT * FROM PROCTRAN"));
    }
}
