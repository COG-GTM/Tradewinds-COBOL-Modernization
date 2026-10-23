package com.ibm.cics.cip.bank.core.abndproc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** ABNDPROC through the property-gated admin endpoint, replaying baseline/transcripts/ABNDPROC/record.json. */
@SpringBootTest(properties = {"cbsa.abndproc.endpoint.enabled=true", "cbsa.abndproc.endpoint.token=test-token"})
@AutoConfigureMockMvc
class AbendRecordControllerTest {

    static final Path TRANSCRIPT = Path.of("../../baseline/transcripts/ABNDPROC/record.json");

    @Autowired
    MockMvc mvc;

    @Autowired
    AbendFile file;

    @BeforeEach
    void clear() {
        file.clear();
    }

    private ResultActions write(String token, String body) throws Exception {
        var req = post("/banking/admin/abend").contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(token == null ? req : req.header(AbendRecordController.TOKEN_HEADER, token));
    }

    static String body(JsonNode c) {
        return """
                {"utimeKey":%d,"taskNoKey":%d,"applid":"%s","tranid":"%s","date":"%s","time":"%s","code":"%s",
                 "program":"%s","respCode":%d,"resp2Code":%d,"sqlCode":%d,"freeform":"%s"}""".formatted(
                c.path("ABND-VSAM-KEY").path("ABND-UTIME-KEY").asLong(),
                c.path("ABND-VSAM-KEY").path("ABND-TASKNO-KEY").asInt(), c.path("ABND-APPLID").asText(),
                c.path("ABND-TRANID").asText(), c.path("ABND-DATE").asText(), c.path("ABND-TIME").asText(),
                c.path("ABND-CODE").asText(), c.path("ABND-PROGRAM").asText(), c.path("ABND-RESPCODE").asInt(),
                c.path("ABND-RESP2CODE").asInt(), c.path("ABND-SQLCODE").asInt(), c.path("ABND-FREEFORM").asText());
    }

    @Test
    void legacyTranscriptRecordIsWrittenWithTheSameFields() throws Exception {
        JsonNode t = new ObjectMapper().readTree(TRANSCRIPT.toFile());
        JsonNode out = t.path("commarea_out");
        write("test-token", body(t.path("commarea_in"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.utimeKey").value(out.path("ABND-VSAM-KEY").path("ABND-UTIME-KEY").asLong()))
                .andExpect(jsonPath("$.taskNoKey").value(out.path("ABND-VSAM-KEY").path("ABND-TASKNO-KEY").asInt()))
                .andExpect(jsonPath("$.applid").value(out.path("ABND-APPLID").asText()))
                .andExpect(jsonPath("$.code").value(out.path("ABND-CODE").asText()))
                .andExpect(jsonPath("$.program").value(out.path("ABND-PROGRAM").asText()))
                .andExpect(jsonPath("$.respCode").value(out.path("ABND-RESPCODE").asInt()))
                .andExpect(jsonPath("$.resp2Code").value(out.path("ABND-RESP2CODE").asInt()))
                .andExpect(jsonPath("$.freeform").value(out.path("ABND-FREEFORM").asText()));
        mvc.perform(get("/banking/admin/abend").header(AbendRecordController.TOKEN_HEADER, "test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].tranid").value("ODCS"));
    }

    @Test
    void duplicateKeyIsDuprecAndNothingIsWritten() throws Exception {
        String rec = "{\"utimeKey\":1,\"taskNoKey\":2,\"code\":\"HWPT\",\"freeform\":\"first\"}";
        write("test-token", rec).andExpect(status().isCreated());
        write("test-token", rec.replace("first", "second")).andExpect(status().isConflict());
        assertThat(file.records()).singleElement().extracting(AbendRecord::freeform).isEqualTo("first");
    }

    @Test
    void fieldsAreFittedToTheAbndinfoPictures() throws Exception {
        write("test-token", "{\"utimeKey\":1,\"taskNoKey\":123456,\"applid\":\"APPLID-TOO-LONG\",\"code\":\"ABCDE\","
                + "\"respCode\":-123456789}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taskNoKey").value(3456))
                .andExpect(jsonPath("$.applid").value("APPLID-T"))
                .andExpect(jsonPath("$.code").value("ABCD"))
                .andExpect(jsonPath("$.respCode").value(-23456789));
    }

    @Autowired
    org.springframework.context.ApplicationEventPublisher events;

    @Autowired
    com.ibm.cics.cip.bank.core.common.proctran.TaskNumberSupplier tasks;

    @Test
    void serviceAbendsAreWrittenToAbndfile() {
        tasks.restartAt(4321);
        tasks.inTask(() -> {
            events.publishEvent(new com.ibm.cics.cip.bank.core.common.error.AbendEvent(
                    java.time.Instant.parse("2026-01-15T10:30:00Z"), "HWPT", "XFRFUN", "PROCTRAN insert failed"));
            return null;
        });
        assertThat(file.records()).singleElement().satisfies(r -> {
            assertThat(r.utimeKey()).isEqualTo(3_977_461_800_000L);
            assertThat(r.taskNoKey()).isEqualTo(4321);
            assertThat(r.applid()).isEqualTo(AbendFile.APPLID);
            assertThat(r.date()).isEqualTo("15.01.2026");
            assertThat(r.time()).isEqualTo("10:30:00");
            assertThat(r.code()).isEqualTo("HWPT");
            assertThat(r.program()).isEqualTo("XFRFUN");
            assertThat(r.freeform()).isEqualTo("PROCTRAN insert failed");
        });
    }

    @Test
    void tokenIsRequired() throws Exception {
        write(null, "{\"utimeKey\":1}").andExpect(status().isForbidden());
        write("wrong", "{\"utimeKey\":1}").andExpect(status().isForbidden());
        mvc.perform(get("/banking/admin/abend")).andExpect(status().isForbidden());
        assertThat(file.records()).isEmpty();
    }
}
