package com.ibm.cics.cip.bank.core.delcus;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.proctran.ProctranWriter;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Shared context for the DELCUS tests: H2 database private to these tests, fixed clock and random seed
 * (the transcripts' CBSA_FIXED_TIME / CBSA_RANDOM_SEED), and the canonical seed reloaded before each test.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cbsa-delcus;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "cbsa.clock.fixed=" + DelcusTestSupport.FIXED_TIME,
        "cbsa.random.seed=" + DelcusTestSupport.RANDOM_SEED})
@AutoConfigureMockMvc
@Import(DelcusTestSupport.StubDelaccConfig.class)
abstract class DelcusTestSupport {

    static final String FIXED_TIME = "2026-01-15T10:30:00Z";
    static final String RANDOM_SEED = "42";
    static final Path BASELINE = locateBaseline();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    StubDeleteAccountService delacc;

    @SpyBean
    ProctranWriter proctranWriter;

    @TestConfiguration
    static class StubDelaccConfig {
        @Bean
        @Primary
        StubDeleteAccountService stubDeleteAccountService(AccountRepository accounts, ProctranWriter proctran,
                BankIdentity bank) {
            return new StubDeleteAccountService(accounts, proctran, bank);
        }
    }

    @BeforeEach
    void reloadCanonicalSeed() {
        delacc.reset();
        for (String table : new String[] {"PROCTRAN", "ACCOUNT", "CUSTOMER", "CONTROL"}) {
            jdbc.update("DELETE FROM " + table);
        }
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new FileSystemResource(BASELINE.resolve("seed/seed.sql")));
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.execute(dataSource);
    }

    long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static Path locateBaseline() {
        for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("baseline");
            if (Files.isRegularFile(candidate.resolve("seed/seed.sql"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("baseline/seed/seed.sql not found above " + Paths.get("").toAbsolutePath());
    }
}
