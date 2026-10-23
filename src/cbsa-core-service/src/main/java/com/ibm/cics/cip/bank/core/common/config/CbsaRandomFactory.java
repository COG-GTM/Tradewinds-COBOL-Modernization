package com.ibm.cics.cip.bank.core.common.config;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/** Hands out {@link CbsaRandom} streams; deterministic when cbsa.random.seed is set. */
@Component
public class CbsaRandomFactory {

    public static final int STREAM_CRECUST_REVIEW_DATE = 0;

    private final Long seed;

    public CbsaRandomFactory(CbsaProperties properties) {
        this.seed = properties.random().seed();
    }

    public boolean isDeterministic() {
        return seed != null;
    }

    /** Stream k: seeded with (cbsa.random.seed + k), or a random seed when unset. */
    public CbsaRandom stream(int k) {
        long base = seed != null ? seed : ThreadLocalRandom.current().nextLong(1L << 31);
        return new CbsaRandom(base + k);
    }

    /** Stream used by credit agency CRDTAGY{n} (n = 1..5). */
    public CbsaRandom creditAgencyStream(int agency) {
        return stream(agency);
    }
}
