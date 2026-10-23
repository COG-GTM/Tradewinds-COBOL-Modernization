package com.ibm.cics.cip.bank.core.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All {@code cbsa.*} properties. Ticket packages read these; only the integration agent adds new
 * ones (application*.properties is shared).
 *
 * @param sortcode     bank sort code (legacy copybook SORTCODE.cpy, VALUE 987654)
 * @param companyName  company name returned by GETCOMPY
 * @param seed         seed-data loading
 * @param clock        deterministic clock (shared contract: cbsa.clock.fixed)
 * @param random       deterministic randomness (shared contract: cbsa.random.seed)
 * @param credit       credit-agency simulation (CRDTAGY1-5)
 */
@ConfigurationProperties(prefix = "cbsa")
public record CbsaProperties(
        @DefaultValue("987654") String sortcode,
        @DefaultValue("CICS Bank Sample Application") String companyName,
        @DefaultValue Seed seed,
        @DefaultValue Clock clock,
        @DefaultValue Random random,
        @DefaultValue Credit credit) {

    public record Seed(
            @DefaultValue({"file:../../baseline/seed/seed.sql", "classpath:seed/seed.sql"}) List<String> location,
            @DefaultValue("if-empty") String mode) {
    }

    /** {@code fixed}: ISO-8601 instant (2026-01-15T10:30:00Z) or local date-time (treated in {@code zone}). */
    public record Clock(String fixed, @DefaultValue("UTC") String zone) {
    }

    /** {@code seed}: when set, all CBSA randomness is reproducible (see CbsaRandom). */
    public record Random(Long seed) {
    }

    public record Credit(@DefaultValue("false") boolean simulateDelay) {
    }
}
