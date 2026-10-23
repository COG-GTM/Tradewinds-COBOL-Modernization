package com.ibm.cics.cip.bank.core.common.seed;

import com.ibm.cics.cip.bank.core.common.config.CbsaProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/**
 * Loads the canonical seed data (baseline/seed/seed.sql, shared with the legacy harness) after
 * schema.sql has run. Locations come from cbsa.seed.location (first existing wins); a missing
 * file is tolerated. Mode cbsa.seed.mode: if-empty | always | never.
 */
@Component
public class SeedDataLoader implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SeedDataLoader.class);

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final ResourceLoader resourceLoader;
    private final CbsaProperties properties;
    private String loadedFrom;

    // JdbcTemplate injection makes Boot order this bean after spring.sql.init (schema.sql).
    public SeedDataLoader(JdbcTemplate jdbcTemplate, DataSource dataSource, ResourceLoader resourceLoader,
            CbsaProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.resourceLoader = resourceLoader;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        String mode = properties.seed().mode() == null ? "if-empty" : properties.seed().mode().trim().toLowerCase();
        if ("never".equals(mode)) {
            log.info("CBSA seed loading disabled (cbsa.seed.mode=never)");
            return;
        }
        if ("if-empty".equals(mode) && !isEmpty()) {
            log.info("CBSA seed skipped: CUSTOMER/ACCOUNT already contain rows");
            return;
        }
        Resource seed = firstExisting(properties.seed().location());
        if (seed == null) {
            log.warn("CBSA seed file not found in {}; starting with empty tables", properties.seed().location());
            return;
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(seed);
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.execute(dataSource);
        loadedFrom = seed.getDescription();
        log.info("CBSA seed loaded from {}", loadedFrom);
    }

    /** Description of the seed resource loaded, or null if none was loaded. */
    public String loadedFrom() {
        return loadedFrom;
    }

    private boolean isEmpty() {
        Long customers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM CUSTOMER", Long.class);
        Long accounts = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ACCOUNT", Long.class);
        return (customers == null || customers == 0) && (accounts == null || accounts == 0);
    }

    private Resource firstExisting(List<String> locations) {
        if (locations == null) {
            return null;
        }
        for (String location : locations) {
            if (location == null || location.isBlank()) {
                continue;
            }
            Resource r = resourceLoader.getResource(location.trim());
            if (r.exists() && r.isReadable()) {
                return r;
            }
        }
        return null;
    }
}
