package com.ibm.cics.cip.bank.core.crecust;

import com.ibm.cics.cip.bank.core.common.config.CbsaProperties;
import com.ibm.cics.cip.bank.core.common.config.CbsaRandom;
import com.ibm.cics.cip.bank.core.common.config.CbsaRandomFactory;
import java.util.Locale;
import java.util.Random;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Replaces {@code FUNCTION RANDOM} in CRDTAGY1-5 ({@code RANDOM(EIBTASKN)}) and in CRECUST's review-date
 * computation ({@code RANDOM(EIBTASKN)} of the CRECUST task).
 *
 * <p>Algorithms (property {@code cbsa.credit.random-algorithm}):
 * <ul>
 * <li>{@code legacy} (default): the generator documented in {@code baseline/README.md} and used by the legacy
 * harness when {@code CBSA_RANDOM_SEED} is set: agency <i>n</i> draws from
 * {@code new java.util.Random(seed + T + n)} and the review date from {@code new java.util.Random(seed + T)},
 * where {@code T} is the CRECUST task number (EIBTASKN) and child task <i>n</i> runs as {@code T + n}.
 * This reproduces {@code baseline/transcripts/CRECUST/*.json}.</li>
 * <li>{@code cbsa-lcg}: {@link CbsaRandomFactory#creditAgencyStream(int)} (seed + n, same generator) for agency <i>n</i> and
 * {@link CbsaRandomFactory#stream(int) stream(0)} for the review date (independent of the task number).</li>
 * </ul>
 * Without {@code cbsa.random.seed} both use unseeded {@link CbsaRandomFactory} streams.
 */
@Component
public class CreditRandomSource {

    public enum Algorithm {
        LEGACY, CBSA_LCG;

        static Algorithm parse(String value) {
            if (value == null || value.isBlank()) {
                return LEGACY;
            }
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
    }

    private final CbsaRandomFactory factory;
    private final Long seed;
    private final Algorithm algorithm;

    public CreditRandomSource(CbsaRandomFactory factory, CbsaProperties properties,
            @Value("${cbsa.credit.random-algorithm:legacy}") String algorithm) {
        this.factory = factory;
        this.seed = properties.random() == null ? null : properties.random().seed();
        this.algorithm = Algorithm.parse(algorithm);
    }

    public Algorithm algorithm() {
        return algorithm;
    }

    /** Stream for CRDTAGY{agency}; draw 1 = delay, draw 2 = score. */
    public DoubleSupplier agencyStream(int agency, long crecustTaskNumber) {
        if (useFactory()) {
            CbsaRandom r = factory.creditAgencyStream(agency);
            return r::nextDouble;
        }
        Random r = new Random(seed + crecustTaskNumber + agency);
        return r::nextDouble;
    }

    /** Stream for the CRECUST review-date offset ({@code RANDOM(EIBTASKN)}). */
    public DoubleSupplier reviewDateStream(long crecustTaskNumber) {
        if (useFactory()) {
            CbsaRandom r = factory.stream(CbsaRandomFactory.STREAM_CRECUST_REVIEW_DATE);
            return r::nextDouble;
        }
        Random r = new Random(seed + crecustTaskNumber);
        return r::nextDouble;
    }

    /** COBOL {@code COMPUTE X = ((hi - lo) * FUNCTION RANDOM) + lo} into an integer PIC: truncation. */
    static int legacyInt(DoubleSupplier random, int lo, int hi) {
        return (int) Math.floor((hi - lo) * random.getAsDouble()) + lo;
    }

    private boolean useFactory() {
        return algorithm == Algorithm.CBSA_LCG || seed == null;
    }
}
