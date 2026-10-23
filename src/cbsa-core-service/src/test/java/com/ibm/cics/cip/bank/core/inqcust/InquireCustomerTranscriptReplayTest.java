package com.ibm.cics.cip.bank.core.inqcust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.config.CbsaRandomFactory;
import com.ibm.cics.cip.bank.core.common.proctran.TaskNumberSupplier;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.repository.CustomerRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Replays every {@code baseline/transcripts/INQCUST/*.json} against {@link InquireCustomerService}
 * from the canonical seed with the transcript's fixed clock and random seed, asserting the COMMAREA
 * outputs, fail codes and DB deltas (CUSTOMER/ACCOUNT/PROCTRAN/CONTROL) match.
 *
 * <p>Random-customer cases run with EIBTASKN = {@code CBSA_TASKN} and the legacy (GnuCOBOL) COMPUTE arithmetic.
 */
@SpringBootTest(properties = {
        "cbsa.clock.fixed=2026-01-15T10:30:00Z",
        "cbsa.random.seed=42"})
@AutoConfigureMockMvc
class InquireCustomerTranscriptReplayTest {

    static final Path TRANSCRIPTS = Path.of("../../baseline/transcripts/INQCUST");
    static final List<String> TABLES = List.of("CUSTOMER", "ACCOUNT", "PROCTRAN", "CONTROL");

    @Autowired
    CustomerRepository customers;

    @Autowired
    BankIdentity bank;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> replayBaselineTranscripts() throws IOException {
        List<Path> files;
        try (Stream<Path> list = Files.list(TRANSCRIPTS)) {
            files = list.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(files).as("INQCUST transcripts").isNotEmpty();
        return files.stream().map(f -> DynamicTest.dynamicTest(f.getFileName().toString(), () -> replay(f)));
    }

    private void replay(Path file) throws Exception {
        JsonNode t = json.readTree(file.toFile());
        assertThat(t.path("program").asText()).isEqualTo("INQCUST");
        JsonNode in = t.path("commarea_in");
        JsonNode out = t.path("commarea_out");
        long requested = in.path("INQCUST-CUSTNO").asLong();
        Long seed = t.path("env").hasNonNull("CBSA_RANDOM_SEED") ? t.path("env").path("CBSA_RANDOM_SEED").asLong() : null;
        TaskNumberSupplier tasks = new TaskNumberSupplier();
        tasks.restartAt(t.path("env").path("CBSA_TASKN").asLong(100));
        InquireCustomerService service = new InquireCustomerService(customers, bank,
                new CbsaRandomFactory(InquireCustomerServiceTest.properties(seed)), tasks, "gnucobol");

        Map<String, List<Map<String, Object>>> before = snapshot();
        Optional<Customer> result = service.inquire(requested);
        assertThat(snapshot()).as("DB delta").isEqualTo(before);
        assertNoRecordedDelta(t.path("db_delta"));
        assertThat(t.path("abend").isNull()).isTrue();

        if ("Y".equals(out.path("INQCUST-INQ-SUCCESS").asText())) {
            assertThat(result).as("customer found").isPresent();
            Customer c = result.get();
            assertThat(c.eyecatcher()).isEqualTo(out.path("INQCUST-EYE").asText());
            assertThat(c.sortCode()).isEqualTo(out.path("INQCUST-SCODE").asText());
            assertThat(c.customerNumber()).isEqualTo(out.path("INQCUST-CUSTNO").asLong());
            assertThat(c.name()).isEqualTo(out.path("INQCUST-NAME").asText().strip());
            assertThat(c.address()).isEqualTo(out.path("INQCUST-ADDR").asText().strip());
            assertThat(c.dateOfBirth()).isEqualTo(date(out.path("INQCUST-DOB"), "INQCUST-DOB-"));
            assertThat(c.creditScore()).isEqualTo(out.path("INQCUST-CREDIT-SCORE").asInt());
            assertThat(c.creditScoreReviewDate()).isEqualTo(date(out.path("INQCUST-CS-REVIEW-DT"), "INQCUST-CS-REVIEW-"));
            assertThat(out.path("INQCUST-INQ-FAIL-CD").asText()).isEqualTo("0");
        } else {
            assertThat(result).as("customer not found").isEmpty();
            String failCode = out.path("INQCUST-INQ-FAIL-CD").asText();
            mvc.perform(get("/webui-1.0/banking/customer/" + requested).contextPath("/webui-1.0"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.failCode").value(failCode))
                    .andExpect(jsonPath("$.errorMessage").value("Customer number " + requested + " not found."));
        }
    }

    private static LocalDate date(JsonNode node, String prefix) {
        return LocalDate.of(node.path(prefix + "YYYY").asInt(), node.path(prefix + "MM").asInt(),
                node.path(prefix + "DD").asInt());
    }

    private static void assertNoRecordedDelta(JsonNode delta) {
        for (String table : TABLES) {
            for (String kind : List.of("inserted", "updated", "deleted")) {
                assertThat(delta.path(table).path(kind).size()).as(table + "." + kind).isZero();
            }
        }
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : TABLES) {
            rows.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1, 2"));
        }
        return rows;
    }
}
