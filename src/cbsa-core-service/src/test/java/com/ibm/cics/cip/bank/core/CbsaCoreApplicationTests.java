package com.ibm.cics.cip.bank.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.ibm.cics.cip.bank.core.common.config.CbsaRandom;
import com.ibm.cics.cip.bank.core.common.counter.CounterService;
import com.ibm.cics.cip.bank.core.common.domain.ProcessedTransaction;
import com.ibm.cics.cip.bank.core.common.proctran.ProctranWriter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Smoke test: context, schema, shared beans, error shape and context path. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "cbsa.clock.fixed=2026-01-15T10:30:00Z",
        "cbsa.random.seed=42"})
class CbsaCoreApplicationTests {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    @Autowired
    ProctranWriter proctranWriter;

    @Autowired
    CounterService counters;

    @Test
    void contextStartsWithCanonicalSchema() {
        for (String table : new String[] {"CUSTOMER", "ACCOUNT", "PROCTRAN", "CONTROL"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).isNotNull();
        }
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-01-15T10:30:00Z"));
    }

    @Test
    @SuppressWarnings("rawtypes")
    void bankIdentityEndpointsServedUnderContextPath() {
        ResponseEntity<Map> sort = rest.getForEntity("/banking/sortCode", Map.class);
        assertThat(sort.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sort.getBody()).containsEntry("sortCode", "987654");

        ResponseEntity<Map> company = rest.getForEntity("/banking/companyName", Map.class);
        assertThat(company.getBody()).containsEntry("companyName", "CICS Bank Sample Application");

        ResponseEntity<Map> health = rest.getForEntity("/actuator/health", Map.class);
        assertThat(health.getBody()).containsEntry("status", "UP");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void unknownPathReturnsErrorShape() {
        ResponseEntity<Map> r = rest.getForEntity("/banking/does-not-exist", Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).containsKey("errorMessage");
    }

    @Test
    void proctranWriterUsesFixedClockAndLegacyLayout() {
        ProcessedTransaction row = proctranWriter.transfer("987654", 1, "987654", 2, new BigDecimal("12.34"));
        assertThat(row.date()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(row.time()).isEqualTo("103000");
        assertThat(row.type()).isEqualTo("TFR");
        assertThat(row.description()).hasSize(40).startsWith("TRANSFER").endsWith("98765400000002");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM PROCTRAN WHERE PROCTRAN_TYPE = 'TFR'", Long.class))
                .isPositive();
    }

    @Test
    void counterAllocatesSequentialNumbers() {
        long first = counters.nextAccountNumber();
        long second = counters.nextAccountNumber();
        assertThat(second).isEqualTo(first + 1);
        assertThat(counters.currentAccountNumber()).isEqualTo(second);
    }

    @Test
    void cbsaRandomIsDeterministic() {
        CbsaRandom a = new CbsaRandom(42);
        CbsaRandom b = new CbsaRandom(42);
        for (int i = 0; i < 5; i++) {
            int score = a.nextInt(1, 999);
            assertThat(score).isEqualTo(b.nextInt(1, 999)).isBetween(1, 998);
        }
    }
}
