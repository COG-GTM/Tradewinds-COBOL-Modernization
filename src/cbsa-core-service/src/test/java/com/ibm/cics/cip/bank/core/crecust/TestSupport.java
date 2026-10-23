package com.ibm.cics.cip.bank.core.crecust;

import com.ibm.cics.cip.bank.core.common.config.CbsaProperties;
import com.ibm.cics.cip.bank.core.common.config.CbsaRandomFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

final class TestSupport {

    static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-15T10:30:00Z"), ZoneOffset.UTC);

    private TestSupport() {
    }

    static CbsaProperties properties(Long seed, boolean simulateDelay) {
        return new CbsaProperties("987654", "CICS Bank Sample Application",
                new CbsaProperties.Seed(List.of(), "never"),
                new CbsaProperties.Clock("2026-01-15T10:30:00Z", "UTC"),
                new CbsaProperties.Random(seed),
                new CbsaProperties.Credit(simulateDelay));
    }

    static CreditRandomSource randoms(Long seed, String algorithm) {
        CbsaProperties p = properties(seed, false);
        return new CreditRandomSource(new CbsaRandomFactory(p), p, algorithm);
    }

    static List<CreditAgency> agencies() {
        return List.of(new CreditAgency(1), new CreditAgency(2), new CreditAgency(3), new CreditAgency(4),
                        new CreditAgency(5));
    }
}
